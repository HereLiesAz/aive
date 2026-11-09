package com.hereliesaz.geministrator

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The real runner, against small shell scripts standing in for a CLI. Skipped where there is no `sh`. */
class CliProcessTest {
    private fun withScript(body: String, block: (File, File) -> Unit) {
        if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
        val dir = createTempDirectory("aive-cli-proc-").toFile()
        try {
            val script = File(dir, "fake-cli").apply {
                writeText("#!/bin/sh\n$body\n")
                setExecutable(true)
            }
            block(dir, script)
        } finally {
            dir.deleteRecursively()
        }
    }

    private suspend fun run(script: File, dir: File, env: Map<String, String> = mapOf("PATH" to "/usr/bin:/bin"), stdin: String? = null, timeout: Long = 20_000, lines: MutableList<String> = mutableListOf()) =
        ProcessCliRunner.run(listOf(script.absolutePath), dir, env, stdin, timeout) { lines += it }

    @Test
    fun itPipesThePromptInReadsStdoutByLineAndKeepsTheTailOfStderrAndTheExitCode() = withScript(
        "cat\necho \"after\"\necho \"to-stderr\" >&2\nexit 3",
    ) { dir, script ->
        val lines = mutableListOf<String>()
        val result = runBlocking { run(script, dir, stdin = "first\nsecond\n", lines = lines) }
        assertEquals(listOf("first", "second", "after"), lines)
        assertEquals(3, result.exitCode)
        assertTrue("to-stderr" in result.stderrTail)
        assertEquals(false, result.timedOut)
    }

    @Test
    fun theEnvironmentIsExactlyWhatWasGivenNothingInherited() = withScript(
        "echo \"FOO=\${FOO-unset}\"\necho \"HOME=\${HOME-unset}\"\necho \"SECRET=\${AIVE_TEST_SECRET-unset}\"",
    ) { dir, script ->
        val lines = mutableListOf<String>()
        runBlocking { run(script, dir, env = mapOf("PATH" to "/usr/bin:/bin", "FOO" to "bar"), lines = lines) }
        assertEquals(listOf("FOO=bar", "HOME=unset", "SECRET=unset"), lines)
    }

    @Test
    fun aRunPastItsTimeoutIsEndedWithItsWholeProcessTreeAndSaidSo() = withScript("sleep 60 &\nwait") { dir, script ->
        val started = System.currentTimeMillis()
        val result = runBlocking { run(script, dir, timeout = 600) }
        assertTrue(result.timedOut)
        assertTrue(System.currentTimeMillis() - started < 15_000, "it did not wait out the sleep")
    }

    @Test
    fun cancellingTheCallerEndsTheProcessPromptly() = withScript("echo started\nsleep 120") { dir, script ->
        runBlocking {
            val up = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) { ProcessCliRunner.run(listOf(script.absolutePath), dir, mapOf("PATH" to "/usr/bin:/bin"), null, 120_000) { up.complete(Unit) } }
            withTimeout(20_000) { up.await() }
            val started = System.currentTimeMillis()
            withTimeout(15_000) { job.cancelAndJoin() }
            assertTrue(System.currentTimeMillis() - started < 10_000)
        }
    }

    @Test
    fun aProgramThatCannotStartIsAFailureNotAnException() = withScript("exit 0") { dir, _ ->
        val result = runBlocking { ProcessCliRunner.run(listOf(File(dir, "missing").absolutePath), dir, emptyMap(), null, 5_000) {} }
        assertTrue(result.startFailure != null && result.exitCode == -1)
    }

    @Test
    fun aVeryLongLineIsCutAndTheRestOfTheOutputIsStillRead() = withScript(
        "head -c 200000 /dev/zero | tr '\\\\0' 'x'\necho\necho tail",
    ) { dir, script ->
        val lines = mutableListOf<String>()
        runBlocking { run(script, dir, lines = lines) }
        assertEquals(ProcessCliRunner.MAX_LINE_CHARS, lines.first().length)
        assertEquals("tail", lines.last())
    }
}
