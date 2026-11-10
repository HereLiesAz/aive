package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.providers.cli.CliAgentSpecs
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopCliAgentsTest {
    private val nodes = mutableListOf<Preferences>()

    /** Every preferences node a test made is removed, whether the test passed or failed. */
    @AfterTest
    fun removePreferenceNodes() {
        nodes.forEach { node -> runCatching { node.removeNode(); node.flush() } }
        nodes.clear()
    }

    private fun settings(): CliAgentSettings {
        val node = Preferences.userRoot().node("com/hereliesaz/geministrator/test-cli-host-${UUID.randomUUID()}").also { nodes += it }
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
        val runner = Scripted(listOf("2.0.1 (Claude Code)"))
        val host = DesktopCliAgents(
            settings = settings(), runner = runner,
            locate = { spec -> if (spec.id == "cli-claude") File("/usr/local/bin/claude") else null }, onChanged = { changed += 1 },
        )

        val before = host.statuses()
        assertEquals(CliAgentSpecs.all.map { it.id }, before.map { it.spec.id })
        val claude = before.first { it.spec.id == "cli-claude" }
        assertEquals("/usr/local/bin/claude", claude.path)
        assertEquals(null, claude.version, "listing a CLI that is off does not run it")
        assertEquals(emptyList(), runner.commands, "no program was started just to list the screen")
        assertFalse(claude.enabled || claude.passKeys || claude.allowProjectConfig, "off by default, no key passed, no project settings")
        assertTrue(before.filter { it.spec.id != "cli-claude" }.all { it.path == null })

        host.setEnabled("cli-claude", true)
        assertEquals(1, changed, "the provider list is rebuilt")
        val after = host.statuses().first { it.spec.id == "cli-claude" }
        assertTrue(after.enabled)
        assertEquals("2.0.1 (Claude Code)", after.version, "once it is on, its version is asked")
        assertEquals(1, runner.commands.size)
    }

    @Test
    fun aCliThatWasTestedHasItsVersionAskedEvenWhileOff() = runBlocking {
        val runner = Scripted(listOf(claudeAnswer))
        val host = DesktopCliAgents(settings = settings(), runner = runner, locate = { File("/usr/local/bin/claude") })
        assertEquals(null, host.statuses().first { it.spec.id == "cli-claude" }.version)
        host.test("cli-claude")
        val testedRuns = runner.commands.size
        host.statuses()
        assertEquals(testedRuns + 1, runner.commands.size, "one --version for the CLI the keeper tested")
    }

    @Test
    fun theProjectSettingsSwitchIsOffUntilTheKeeperTurnsItOn() = runBlocking {
        val host = DesktopCliAgents(settings = settings(), runner = Scripted(emptyList()), locate = { File("/usr/local/bin/claude") })
        assertFalse(host.statuses().first { it.spec.id == "cli-claude" }.allowProjectConfig)
        host.setAllowProjectConfig("cli-claude", true)
        assertTrue(host.statuses().first { it.spec.id == "cli-claude" }.allowProjectConfig)
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
        val on = settings().also { it.setEnabled("cli-claude", true) }
        val installed = { spec: com.hereliesaz.geministrator.providers.cli.CliAgentSpec -> if (spec.id == "cli-claude") File("/usr/bin/claude") else null }
        assertEquals(listOf("cli-claude"), configuredCliProviders(on, installed).map { it.id.value }, "on and installed: registered")
        assertEquals(emptyList(), configuredCliProviders(on) { null }.map { it.id.value }, "on but not installed: not registered")
        assertEquals(emptyList(), configuredCliProviders(settings(), installed).map { it.id.value }, "installed but off: not registered")
    }
}
