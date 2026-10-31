package com.hereliesaz.geministrator

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume

/**
 * The recorder and checkpoint reading against a fake `entire` (a shell script), so they are tested
 * wherever the real CLI is missing. `EntireEndToEndTest` runs the same path against the real one.
 */
class EntireRecorderTest {
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    /** A fake `entire` answering status, session attach and checkpoint explain; it logs what it saw into [dir]. */
    private fun fakeEntire(dir: File): EntireCli {
        val script = File(dir, "entire")
        script.writeText(
            """
            |#!/bin/sh
            |printf '%s\n' "${'$'}*" >> '${dir.absolutePath}/args.log'
            |case "${'$'}1 ${'$'}2" in
            |  "status --json") printf '%s\n' '{"enabled": true}' ;;
            |  "session attach")
            |    pwd > '${dir.absolutePath}/cwd.seen'
            |    printf '%s\n' "${'$'}PATH" > '${dir.absolutePath}/path.seen'
            |    printf '%s\n' "${'$'}AIVE_ENTIRE_SESSIONS" > '${dir.absolutePath}/sessions.seen'
            |    cat .entire/settings.local.json > '${dir.absolutePath}/settings.seen'
            |    echo "attached" ;;
            |  "checkpoint explain")
            |    if [ "${'$'}3" = "--json" ]; then
            |      printf '%s\n' '[{"checkpoint_id":"abc123def4567890","agent":"The Aive","date":"2026-10-01","message":"Haive: two\nbody"},{"checkpoint_id":"ffff0000","agent":"Claude Code","message":"m"}]'
            |    elif [ "${'$'}3" = "ffff0000" ]; then
            |      printf '# Checkpoint\n\n## Summary\n*Not generated yet*\n'
            |    else
            |      printf '# Checkpoint %s\n\n## Intent\n  Make a.txt\n  say two\n\n## Summary\nChanged a.txt.\n──────\n## Files\na.txt\n' "${'$'}3"
            |    fi ;;
            |  *) echo "unknown ${'$'}*" >&2; exit 2 ;;
            |esac
            |""".trimMargin(),
        )
        script.setExecutable(true, true)
        return EntireCli(script)
    }

    @Test
    fun attachRunsInTheWorktreeWithExternalAgentsAllowedThereOnly() = runBlocking {
        Assume.assumeTrue("needs a POSIX shell", !windows)
        val tmp = Files.createTempDirectory("aive-entire-fake").toFile()
        val root = File(tmp, "repo").apply { mkdirs() }
        File(root, ".entire").mkdirs()
        File(root, ".entire/settings.json").writeText("""{"enabled":true}""")
        File(root, ".entire/settings.local.json").writeText("""{"log_level":"debug"}""")
        val workspace = File(tmp, "worktree").apply { mkdirs() }
        val sessions = AiveEntireSessions(File(tmp, "sessions"))
        val cli = fakeEntire(tmp)
        val home = System.getProperty("user.home")
        System.setProperty("user.home", File(tmp, "home").absolutePath) // the launcher goes to ~/.aive/bin
        val result = try {
            EntireRecorder(locate = { cli }, sessions = sessions).attach(cli, root, workspace, "run-1")
        } finally {
            System.setProperty("user.home", home)
        }
        assertTrue(result?.ok == true, result?.message)
        assertTrue("session attach run-1 --agent aive --force" in File(tmp, "args.log").readText())
        assertEquals(workspace.canonicalPath, File(File(tmp, "cwd.seen").readText().trim()).canonicalPath)
        assertEquals(sessions.directory.absolutePath, File(tmp, "sessions.seen").readText().trim())
        assertTrue(File(tmp, "path.seen").readText().startsWith(File(tmp, "home/.aive/bin").absolutePath))
        assertTrue(File(tmp, "home/.aive/bin/entire-agent-aive").canExecute())
        val local = Json.parseToJsonElement(File(tmp, "settings.seen").readText()).jsonObject
        assertEquals(true, local["external_agents"]!!.jsonPrimitive.boolean)
        assertEquals("debug", local["log_level"]!!.jsonPrimitive.content)
        assertEquals("""{"enabled":true}""", File(workspace, ".entire/settings.json").readText())
        // The keeper's own settings are untouched.
        assertEquals("""{"log_level":"debug"}""", File(root, ".entire/settings.local.json").readText())
    }

    @Test
    fun aSymlinkedSettingsPathInTheWorktreeIsNeverWrittenThrough() = runBlocking {
        Assume.assumeTrue("needs a POSIX shell", !windows)
        val tmp = Files.createTempDirectory("aive-entire-fake").toFile()
        val root = File(tmp, "repo").apply { mkdirs() }
        File(root, ".entire").mkdirs()
        val outside = File(tmp, "keeper-settings.json").apply { writeText("precious") }
        val outsideDir = File(tmp, "elsewhere").apply { mkdirs() }
        val cli = fakeEntire(tmp)
        val home = System.getProperty("user.home")
        System.setProperty("user.home", File(tmp, "home").absolutePath)
        try {
            // The repository tracks the settings file, then the whole directory, as a link out.
            val fileLinked = File(tmp, "worktree-file").apply { mkdirs() }
            File(fileLinked, ".entire").mkdirs()
            Files.createSymbolicLink(File(fileLinked, ".entire/settings.local.json").toPath(), outside.toPath())
            val dirLinked = File(tmp, "worktree-dir").apply { mkdirs() }
            Files.createSymbolicLink(File(dirLinked, ".entire").toPath(), outsideDir.toPath())

            listOf(fileLinked, dirLinked).forEach { workspace ->
                val result = EntireRecorder(locate = { cli }, sessions = AiveEntireSessions(File(tmp, "sessions")))
                    .attach(cli, root, workspace, "run-${workspace.name}")
                assertTrue(result?.ok == false, "${workspace.name} was written through: ${result?.message}")
            }
            assertEquals("precious", outside.readText())
            assertTrue(outsideDir.listFiles().isNullOrEmpty(), "something was written outside the worktree")
            assertTrue("session attach" !in File(tmp, "args.log").readText())
        } finally {
            System.setProperty("user.home", home)
        }
    }

    @Test
    fun checkpointsAreReadWithTheirIntentAndSummary() = runBlocking {
        Assume.assumeTrue("needs a POSIX shell", !windows)
        val tmp = Files.createTempDirectory("aive-entire-fake").toFile()
        val cli = fakeEntire(tmp)
        val checkpoints = entireCheckpoints(cli, tmp, limit = 5)
        assertEquals(listOf("abc123def4567890", "ffff0000"), checkpoints.map { it.id })
        val first = checkpoints[0]
        assertEquals("The Aive", first.agent)
        assertEquals("Make a.txt say two", first.intent)
        assertEquals("Changed a.txt.", first.summary)
        assertNull(checkpoints[1].intent)
        assertNull(checkpoints[1].summary, "an ungenerated summary is dropped")
        val context = assertNotNull(EntireRecorder(locate = { cli }, sessions = AiveEntireSessions(tmp)).checkpointContext(tmp))
        assertEquals(
            "- abc123def456 (The Aive) Haive: two | asked: Make a.txt say two | did: Changed a.txt.\n- ffff0000 (Claude Code) m",
            context,
        )
    }
}
