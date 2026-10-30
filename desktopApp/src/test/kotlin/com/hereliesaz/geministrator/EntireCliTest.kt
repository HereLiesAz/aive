package com.hereliesaz.geministrator

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class EntireCliTest {
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun aLoudErrorStreamIsDrainedEvenWhenTheCommonPoolIsBusy() = withScript(
        // Far more than a pipe buffer holds, on stderr, before the command can finish.
        "head -c 400000 /dev/zero | tr '\\000' x >&2\necho done\n",
    ) { cli ->
        val pool = ForkJoinPool.commonPool()
        val release = CountDownLatch(1)
        val blockers = (0 until pool.parallelism * 2).map {
            pool.submit { release.await(60, TimeUnit.SECONDS) }
        }
        try {
            val started = System.nanoTime()
            val result = runBlocking { cli.run(emptyList(), timeoutSeconds = 20) }
            val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)

            assertEquals(0, result.exitCode, result.error.take(200))
            assertEquals("done", result.output.trim())
            assertEquals(400_000, result.error.length)
            assertTrue(seconds < 15, "entire took ${seconds}s")
        } finally {
            release.countDown()
            blockers.forEach { it.get(10, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun cancellingTheCallerStopsTheCommandPromptly() = withScript("sleep 120\n") { cli ->
        runBlocking {
            val call = launch(Dispatchers.Default) { cli.run(emptyList(), timeoutSeconds = 60) }
            delay(500)
            withTimeout(10_000) { call.cancelAndJoin() }
        }
    }

    @Test
    fun theLauncherIsReplacedAtomicallyOnlyWhenItChanges() {
        val dir = Files.createTempDirectory("entire-launcher-test-").toFile()
        try {
            val launcher = writeEntireAgentLauncher(dir, listOf("/opt/aive/bin/aive", "entire-agent"), windows)
            val first = launcher.readText()
            assertTrue(first.contains("'/opt/aive/bin/aive'") || first.contains("\"/opt/aive/bin/aive\""))
            if (!windows) assertTrue(launcher.canExecute())

            val stamp = 1_000_000_000_000L
            assertTrue(launcher.setLastModified(stamp))
            writeEntireAgentLauncher(dir, listOf("/opt/aive/bin/aive", "entire-agent"), windows)
            assertEquals(stamp, launcher.lastModified(), "an unchanged launcher was rewritten")

            writeEntireAgentLauncher(dir, listOf("/opt/aive-2/bin/aive", "entire-agent"), windows)
            assertTrue(launcher.readText().contains("aive-2"))
            if (!windows) assertTrue(launcher.canExecute())
            assertEquals(listOf(launcher.name), dir.list()!!.toList(), "a staged launcher was left behind")
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun withScript(body: String, block: (EntireCli) -> Unit) {
        if (windows) return
        val dir = Files.createTempDirectory("entire-cli-test-").toFile()
        try {
            val script = File(dir, "entire").apply {
                writeText("#!/bin/sh\n$body")
                setExecutable(true, true)
            }
            block(EntireCli(script))
        } finally {
            dir.deleteRecursively()
        }
    }
}
