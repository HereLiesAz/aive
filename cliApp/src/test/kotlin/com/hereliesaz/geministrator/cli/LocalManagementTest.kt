package com.hereliesaz.geministrator.cli

import com.hereliesaz.geministrator.CliAgentSettings
import com.hereliesaz.geministrator.CliProcessResult
import com.hereliesaz.geministrator.CliProcessRunner
import com.hereliesaz.geministrator.DesktopCliAgents
import com.hereliesaz.geministrator.mcpbridge.ControlReply
import com.hereliesaz.geministrator.mcpbridge.ControlTransport
import com.hereliesaz.geministrator.mcpbridge.Tools
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalManagementTest {
    private val nodes = mutableListOf<Preferences>()
    private val output = StringBuilder()
    private val errors = StringBuilder()
    private val sourceChanges = mutableListOf<Pair<String, Boolean>>()
    private var runs = 0

    @AfterTest
    fun cleanUp() {
        nodes.forEach { runCatching { it.removeNode(); it.flush() } }
    }

    private val settings: CliAgentSettings by lazy {
        val node = Preferences.userRoot().node("com/hereliesaz/geministrator/test-cli-local-${UUID.randomUUID()}").also { nodes += it }
        CliAgentSettings { node }
    }

    private val runner = CliProcessRunner { _, _, _, _, _, onLine ->
        runs += 1
        onLine("""{"type":"result","subtype":"success","is_error":false,"result":"OK"}""")
        CliProcessResult(0)
    }

    private fun management(terminal: LocalTerminal?) = LocalManagement(
        terminal = terminal,
        agents = { DesktopCliAgents(settings = settings, runner = runner, locate = { File("/usr/bin/claude") }) },
        sourceIds = { setOf("openai", "gemini") },
        setSource = { id, on -> sourceChanges += id to on },
    )

    private fun run(terminal: LocalTerminal?, vararg args: String): Int? = management(terminal).run(args.toList(), output, errors)

    private val typed = LocalTerminal { _ -> "cli-claude" }
    private val refuses = LocalTerminal { _ -> "no" }

    @Test fun withoutATerminalNothingChangesEvenWithYes() {
        assertEquals(2, run(null, "agents", "enable", "cli-claude", "--yes"))
        assertTrue("interactive terminal" in errors)
        assertFalse(settings.enabled("cli-claude"))
        assertEquals(2, run(null, "sources", "disable", "openai", "--yes"))
        assertTrue(sourceChanges.isEmpty())
        assertEquals(2, run(null, "agents", "install", "cli-claude", "--yes"))
        assertEquals(2, run(null, "agents", "test", "cli-claude", "--yes"))
        assertEquals(0, runs)
    }

    @Test fun withATerminalButNoYesItNeedsTheIdTyped() {
        assertEquals(2, run(refuses, "agents", "enable", "cli-claude"))
        assertFalse(settings.enabled("cli-claude"))
        assertEquals(0, run(typed, "agents", "enable", "cli-claude"))
        assertTrue(settings.enabled("cli-claude"))
        assertEquals(0, run(typed, "agents", "disable", "cli-claude"))
        assertFalse(settings.enabled("cli-claude"))
    }

    @Test fun yesSkipsThePromptAndSourcesAreSwitched() {
        assertEquals(0, run(refuses, "sources", "disable", "openai", "--yes"))
        assertEquals(0, run(refuses, "sources", "enable", "gemini", "--yes"))
        assertEquals(listOf("openai" to false, "gemini" to true), sourceChanges)
    }

    @Test fun testRunsTheCliOnlyAfterConfirmation() {
        assertEquals(2, run(refuses, "agents", "test", "cli-claude"))
        assertEquals(0, runs)
        assertEquals(0, run(refuses, "agents", "test", "cli-claude", "--yes"))
        assertEquals(1, runs)
        assertTrue("OK" in output)
    }

    @Test fun usageErrorsAndKeysAsFlagsAreRefused() {
        assertEquals(2, run(typed, "agents", "enable", "nope", "--yes"))
        assertEquals(2, run(typed, "sources", "enable", "nope", "--yes"))
        assertEquals(2, run(typed, "agents", "enable", "--yes"))
        assertEquals(2, run(typed, "agents", "enable", "cli-claude", "--api-key", "sk-secret", "--yes"))
        assertFalse("sk-secret" in output.toString() || "sk-secret" in errors.toString())
        assertFalse(settings.enabled("cli-claude"))
    }

    @Test fun readOnlyListingsAndOtherCommandsFallThrough() {
        assertNull(run(typed, "agents"))
        assertNull(run(typed, "sources"))
        assertNull(run(typed, "status"))
        assertNull(run(typed, "agents", "list"))
    }

    @Test fun noControlApiToolOrCliCommandCarriesTheseVerbs() {
        val words = setOf("install", "enable", "disable", "test")
        assertTrue(Tools.all.none { tool -> tool.name.split('_').any { it in words } })
        assertTrue(Commands.all.none { command -> command.words.any { it in words } })
        // The control-API listings still go through the transport; the management verbs never do.
        val calls = mutableListOf<String>()
        val transport = ControlTransport { method, path, _ -> calls += "$method $path"; ControlReply(200, """{"ok":true,"result":{}}""") }
        assertEquals(0, AiveCli(transport).run(listOf("agents"), output, errors))
        assertEquals(listOf("GET /agents"), calls)
        assertEquals(2, run(null, "agents", "enable", "cli-claude", "--yes"))
        assertEquals(listOf("GET /agents"), calls)
    }
}
