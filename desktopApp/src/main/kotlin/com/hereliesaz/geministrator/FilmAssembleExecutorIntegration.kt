package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.control.ControlRedaction
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.releasedDependencies
import com.hereliesaz.geministrator.workflow.ArtifactVerdicts
import com.hereliesaz.geministrator.workflow.FILM_ASSEMBLE_SERVICE
import com.hereliesaz.geministrator.workflow.FILM_TASK_FRAMES
import com.hereliesaz.geministrator.workflow.FILM_TASK_FRAME_REVIEW
import com.hereliesaz.geministrator.workflow.FILM_TASK_STORYBOARD
import com.hereliesaz.geministrator.workflow.FilmFrameReview
import com.hereliesaz.geministrator.workflow.FilmStoryboard
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.TaskExecutorExecution
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegration
import com.hereliesaz.geministrator.workflow.parseStoryboard
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** How long ffmpeg may run before it and everything it started is stopped. */
const val FILM_FFMPEG_TIMEOUT_MILLIS: Long = 15L * 60L * 1000L

/** Words a keeper reads when ffmpeg is missing. */
internal const val FFMPEG_INSTALL_HINT: String =
    "ffmpeg is not installed on this computer (looked on PATH and in the usual install folders). Install it " +
        "(macOS: brew install ffmpeg; Windows: winget install Gyan.FFmpeg; Debian or Ubuntu: sudo apt install ffmpeg), " +
        "restart The Aive, and run this step again. The frames are kept."

/**
 * The short-film hive's assemble step (docs/Short-Film.md): turns the drawn frames into an .mp4 with ffmpeg, on this
 * computer. Desktop only; where this is not registered the engine blocks the step as "not available in this runtime".
 *
 * - **Gate.** It refuses to run while the frame review has sent shots back (a `regenerate-shots` list), says which,
 *   and records them so running the frames step again draws only those.
 * - **Safe inputs.** A frame's path comes only from [FileFilmFrameStore.verifiedFrame] (this film's folder, a plain
 *   file, no `..`, no link out); durations come from the parsed storyboard; the command is [FfmpegFilmPlan], an
 *   argument list given to [runner], never a shell string. ffmpeg gets an environment of a few plain variables.
 * - **Bounded.** [timeoutMillis] (15 minutes); on timeout or cancel the runner ends the whole process tree and the
 *   half-written file is deleted. A missing ffmpeg fails the step at once with how to install it.
 * - **Output.** `<outputRoot>/<run>/film.mp4`, reported as a Media artifact of type video/mp4.
 */
internal class FilmAssembleExecutorIntegration(
    private val store: FileFilmFrameStore,
    private val runner: CliProcessRunner = ProcessCliRunner,
    private val locate: () -> File? = { CliLocator.locateBinary(listOf("ffmpeg")) },
    private val outputRoot: File = defaultOutputRoot(),
    private val environment: () -> Map<String, String> = { System.getenv() },
    private val timeoutMillis: Long = FILM_FFMPEG_TIMEOUT_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : TaskExecutorIntegration {
    override val orchestrationToolId: String = FILM_ASSEMBLE_SERVICE
    override val retryDispatchFailures: Boolean = false

    override fun supports(executor: TaskExecutor): Boolean =
        executor is TaskExecutor.ExternalService && executor.service == FILM_ASSEMBLE_SERVICE

    private val runs = java.util.concurrent.ConcurrentHashMap<String, AssembleRun>()

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution = try {
        start(context)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: AssembleFailure) {
        TaskExecutorExecution(status = TaskRunStatus.Failed, progressMessage = failure.message)
    } catch (failure: Throwable) {
        TaskExecutorExecution(
            status = TaskRunStatus.Failed,
            progressMessage = "Assembling the film failed: " + clean(failure.message ?: failure::class.simpleName.orEmpty()),
        )
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution {
        val known = context.taskRun.externalRunId?.let(runs::get)
        // Not ours any more (the app restarted): assemble again from the kept frames.
        return if (known == null) dispatch(context) else snapshot(known)
    }

    override suspend fun cancel(context: TaskExecutorContext) {
        context.taskRun.externalRunId?.let(runs::remove)?.job?.cancel()
    }

    private suspend fun start(context: TaskExecutorContext): TaskExecutorExecution {
        val inputs = gather(context)
        val ffmpeg = locate() ?: throw AssembleFailure(FFMPEG_INSTALL_HINT)
        val folder = File(outputRoot, safeName(context.run.id.value)).also { it.mkdirs() }
        val partial = File(folder, "film.partial.mp4")
        val finalFile = File(folder, "film.mp4")
        val command = try {
            FfmpegFilmPlan.build(ffmpeg.absolutePath, inputs.clips, partial.absolutePath)
        } catch (failure: IllegalArgumentException) {
            throw AssembleFailure("The film can't be assembled: ${failure.message}")
        }

        val externalId = "$FILM_ASSEMBLE_SERVICE:${context.taskRun.id.value}"
        val run = AssembleRun(externalId, FfmpegFilmPlan.totalMillis(inputs.clips))
        runs.remove(externalId)?.job?.cancel()
        runs[externalId] = run
        run.job = scope.launch {
            try {
                run.result = assemble(context, inputs, command, folder, partial, finalFile, run)
            } catch (cancelled: CancellationException) {
                partial.delete()
                throw cancelled
            } catch (failure: Throwable) {
                partial.delete()
                run.result = failed("Assembling the film failed: " + clean(failure.message ?: failure::class.simpleName.orEmpty()))
            }
        }
        return snapshot(run)
    }

    private suspend fun assemble(
        context: TaskExecutorContext,
        inputs: Inputs,
        command: List<String>,
        folder: File,
        partial: File,
        finalFile: File,
        run: AssembleRun,
    ): TaskExecutorExecution {
        partial.delete()
        val result = runner.run(command, folder, ffmpegEnvironment(environment()), null, timeoutMillis) { line ->
            progressMillis(line)?.let { run.doneMillis = it }
        }
        return when {
            result.startFailure != null -> {
                partial.delete()
                failed("ffmpeg could not be started: ${clean(result.startFailure)}. $FFMPEG_INSTALL_HINT")
            }
            result.timedOut -> {
                partial.delete()
                failed(
                    "ffmpeg did not finish within ${timeoutMillis / 60_000} minutes and was stopped, with everything it started. " +
                        "Use fewer or shorter shots, or a faster computer, and run this step again. The frames are kept.",
                )
            }
            result.exitCode != 0 -> {
                partial.delete()
                failed("ffmpeg stopped with exit code ${result.exitCode}: ${clean(result.stderrTail).ifBlank { "no message" }.takeLast(MAX_MESSAGE_CHARS)}")
            }
            !partial.isFile || partial.length() == 0L -> {
                partial.delete()
                failed("ffmpeg finished but wrote no film. Run this step again; if it repeats, run ffmpeg by hand on the frames to see why.")
            }
            else -> {
                Files.move(partial.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                val total = inputs.clips.sumOf { it.seconds }
                TaskExecutorExecution(
                    status = TaskRunStatus.Completed,
                    artifacts = listOf(
                        ArtifactRef(
                            id = ArtifactId("${context.taskRun.id.value}:film:video"),
                            kind = ArtifactKind.Media,
                            taskRunId = context.taskRun.id,
                            label = "Short film · ${inputs.clips.size} shots · $total s",
                            uri = finalFile.toURI().toString(),
                            mediaType = "video/mp4",
                            metadata = mapOf(
                                "filmId" to inputs.filmId,
                                "shots" to inputs.clips.size.toString(),
                                "durationSeconds" to total.toString(),
                                "width" to FfmpegFilmPlan.DEFAULT_WIDTH.toString(),
                                "height" to FfmpegFilmPlan.DEFAULT_HEIGHT.toString(),
                                "bytes" to finalFile.length().toString(),
                                "aiGenerated" to "true",
                                "assembledWith" to "ffmpeg",
                            ),
                            createdAtEpochMillis = context.nowEpochMillis,
                        ),
                    ),
                    progress = 1f,
                    progressMessage = "Assembled ${inputs.clips.size} shots into a $total s film",
                )
            }
        }
    }

    private fun snapshot(run: AssembleRun): TaskExecutorExecution {
        run.result?.let {
            runs.remove(run.externalId)
            return it
        }
        return TaskExecutorExecution(
            status = TaskRunStatus.Running,
            externalRunId = run.externalId,
            progress = if (run.totalMillis <= 0) 0f else (run.doneMillis.toFloat() / run.totalMillis).coerceIn(0f, 0.99f),
            progressMessage = "Assembling the film with ffmpeg",
        )
    }

    /** The storyboard, the review and the frames, checked; throws [AssembleFailure] saying what to change. */
    private suspend fun gather(context: TaskExecutorContext): Inputs {
        val released = context.definition.releasedDependencies(context.task).map { it.value }.toSet()
        fun artifacts(taskId: String): List<ArtifactRef> = if (taskId !in released) {
            emptyList()
        } else {
            context.run.taskRuns[TaskDefinitionId(taskId)]?.artifacts.orEmpty()
        }

        val parses = artifacts(FILM_TASK_STORYBOARD).mapNotNull { it.textContent?.takeIf(String::isNotBlank) }.map(::parseStoryboard)
        if (parses.isEmpty()) throw AssembleFailure("The assemble step has no storyboard to take the shot order and durations from.")
        val storyboard: FilmStoryboard = parses.firstNotNullOfOrNull { parse -> parse.storyboard.takeIf { parse.ok } }
            ?: throw AssembleFailure("The storyboard can't be used: ${parses.first().errorMessage()}")

        val frames = artifacts(FILM_TASK_FRAMES).filter { it.kind == ArtifactKind.Media && it.metadata["shotId"] != null }
        val filmId = frames.firstNotNullOfOrNull { it.metadata["filmId"] }

        // The gate: shots the review sent back must be drawn again before anything is assembled.
        val reviews = artifacts(FILM_TASK_FRAME_REVIEW)
        val ids = storyboard.shots.map { it.id }.toSet()
        val sentBack = reviews.mapNotNull { it.textContent }
            .flatMap { FilmFrameReview.parse(it).regenerate }
            .filter { it in ids }
            .distinct()
        if (sentBack.isNotEmpty()) {
            if (filmId != null) runCatching { store.markForRegeneration(filmId, sentBack.toSet()) }
            throw AssembleFailure(
                "The frame review sent back ${sentBack.size} shot${if (sentBack.size == 1) "" else "s"}: ${sentBack.joinToString(", ")}. " +
                    "Run the frames step again (Run again from a task, at the frames step): only those are drawn and the rest are kept. " +
                    "Then this step assembles the film.",
            )
        }
        ArtifactVerdicts.blocking(reviews)?.let { verdict ->
            throw AssembleFailure(
                "The frame review turned the film back (${verdict.name.lowercase()}) without naming shots. Read its notes. To draw " +
                    "particular shots again, run the frames step again with a line 'regenerate-shots: s01, s02' in its objective.",
            )
        }

        if (filmId == null) throw AssembleFailure("The frames step left no frames. Run it again.")
        val byShot = frames.associateBy { it.metadata.getValue("shotId") }
        val clips = storyboard.shots.map { shot ->
            val frame = byShot[shot.id]
                ?: throw AssembleFailure("Shot ${shot.id} has no frame (the frames step drew ${frames.size} of ${storyboard.shots.size}). Run the frames step again.")
            val uri = frame.uri ?: throw AssembleFailure("The frame of shot ${shot.id} has no file.")
            val file = try {
                store.verifiedFrame(frame.metadata["filmId"] ?: filmId, shot.id, uri)
            } catch (failure: FilmPathException) {
                throw AssembleFailure(failure.message ?: "A frame is not usable.")
            }
            FilmClip(file.absolutePath, shot.durationSeconds)
        }
        return Inputs(filmId, clips)
    }

    private fun failed(message: String) = TaskExecutorExecution(status = TaskRunStatus.Failed, progressMessage = message)

    private fun clean(text: String): String =
        ControlRedaction.scrub(text.trim().replace(Regex("\\s+"), " ")).take(MAX_MESSAGE_CHARS)

    private class AssembleFailure(message: String) : Exception(message)

    private class Inputs(val filmId: String, val clips: List<FilmClip>)

    private class AssembleRun(val externalId: String, val totalMillis: Long) {
        @Volatile var job: Job? = null
        @Volatile var doneMillis: Long = 0
        @Volatile var result: TaskExecutorExecution? = null
    }

    companion object {
        private const val MAX_MESSAGE_CHARS = 600

        /** Assembled films: `~/.aive/film/output/<run>/film.mp4`, apart from the frame store. */
        fun defaultOutputRoot(): File = File(System.getProperty("user.home").orEmpty(), ".aive/film/output")

        private val ENVIRONMENT_KEYS = setOf("PATH", "SystemRoot", "SYSTEMROOT", "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL")

        /** ffmpeg gets only these plain variables: nothing else in the app's environment reaches it. */
        fun ffmpegEnvironment(environment: Map<String, String>): Map<String, String> =
            environment.filterKeys { it in ENVIRONMENT_KEYS }

        /** A folder name from a run id: letters, digits, `-` and `_`. */
        fun safeName(id: String): String =
            id.map { if (it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_') it else '_' }.joinToString("").take(64).ifBlank { "run" }

        /** Milliseconds of film done, from an ffmpeg `-progress` line (`out_time_us=` is microseconds; `out_time_ms=` is too, despite its name). */
        fun progressMillis(line: String): Long? {
            val value = when {
                line.startsWith("out_time_us=") -> line.removePrefix("out_time_us=")
                line.startsWith("out_time_ms=") -> line.removePrefix("out_time_ms=")
                else -> return null
            }
            return value.trim().toLongOrNull()?.takeIf { it >= 0 }?.div(1000)
        }
    }
}
