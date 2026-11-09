package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.releasedDependencies
import com.hereliesaz.geministrator.workflow.FILM_FRAME_PARALLELISM
import com.hereliesaz.geministrator.workflow.FILM_FRAMES_SERVICE
import com.hereliesaz.geministrator.workflow.FILM_IMAGE_BUDGET_CEILING
import com.hereliesaz.geministrator.workflow.FILM_IMAGE_BUDGET_DEFAULT
import com.hereliesaz.geministrator.workflow.FILM_IMAGE_BUDGET_ENV
import com.hereliesaz.geministrator.workflow.FILM_SHOT_MAX_RETRIES
import com.hereliesaz.geministrator.workflow.FILM_TASK_STORYBOARD
import com.hereliesaz.geministrator.workflow.FILM_TASK_STYLE_SHEET
import com.hereliesaz.geministrator.workflow.FilmFramePrompts
import com.hereliesaz.geministrator.workflow.FilmFrameReview
import com.hereliesaz.geministrator.workflow.FilmFrameStore
import com.hereliesaz.geministrator.workflow.FilmKey
import com.hereliesaz.geministrator.workflow.FilmShot
import com.hereliesaz.geministrator.workflow.FilmStoryboard
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.TaskExecutorExecution
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegration
import com.hereliesaz.geministrator.workflow.cleanFilmText
import com.hereliesaz.geministrator.workflow.cleanStyleSheet
import com.hereliesaz.geministrator.workflow.parseStoryboard
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** The size of a frame asked of the image model: landscape 3:2. */
const val FILM_FRAME_SIZE: String = "1536x1024"

/** The most bytes one frame may be; a larger reply is not a frame. */
const val FILM_MAX_FRAME_BYTES: Int = 25_000_000

/** One picture from an image model, as PNG bytes. The seam that lets tests draw without an image API. */
fun interface FilmImageApi {
    suspend fun generate(prompt: String): ByteArray
}

/**
 * The film's pictures from the OpenAI Responses `image_generation` tool, through the client the
 * node-character pipeline already uses ([OpenAiNodeCharacterImageApi]): landscape, opaque background.
 */
class OpenAiFilmImageApi(
    private val apiKeyProvider: LlmApiKeyProvider,
    private val api: OpenAiNodeCharacterImageApi = OpenAiNodeCharacterImageApi(apiKeyProvider),
) : FilmImageApi {
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun generate(prompt: String): ByteArray {
        require(apiKeyProvider.getApiKey().isNotBlank()) {
            "No OpenAI connection. Connect OpenAI in the Keeper's room, Connections, then run the frames step again."
        }
        val image = api.generate(
            prompt = prompt,
            inputImages = emptyList(),
            action = "generate",
            size = FILM_FRAME_SIZE,
            background = "opaque",
        )
        return Base64.decode(image.base64Png)
    }
}

/**
 * The short-film hive's frames step (docs/Short-Film.md): reads the storyboard and style sheet from its
 * dependencies and draws one picture per shot.
 *
 * - **Bounded spend.** At most [imageBudget] images per workflow run, every attempt counted, and the count is kept in the
 *   [FilmFrameStore] before each picture is asked for, so a restart does not give it back. A storyboard that needs more than
 *   is left fails before anything is drawn, saying how to raise the budget.
 * - **Bounded work.** [parallelism] shots at a time; a shot is tried at most `1 + maxRetries` times.
 * - **Kept frames.** Frames are filed by [FilmKey] in the [FilmFrameStore]. A run again finds what was drawn and draws
 *   only the missing shots and the shots the frame review, or a `regenerate-shots` line in this step's
 *   objective, sent back (a `regenerate-shots` line is acted on once per run); the rest are kept and cost nothing.
 * - **Progress.** The step starts the drawing and reports Running, with a count of frames drawn, until it finishes.
 *   If the app restarts meanwhile, the step is started again and finds the frames already kept.
 *
 * Storyboard text is untrusted: it is parsed ([parseStoryboard]), its ids are checked before they name a
 * file, and a shot's prompt goes only into the image prompt, as described scene.
 */
class FilmFramesExecutorIntegration(
    private val api: FilmImageApi,
    private val store: FilmFrameStore,
    private val imageBudget: Int = FILM_IMAGE_BUDGET_DEFAULT,
    private val parallelism: Int = FILM_FRAME_PARALLELISM,
    private val maxRetries: Int = FILM_SHOT_MAX_RETRIES,
    private val retryDelayMillis: Long = RETRY_DELAY_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : TaskExecutorIntegration {
    init {
        require(imageBudget in 1..FILM_IMAGE_BUDGET_CEILING) { "The image budget must be 1 to $FILM_IMAGE_BUDGET_CEILING" }
        require(parallelism >= 1) { "parallelism must be at least one" }
        require(maxRetries >= 0) { "maxRetries must not be negative" }
    }

    override val orchestrationToolId: String = FILM_FRAMES_SERVICE

    /** A retry is a second charge for the same pictures, so the engine's retry policy must not repeat this step. */
    override val retryDispatchFailures: Boolean = false

    override fun supports(executor: TaskExecutor): Boolean =
        executor is TaskExecutor.ExternalService && executor.service == FILM_FRAMES_SERVICE

    private val lock = Mutex()
    private val runs = mutableMapOf<String, FrameRun>()

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution = try {
        start(context)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: FilmStepFailure) {
        TaskExecutorExecution(status = TaskRunStatus.Failed, progressMessage = failure.message)
    } catch (failure: Throwable) {
        TaskExecutorExecution(
            status = TaskRunStatus.Failed,
            progressMessage = "Drawing the frames failed: " + describe(failure),
        )
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution {
        val id = context.taskRun.externalRunId
        val known = id?.let { key -> lock.withLock { runs[key] } }
        // Not ours any more (the app restarted): start again; the kept frames make that cheap.
        return if (known == null) dispatch(context) else snapshot(known, context)
    }

    override suspend fun cancel(context: TaskExecutorContext) {
        val id = context.taskRun.externalRunId ?: return
        lock.withLock { runs.remove(id) }?.job?.cancel()
    }

    private suspend fun start(context: TaskExecutorContext): TaskExecutorExecution {
        val plan = plan(context)
        val workflowRunId = context.run.id.value
        val externalId = "$FILM_FRAMES_SERVICE:${context.taskRun.id.value}"
        val run = FrameRun(externalId, plan)
        val needed = plan.toDraw.size
        if (needed > 0) {
            val remaining = imageBudget - store.spentImages(workflowRunId)
            if (needed > remaining) {
                throw FilmStepFailure(
                    "The storyboard needs $needed new images, but this run's image budget is $imageBudget and " +
                        "${imageBudget - remaining} are already used. ${budgetHint()} Frames already drawn are kept.",
                )
            }
        }
        lock.withLock {
            runs.remove(externalId)?.job?.cancel()
            runs[externalId] = run
        }
        if (needed == 0) {
            lock.withLock { run.finished = true }
        } else {
            run.job = scope.launch { drawAll(run, workflowRunId) }
        }
        return snapshot(run, context)
    }

    private suspend fun plan(context: TaskExecutorContext): Plan {
        val released = context.definition.releasedDependencies(context.task).map { it.value }.toSet()
        fun texts(taskId: String): List<String> = if (taskId !in released) {
            emptyList()
        } else {
            context.run.taskRuns[TaskDefinitionId(taskId)]?.artifacts.orEmpty()
                .mapNotNull { it.textContent?.takeIf(String::isNotBlank) }
        }

        val storyboardTexts = texts(FILM_TASK_STORYBOARD)
        if (storyboardTexts.isEmpty()) {
            throw FilmStepFailure(
                "The frames step has no storyboard. The '$FILM_TASK_STORYBOARD' step must finish first with a JSON shot list, " +
                    "and the frames step must depend on it.",
            )
        }
        val parses = storyboardTexts.map(::parseStoryboard)
        val storyboard = parses.firstNotNullOfOrNull { it.storyboard.takeIf { _ -> it.ok } }
            ?: throw FilmStepFailure(
                "The storyboard can't be used: ${parses.first().errorMessage()} " +
                    "Run again from the storyboard step, or edit its output, to correct it. Nothing was drawn.",
            )
        val styleSource = texts(FILM_TASK_STYLE_SHEET).firstOrNull().orEmpty()
        val (style, _) = cleanStyleSheet(styleSource)
        if (style.isBlank()) {
            throw FilmStepFailure(
                "The frames step has no style sheet. The '$FILM_TASK_STYLE_SHEET' step must finish first. Nothing was drawn.",
            )
        }

        val filmId = FilmKey.of(storyboard, style)
        val ids = storyboard.shots.map { it.id }.toSet()
        // A `regenerate-shots` line stays in the hive's text, so it is acted on once per run (it is moved into the store's
        // waiting list, which survives a restart and is emptied as the shots are drawn): reading it again after a restart or a
        // second reconcile must not redraw, and pay for, the same shots.
        val asked = FilmFrameReview.parse(context.task.objective).regenerate.filterTo(linkedSetOf()) { it in ids }
        if (asked.isNotEmpty() && store.consumeOnce("${context.run.id.value}|regenerate|$filmId|${asked.sorted().joinToString(",")}")) {
            store.markForRegeneration(filmId, asked)
        }
        val sentBack = store.pendingRegeneration(filmId).filterTo(linkedSetOf()) { it in ids }
        val existing = mutableMapOf<String, String>()
        val toDraw = mutableListOf<FilmShot>()
        for (shot in storyboard.shots) {
            val kept = if (shot.id in sentBack) null else store.find(filmId, shot.id)
            if (kept == null) toDraw += shot else existing[shot.id] = kept
        }
        return Plan(storyboard, style, filmId, sentBack, existing, toDraw)
    }

    private suspend fun drawAll(run: FrameRun, workflowRunId: String) {
        try {
            val permits = Semaphore(parallelism)
            supervisorScope {
                run.plan.toDraw
                    .map { shot -> async { permits.withPermit { drawOne(run, shot, workflowRunId) } } }
                    .awaitAll()
            }
            val failed = lock.withLock { run.failures.keys.toSet() }
            runCatching { store.clearRegeneration(run.plan.filmId, run.plan.sentBack - failed) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            lock.withLock { run.fatal = describe(failure) }
        }
        lock.withLock { run.finished = true }
    }

    private suspend fun drawOne(run: FrameRun, shot: FilmShot, workflowRunId: String) {
        val attempts = 1 + maxRetries
        var lastError = ""
        for (attempt in 1..attempts) {
            // A cancelled run neither spends budget nor asks the image model again.
            currentCoroutineContext().ensureActive()
            // Kept before the picture is asked for, in the store, so a restart does not hand the budget back.
            val allowed = lock.withLock { store.reserveImage(workflowRunId, imageBudget) }
            if (!allowed) {
                lock.withLock { run.failures[shot.id] = "the image budget ($imageBudget) was used up. ${budgetHint()}" }
                return
            }
            try {
                val png = api.generate(FilmFramePrompts.build(run.plan.style, shot))
                check(isPng(png)) { "the image model returned data that is not a PNG picture" }
                check(png.size <= FILM_MAX_FRAME_BYTES) { "the picture is larger than $FILM_MAX_FRAME_BYTES bytes" }
                val uri = store.save(run.plan.filmId, shot.id, png)
                lock.withLock {
                    run.drawn[shot.id] = uri
                    run.failures.remove(shot.id)
                }
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastError = describe(failure)
                if (attempt < attempts && retryDelayMillis > 0) delay(retryDelayMillis * attempt)
            }
        }
        lock.withLock { run.failures[shot.id] = "failed after $attempts attempts: $lastError" }
    }

    /** What the task shows now: Running with a count, or its final result once the drawing has finished. */
    private suspend fun snapshot(run: FrameRun, context: TaskExecutorContext): TaskExecutorExecution {
        val state = lock.withLock {
            // A finished run is kept, so a second reconcile before the engine has saved its result answers the same and draws nothing.
            val finished = run.finished
            State(finished, run.drawn.size, run.failures.size, run.failures.toMap(), run.drawn.toMap(), run.fatal)
        }
        if (!state.finished) {
            val total = run.plan.toDraw.size
            val done = state.drawn + state.failed
            return TaskExecutorExecution(
                status = TaskRunStatus.Running,
                externalRunId = run.externalId,
                progress = if (total == 0) 0f else (done.toFloat() / total).coerceIn(0f, 0.99f),
                progressMessage = "Drew $done of $total new frames" +
                    (run.plan.existing.size.takeIf { it > 0 }?.let { " ($it kept)" } ?: ""),
            )
        }
        return result(run, context, state)
    }

    private fun result(run: FrameRun, context: TaskExecutorContext, state: State): TaskExecutorExecution {
        val plan = run.plan
        val uris = plan.existing + state.drawnUris
        val missing = plan.storyboard.shots.filter { it.id !in uris }
        val base = context.taskRun.id.value
        val frames = plan.storyboard.shots.mapIndexedNotNull { index, shot ->
            val uri = uris[shot.id] ?: return@mapIndexedNotNull null
            ArtifactRef(
                id = ArtifactId("$base:film:frame:${shot.id}"),
                kind = ArtifactKind.Media,
                taskRunId = context.taskRun.id,
                label = "Shot ${shot.id}",
                uri = uri,
                textContent = shot.prompt,
                mediaType = "image/png",
                metadata = mapOf(
                    "filmId" to plan.filmId,
                    "shotId" to shot.id,
                    "order" to index.toString(),
                    "durationSeconds" to shot.durationSeconds.toString(),
                    "source" to if (shot.id in state.drawnUris) "generated" else "reused",
                    "aiGenerated" to "true",
                ),
                createdAtEpochMillis = context.nowEpochMillis,
            )
        }
        val spentNow = state.drawnUris.size + state.failed
        val summary = ArtifactRef(
            id = ArtifactId("$base:film:frames-summary"),
            kind = ArtifactKind.Specification,
            taskRunId = context.taskRun.id,
            label = "Frames · ${frames.size} of ${plan.storyboard.shots.size}",
            textContent = buildString {
                appendLine("film ${plan.filmId}")
                appendLine("shots ${plan.storyboard.shots.size}, drawn this time ${state.drawnUris.size}, kept ${plan.existing.size}, sent back ${plan.sentBack.size}")
                appendLine("images asked for this time (retries included) at least $spentNow; budget $imageBudget per run")
                state.failures.forEach { (id, reason) -> appendLine("FAILED $id: $reason") }
            },
            mediaType = "text/plain",
            metadata = mapOf("filmId" to plan.filmId, "stage" to "film-frames-summary"),
            createdAtEpochMillis = context.nowEpochMillis,
        )
        if (missing.isEmpty() && state.fatal == null) {
            return TaskExecutorExecution(
                status = TaskRunStatus.Completed,
                artifacts = frames + summary,
                progress = 1f,
                progressMessage = "${frames.size} frames ready (${state.drawnUris.size} drawn, ${plan.existing.size} kept)",
            )
        }
        val why = state.fatal?.let { "Drawing stopped: $it. " }.orEmpty() + missing.take(MAX_FAILURES_LISTED).joinToString("; ") { shot ->
            "${shot.id} (${state.failures[shot.id] ?: "not drawn"})"
        } + if (missing.size > MAX_FAILURES_LISTED) "; and ${missing.size - MAX_FAILURES_LISTED} more" else ""
        return TaskExecutorExecution(
            status = TaskRunStatus.Failed,
            artifacts = frames + summary,
            progressMessage = "${missing.size} of ${plan.storyboard.shots.size} frames could not be drawn: $why. " +
                "The ${frames.size} that were drawn are kept; run this step again to draw only the missing ones.",
        )
    }

    private fun budgetHint(): String =
        "To raise the budget set the environment variable $FILM_IMAGE_BUDGET_ENV (now $imageBudget, at most " +
            "$FILM_IMAGE_BUDGET_CEILING), restart the app and run this step again, or cut shots from the storyboard."

    private fun describe(failure: Throwable): String =
        cleanFilmText(failure.message?.takeIf(String::isNotBlank) ?: failure::class.simpleName.orEmpty())
            .redactSecrets().take(MAX_ERROR_CHARS)

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    private class FilmStepFailure(message: String) : Exception(message)

    private class Plan(
        val storyboard: FilmStoryboard,
        val style: String,
        val filmId: String,
        val sentBack: Set<String>,
        val existing: Map<String, String>,
        val toDraw: List<FilmShot>,
    )

    /** One started drawing. Every mutable field is changed only while [lock] is held. */
    private class FrameRun(val externalId: String, val plan: Plan) {
        var job: Job? = null
        var finished: Boolean = false
        var fatal: String? = null
        val drawn: MutableMap<String, String> = linkedMapOf()
        val failures: MutableMap<String, String> = linkedMapOf()
    }

    private class State(
        val finished: Boolean,
        val drawn: Int,
        val failed: Int,
        val failures: Map<String, String>,
        val drawnUris: Map<String, String>,
        val fatal: String?,
    )

    private companion object {
        const val RETRY_DELAY_MILLIS: Long = 2_000L
        const val MAX_ERROR_CHARS: Int = 300
        const val MAX_FAILURES_LISTED: Int = 5
        val PNG_SIGNATURE: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
