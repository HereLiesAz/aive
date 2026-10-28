package com.hereliesaz.geministrator

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * An Aive run recorded as an Entire checkpoint, against the real `entire` CLI. Skipped where
 * Entire isn't installed.
 */
class EntireEndToEndTest {
    @Test
    fun aRunCommittedInAWorktreeGainsAnEntireCheckpoint() = runBlocking {
        val cli = EntireCli.locate() ?: return@runBlocking
        val root = Files.createTempDirectory("aive-entire-repo").toFile()
        val sessions = AiveEntireSessions(Files.createTempDirectory("aive-entire-sessions").toFile())
        fun git(dir: File, vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", *args))
                .directory(dir).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "git ${args.toList()}: $out" }
            return out
        }
        git(root, "init", "-q", "-b", "main")
        File(root, "a.txt").writeText("one\n")
        git(root, "add", ".")
        git(root, "commit", "-qm", "init")
        val enable = cli.run(listOf("enable", "--agent", "claude-code", "--skip-push-sessions", "--telemetry=false"), root)
        assertTrue(enable.ok, enable.message)
        git(root, "add", "-A")
        runCatching { git(root, "commit", "-qm", "entire", "--no-verify") }

        val workspace = Files.createTempDirectory("aive-entire-worktree").toFile().also { it.delete() }
        git(root, "worktree", "add", "-q", "-b", "haive/test", workspace.absolutePath)
        val id = "aive-test-${System.nanoTime()}"
        sessions.begin(id, root.absolutePath, "test")
        sessions.prompt(id, "Make a.txt say two")
        File(workspace, "a.txt").writeText("two\n")
        git(workspace, "add", "-A")
        git(workspace, "-c", "core.hooksPath=/dev/null", "commit", "-qm", "Haive: two")
        sessions.response(id, "diff --git a/a.txt b/a.txt")
        sessions.files(id, listOf("a.txt"), emptyList(), emptyList())
        sessions.usage(id, 10, 5)

        val recorder = EntireRecorder(locate = { cli }, sessions = sessions)
        val result = recorder.attach(cli, root, workspace, id)
        assertTrue(result?.ok == true, result?.message)
        val message = git(workspace, "log", "-1", "--format=%B")
        assertTrue("Entire-Checkpoint:" in message, message)

        // And the next drifter reads it back.
        val checkpoint = entireCheckpoints(cli, workspace, 5).single()
        assertEquals("The Aive", checkpoint.agent)
        assertEquals("Make a.txt say two", checkpoint.intent)
    }
}
