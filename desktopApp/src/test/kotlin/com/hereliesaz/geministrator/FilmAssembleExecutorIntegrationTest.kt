package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.workflow.FilmKey
import com.hereliesaz.geministrator.workflow.ShortFilmHive
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.parseStoryboard
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class FilmAssembleExecutorIntegrationTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val ffmpeg = File("/opt/test/bin/ffmpeg")

    /** Plays ffmpeg: records how it was called, says what it was told to say, and writes the output file like ffmpeg does. */
    private class Scripted(
        val exit: Int = 0,
        val stderr: String = "",
        val timedOut: Boolean = false,
        val startFailure: String? = null,
        val lines: List<String> = emptyList(),
    ) : CliProcessRunner {
        val commands = mutableListOf<List<String>>()
        val environments = mutableListOf<Map<String, String>>()
        val timeouts = mutableListOf<Long>()
        val directories = mutableListOf<File>()

        override suspend fun run(
            command: List<String>, directory: File, environment: Map<String, String>, stdin: String?, timeoutMillis: Long, onLine: suspend (String) -> Unit,
        ): CliProcessResult {
            commands += command
            environments += environment
            timeouts += timeoutMillis
            directories += directory
            lines.forEach { onLine(it) }
            if (startFailure == null) File(command.last()).writeText(if (exit == 0 && !timedOut) "mp4 bytes" else "half a film")
            return CliProcessResult(exit, timedOut = timedOut, startFailure = startFailure, stderrTail = stderr)
        }
    }

    private class Fixture(val store: FileFilmFrameStore, val root: File, val context: TaskExecutorContext, val filmId: String) {
        val output = File(root, "out")
    }

    private fun storyboard(count: Int) =
        (1..count).joinToString(",", "{\"shots\":[", "]}") { """{"id":"s$it","prompt":"Prompt $it","durationSeconds":${it + 2}}""" }

    private fun fixture(
        root: File,
        shots: Int = 3,
        review: String? = """{"regenerate-shots":[]}""",
        reviewKind: ArtifactKind = ArtifactKind.Review,
        framesFor: Set<Int> = (1..shots).toSet(),
        uriFor: (FileFilmFrameStore, String, String) -> String = { store, film, shot -> store.uriFor(film, shot) },
        taskRunId: String = "tr-assemble",
        externalRunId: String? = null,
    ): Fixture {
        val store = FileFilmFrameStore(File(root, "frames"))
        val text = storyboard(shots)
        val filmId = FilmKey.of(assertNotNull(parseStoryboard(text).storyboard), "style")
        val definition = ShortFilmHive.definition()
        val task = definition.tasks.first { it.id.value == "assemble" }

        fun done(id: String, vararg artifacts: ArtifactRef) = TaskRun(
            TaskRunId("tr-$id"), TaskDefinitionId(id), TaskRunStatus.Completed, assignedRoleId = null, artifacts = artifacts.toList(),
        )
        fun text(id: String, kind: ArtifactKind, body: String) =
            ArtifactRef(ArtifactId("a-$id"), kind, TaskRunId("tr-$id"), id, textContent = body, createdAtEpochMillis = 0)

        val frames = framesFor.sorted().map { n ->
            runBlocking { store.save(filmId, "s$n", png) }
            ArtifactRef(
                ArtifactId("frame-s$n"), ArtifactKind.Media, TaskRunId("tr-frames"), "Shot s$n",
                uri = uriFor(store, filmId, "s$n"), textContent = "Prompt $n", mediaType = "image/png",
                metadata = mapOf("filmId" to filmId, "shotId" to "s$n"), createdAtEpochMillis = 0,
            )
        }
        val assemble = TaskRun(
            TaskRunId(taskRunId), task.id, if (externalRunId == null) TaskRunStatus.Ready else TaskRunStatus.Running,
            assignedRoleId = null, externalRunId = externalRunId,
        )
        val taskRuns = buildMap {
            put(TaskDefinitionId("storyboard"), done("storyboard", text("storyboard", ArtifactKind.Specification, text)))
            put(TaskDefinitionId("frames"), done("frames", *frames.toTypedArray()))
            if (review != null) put(TaskDefinitionId("frame-review"), done("frame-review", text("frame-review", reviewKind, review)))
            put(task.id, assemble)
        }
        val run = WorkflowRun(
            WorkflowRunId("run/1 ../x"), ProjectId("p"), definition.id, "film", WorkflowRunStatus.Running, taskRuns, 0, 0,
        )
        val context = TaskExecutorContext(
            Project(ProjectId("p"), "P", createdAtEpochMillis = 0, updatedAtEpochMillis = 0), definition, run, task, assemble,
            assertNotNull(task.executor), 5_000,
        )
        return Fixture(store, root, context, filmId)
    }

    private fun integration(fixture: Fixture, runner: CliProcessRunner, locate: () -> File? = { ffmpeg }, environment: Map<String, String> = mapOf("PATH" to "/usr/bin")) =
        FilmAssembleExecutorIntegration(
            store = fixture.store,
            runner = runner,
            locate = locate,
            outputRoot = fixture.output,
            environment = { environment },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

    private fun withRoot(test: suspend (File) -> Unit): Unit = runBlocking {
        val root = Files.createTempDirectory("aive-film-assemble-").toFile()
        try {
            test(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun itAssemblesTheFramesIntoAnMp4MediaArtifact(): Unit = withRoot { root ->
        val f = fixture(root)
        val runner = Scripted(lines = listOf("out_time_us=1000000", "progress=end"))
        val result = integration(f, runner).dispatch(f.context)

        assertEquals(TaskRunStatus.Completed, result.status, result.progressMessage)
        val video = result.artifacts.single()
        assertEquals(ArtifactKind.Media, video.kind)
        assertEquals("video/mp4", video.mediaType)
        assertEquals("true", video.metadata["aiGenerated"])
        assertEquals("12", video.metadata["durationSeconds"], "3 + 4 + 5")
        assertEquals("3", video.metadata["shots"])
        val file = File(java.net.URI(assertNotNull(video.uri)))
        assertTrue(file.isFile && file.name == "film.mp4" && file.readText() == "mp4 bytes")
        assertTrue(file.canonicalPath.startsWith(File(root, "out").canonicalPath), "in the per-run output folder")
        assertFalse(File(file.parentFile, "film.partial.mp4").exists(), "the half-written name is gone")
        assertEquals("run_1____x", file.parentFile.name, "the run id is a safe folder name")
        assertEquals(1f, result.progress)
    }

    @Test
    fun theCommandIsAnArgumentListToFfmpegWithTheStoreFramesAndNeverAShell(): Unit = withRoot { root ->
        val f = fixture(root)
        val runner = Scripted()
        integration(f, runner, environment = mapOf("PATH" to "/usr/bin", "OPENAI_API_KEY" to "do-not-pass", "HOME" to "/home/x", "SECRET_THING" to "x", "TMPDIR" to "/tmp")).dispatch(f.context)

        val command = runner.commands.single()
        assertEquals(ffmpeg.absolutePath, command.first())
        val inputs = command.indices.filter { command[it] == "-i" }.map { command[it + 1] }
        assertEquals((1..3).map { f.store.verifiedFrame(f.filmId, "s$it", f.store.uriFor(f.filmId, "s$it")).absolutePath }, inputs)
        assertTrue(command.none { it == "sh" || it == "bash" || it == "-c" || it == "cmd" })
        assertEquals(listOf("3.500", "4.500", "5.000"), command.indices.filter { command[it] == "-t" }.map { command[it + 1] }, "each shot plus its crossfade, the last alone")
        assertEquals(setOf("PATH", "TMPDIR"), runner.environments.single().keys, "no other variable, and so no connection token, reaches ffmpeg")
        assertEquals(15L * 60 * 1000, runner.timeouts.single(), "fifteen minutes")
        assertTrue(runner.directories.single().path.contains("out"))
    }

    @Test
    fun withoutFfmpegItFailsAtOnceAndSaysHowToInstallIt(): Unit = withRoot { root ->
        val f = fixture(root)
        val runner = Scripted()
        val result = integration(f, runner, locate = { null }).dispatch(f.context)

        assertEquals(TaskRunStatus.Failed, result.status)
        val message = assertNotNull(result.progressMessage)
        assertTrue("not installed" in message && "brew install ffmpeg" in message && "winget" in message && "apt install ffmpeg" in message, message)
        assertTrue(runner.commands.isEmpty(), "and nothing was started")
        assertFalse(File(f.output, "run_1____x/film.mp4").exists())
    }

    @Test
    fun whileTheReviewHasShotsSentBackItRefusesNamesThemAndRemembersThem(): Unit = withRoot { root ->
        val f = fixture(root, review = """The frames are mostly fine. {"regenerate-shots":["s2","s9","../x"],"notes":"s2 has a watermark"}""")
        val runner = Scripted()
        val result = integration(f, runner).dispatch(f.context)

        assertEquals(TaskRunStatus.Failed, result.status)
        val message = assertNotNull(result.progressMessage)
        assertTrue("sent back 1 shot: s2" in message && "frames step again" in message && "only those are drawn" in message, message)
        assertTrue("s9" !in message, "an id the storyboard does not have is ignored")
        assertTrue(runner.commands.isEmpty())
        assertEquals(setOf("s2"), f.store.pendingRegeneration(f.filmId), "so the frames step draws only that one")
    }

    @Test
    fun aReviewThatRejectsWithoutNamingShotsAlsoStopsIt(): Unit = withRoot { root ->
        val f = fixture(root, review = "VERDICT: reject\nThe style drifts in every frame.", reviewKind = ArtifactKind.Verification)
        val runner = Scripted()
        val result = integration(f, runner).dispatch(f.context)
        assertEquals(TaskRunStatus.Failed, result.status)
        assertTrue("turned the film back (reject)" in assertNotNull(result.progressMessage))
        assertTrue(runner.commands.isEmpty())
        assertEquals(emptySet(), f.store.pendingRegeneration(f.filmId))

        val approved = fixture(File(root, "again").also { it.mkdirs() }, review = "VERDICT: approve\nAll good.", reviewKind = ArtifactKind.Verification)
        assertEquals(TaskRunStatus.Completed, integration(approved, Scripted()).dispatch(approved.context).status)
    }

    @Test
    fun aFrameThatIsMissingOrOutsideTheStoreIsRefusedBeforeFfmpegRuns(): Unit = withRoot { root ->
        val missing = fixture(root, framesFor = setOf(1, 3))
        val runner = Scripted()
        val noFrame = integration(missing, runner).dispatch(missing.context)
        assertEquals(TaskRunStatus.Failed, noFrame.status)
        assertTrue("Shot s2 has no frame" in assertNotNull(noFrame.progressMessage))

        val elsewhere = File(root, "elsewhere/s2.png").apply { parentFile.mkdirs(); writeBytes(png) }
        listOf(
            elsewhere.toURI().toString(),
            "file:///etc/passwd",
            "http://example.com/s2.png",
            File(root, "frames/${missing.filmId}/../${missing.filmId}/s2.png").toURI().toString(),
        ).forEachIndexed { index, bad ->
            val tampered = fixture(
                File(root, "t$index").also { it.mkdirs() },
                uriFor = { store, film, shot -> if (shot == "s2") bad else store.uriFor(film, shot) },
            )
            val tamperedRunner = Scripted()
            val result = integration(tampered, tamperedRunner).dispatch(tampered.context)
            assertEquals(TaskRunStatus.Failed, result.status, bad)
            assertTrue("shot s2" in assertNotNull(result.progressMessage), result.progressMessage)
            assertTrue(tamperedRunner.commands.isEmpty(), "ffmpeg is never given a path from outside: $bad")
        }
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun aFailingFfmpegGivesItsReasonAndLeavesNoHalfFilm(): Unit = withRoot { root ->
        val f = fixture(root)
        val failed = integration(f, Scripted(exit = 1, stderr = "Unknown encoder 'libx264'\n  at line\n")).dispatch(f.context)
        assertEquals(TaskRunStatus.Failed, failed.status)
        val message = assertNotNull(failed.progressMessage)
        assertTrue("exit code 1" in message && "Unknown encoder 'libx264' at line" in message, message)
        assertEquals(emptyList(), File(f.output, "run_1____x").listFiles()!!.toList(), "no film.partial.mp4 left behind")

        val slowFixture = fixture(File(root, "slow").also { it.mkdirs() })
        val timedOut = integration(slowFixture, Scripted(timedOut = true)).dispatch(slowFixture.context)
        assertEquals(TaskRunStatus.Failed, timedOut.status)
        assertTrue("15 minutes" in assertNotNull(timedOut.progressMessage) && "stopped" in timedOut.progressMessage.orEmpty())

        val cannotStart = fixture(File(root, "nostart").also { it.mkdirs() })
        val start = integration(cannotStart, Scripted(startFailure = "Cannot run program")).dispatch(cannotStart.context)
        assertEquals(TaskRunStatus.Failed, start.status)
        assertTrue("could not be started" in assertNotNull(start.progressMessage) && "ffmpeg is not installed" in start.progressMessage.orEmpty())
    }

    @Test
    fun aBadStoryboardOrNoFramesFailsWithAMessage(): Unit = withRoot { root ->
        val f = fixture(root)
        val broken = f.context.copy(
            run = f.context.run.copy(
                taskRuns = f.context.run.taskRuns + (TaskDefinitionId("storyboard") to f.context.run.taskRuns.getValue(TaskDefinitionId("storyboard")).copy(
                    artifacts = listOf(ArtifactRef(ArtifactId("x"), ArtifactKind.Specification, TaskRunId("tr-storyboard"), "sb", textContent = "nope", createdAtEpochMillis = 0)),
                )),
            ),
        )
        val result = integration(f, Scripted()).dispatch(broken)
        assertEquals(TaskRunStatus.Failed, result.status)
        assertTrue("storyboard can't be used" in assertNotNull(result.progressMessage))

        val none = fixture(File(root, "none").also { it.mkdirs() }, framesFor = emptySet())
        val noFrames = integration(none, Scripted()).dispatch(none.context)
        assertEquals(TaskRunStatus.Failed, noFrames.status)
        assertTrue("no frames" in assertNotNull(noFrames.progressMessage))
    }

    @Test
    fun progressComesFromFfmpegsProgressLinesAndARestartAssemblesAgain(): Unit = withRoot { root ->
        assertEquals(1000L, FilmAssembleExecutorIntegration.progressMillis("out_time_us=1000000"))
        assertEquals(2500L, FilmAssembleExecutorIntegration.progressMillis("out_time_ms=2500000"))
        assertEquals(null, FilmAssembleExecutorIntegration.progressMillis("out_time_us=N/A"))
        assertEquals(null, FilmAssembleExecutorIntegration.progressMillis("frame=12"))
        assertEquals(null, FilmAssembleExecutorIntegration.progressMillis("out_time_us=-5"))

        // After a restart the step is Running with an id this integration does not know: it assembles again.
        val f = fixture(root, externalRunId = "film-assemble:tr-assemble")
        val runner = Scripted()
        val result = integration(f, runner).reconcile(f.context)
        assertEquals(TaskRunStatus.Completed, result.status, result.progressMessage)
        assertEquals(1, runner.commands.size)
    }

    @Test
    fun itHandlesOnlyTheFilmAssembleService(): Unit = withRoot { root ->
        val f = fixture(root)
        val film = integration(f, Scripted())
        assertTrue(film.supports(TaskExecutor.ExternalService("film-assemble", "assemble")))
        assertFalse(film.supports(TaskExecutor.ExternalService("film-frames", "frames")))
        assertFalse(film.supports(TaskExecutor.TestRunner()))
        assertFalse(film.retryDispatchFailures)
        assertEquals("film-assemble", film.orchestrationToolId)
        assertEquals(setOf("PATH"), FilmAssembleExecutorIntegration.ffmpegEnvironment(mapOf("PATH" to "x", "OPENAI_API_KEY" to "y")).keys)
        assertEquals("run", FilmAssembleExecutorIntegration.safeName(""))
        assertEquals("a_b_c_", FilmAssembleExecutorIntegration.safeName("a/b\\c\n"))
    }
}
