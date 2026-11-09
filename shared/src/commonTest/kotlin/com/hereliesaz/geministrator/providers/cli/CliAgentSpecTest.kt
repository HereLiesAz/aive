package com.hereliesaz.geministrator.providers.cli

import com.hereliesaz.geministrator.domain.AgentCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliAgentSpecTest {
    private val prompt = "Fix the failing test."

    @Test
    fun noSpecInAnyModeEverAsksTheCliToSkipItsOwnPermissionChecks() {
        CliAgentSpecs.all.forEach { spec ->
            CliRunMode.entries.forEach { mode ->
                val args = spec.arguments(mode, "/work/tree", prompt)
                CliAgentSpecs.FORBIDDEN_FLAGS.forEach { flag ->
                    assertFalse(args.any { it.contains(flag, ignoreCase = true) }, "${spec.id} in $mode passes $flag: $args")
                }
            }
        }
    }

    @Test
    fun theSuiteIsAListOfUniqueCliIdsWithABinaryAndAWordForWhatItsSandboxDoes() {
        assertEquals(CliAgentSpecs.all.size, CliAgentSpecs.all.map { it.id }.toSet().size)
        CliAgentSpecs.all.forEach {
            assertTrue(it.id.startsWith("cli-") && it.binaries.isNotEmpty() && it.sandboxNote.isNotBlank(), it.id)
            assertTrue(it.optionalKeyEnvironment.none { v -> v in it.authEnvironment }, "an API key is never part of how it finds its login")
        }
        assertEquals(CliAgentSpecs.Claude, CliAgentSpecs.byId("cli-claude"))
    }

    @Test
    fun claudeOnlyEditsFilesAndHasNoShellOrWebToolWhetherOrNotItMayEdit() {
        val edit = CliAgentSpecs.Claude.arguments(CliRunMode.EditFiles, "/w", prompt)
        val read = CliAgentSpecs.Claude.arguments(CliRunMode.ReadOnly, "/w", prompt)

        assertEquals("acceptEdits", edit[edit.indexOf("--permission-mode") + 1])
        assertEquals("plan", read[read.indexOf("--permission-mode") + 1])
        assertEquals("Read,Glob,Grep,Edit,Write", edit[edit.indexOf("--allowedTools") + 1])
        assertEquals("Read,Glob,Grep", read[read.indexOf("--allowedTools") + 1])
        listOf(edit, read).forEach { assertEquals("Bash,WebFetch,WebSearch", it[it.indexOf("--disallowedTools") + 1]) }
        assertFalse(prompt in edit, "its prompt goes to stdin, not the process list")
        assertFalse(AgentCapability.ShellExecution in CliAgentSpecs.Claude.capabilities)
    }

    @Test
    fun codexRunsInsideItsOwnSandboxAndReadsStdinAndOnlyItClaimsShellExecution() {
        val edit = CliAgentSpecs.Codex.arguments(CliRunMode.EditFiles, "/w", prompt)
        val read = CliAgentSpecs.Codex.arguments(CliRunMode.ReadOnly, "/w", prompt)

        assertEquals("workspace-write", edit[edit.indexOf("--sandbox") + 1])
        assertEquals("read-only", read[read.indexOf("--sandbox") + 1])
        assertEquals("/w", edit[edit.indexOf("-C") + 1])
        assertEquals("-", edit.last(), "the prompt comes from stdin")
        assertEquals(listOf("cli-codex"), CliAgentSpecs.all.filter { AgentCapability.ShellExecution in it.capabilities }.map { it.id })
    }

    @Test
    fun anArgumentPromptIsLastAndBoundedAndNoSpecCanBeGivenAnEmptyCommand() {
        val long = "x".repeat(300_000)
        val args = CliAgentSpecs.Gemini.arguments(CliRunMode.EditFiles, "/w", long)
        assertEquals(CliAgentSpec.MAX_ARGUMENT_PROMPT_CHARS, args.last().length)
        assertEquals("auto_edit", args[args.indexOf("--approval-mode") + 1])
        assertEquals("-p", args[args.size - 2])
    }
}
