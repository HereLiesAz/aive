package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.providers.cli.CliAgentSpecs
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopCliAgentsTest {
    private fun settings(): CliAgentSettings {
        val node = Preferences.userRoot().node("com/hereliesaz/geministrator/test-cli-host-${UUID.randomUUID()}")
        return CliAgentSettings { node }
    }

    private class Scripted(val lines: List<String>, val exit: Int = 0, val stderr: String = "", val timedOut: Boolean = false) : CliProcessRunner {
        val commands = mutableListOf<List<String>>()
        override suspend fun run(
            command: List<String>, directory: File, environment: Map<String, String>, stdin: String?, timeoutMillis: Long, onLine: suspend (String) -> Unit,
        ): CliProcessResult {
            commands += command
            lines.forEach { onLine(it) }
            return CliProcessResult(exit, timedOut = timedOut, stderrTail = stderr)
        }
    }

    private val claudeAnswer = """{"type":"result","subtype":"success","is_error":false,"result":"OK"}"""

    @Test
    fun everyKnownCliIsListedWithWhereItWasFoundAndIsOffUntilTurnedOn() = runBlocking {
        var changed = 0
        val host = DesktopCliAgents(
            settings = settings(), runner = Scripted(listOf("2.0.1 (Claude Code)")),
            locate = { spec -> if (spec.id == "cli-claude") File("/usr/local/bin/claude") else null }, onChanged = { changed += 1 },
        )

        val before = host.statuses()
        assertEquals(CliAgentSpecs.all.map { it.id }, before.map { it.spec.id })
        val claude = before.first { it.spec.id == "cli-claude" }
        assertEquals("/usr/local/bin/claude", claude.path)
        assertEquals("2.0.1 (Claude Code)", claude.version)
        assertFalse(claude.enabled || claude.passKeys, "off by default, and no key passed")
        assertTrue(before.filter { it.spec.id != "cli-claude" }.all { it.path == null })

        host.setEnabled("cli-claude", true)
        assertEquals(1, changed, "the provider list is rebuilt")
        assertTrue(host.statuses().first { it.spec.id == "cli-claude" }.enabled)
    }

    @Test
    fun theTestRunsTheCliReadOnlyAndReportsAnAnswerOrWhyItFailed() = runBlocking {
        val ok = Scripted(listOf(claudeAnswer))
        val host = DesktopCliAgents(settings = settings(), runner = ok, locate = { File("/usr/local/bin/claude") })

        val passed = host.test("cli-claude")
        assertTrue(passed.ok && "OK" in passed.summary, passed.summary)
        val command = ok.commands.single()
        assertEquals("plan", command[command.indexOf("--permission-mode") + 1], "read-only")
        assertEquals(passed, host.statuses().first { it.spec.id == "cli-claude" }.lastTest, "the last test is kept")

        val badFlag = DesktopCliAgents(settings = settings(), runner = Scripted(emptyList(), exit = 1, stderr = "error: unknown option '--permission-mode'"), locate = { File("/x/claude") })
        val failed = badFlag.test("cli-claude")
        assertFalse(failed.ok)
        assertTrue("unknown option" in failed.summary && "Exit 1" in failed.summary, failed.summary)

        val slow = DesktopCliAgents(settings = settings(), runner = Scripted(emptyList(), timedOut = true), locate = { File("/x/claude") })
        assertTrue("logged in" in slow.test("cli-claude").summary)

        val none = DesktopCliAgents(settings = settings(), runner = ok, locate = { null })
        assertTrue("not installed" in none.test("cli-claude").summary)
    }

    @Test
    fun onlyACliThatIsOnAndInstalledJoinsTheProviderList() {
        val s = settings()
        s.setEnabled("cli-claude", true)
        // Not installed in the test environment: on but absent is still not registered.
        assertEquals(emptyList(), configuredCliProviders(s).map { it.id.value }.filter { it == "cli-claude" && CliLocator.locate(CliAgentSpecs.Claude) == null })
        assertEquals(emptyList(), configuredCliProviders(settings()).map { it.id.value }, "all off: none registered")
    }
}
