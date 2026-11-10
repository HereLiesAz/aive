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
        val viaArgument = CliAgentSpec(
            id = "cli-test", displayName = "Test", binaries = listOf("t"), promptChannel = CliPromptChannel.Argument,
            outputFormat = CliOutputFormat.PlainText, capabilities = emptySet(), sandboxNote = "n",
            build = { _, _, _ -> listOf("--flag", "-p") },
        )
        val args = viaArgument.arguments(CliRunMode.EditFiles, "/w", "x".repeat(300_000))
        assertEquals(CliAgentSpec.MAX_ARGUMENT_PROMPT_CHARS, args.last().length)
        assertTrue(CliAgentSpec.MAX_ARGUMENT_PROMPT_CHARS <= 32_000, "fits a Windows command line")
        assertEquals("-p", args[args.size - 2])
    }

    @Test
    fun geminiAndQwenTakeTheTaskOnStdinSoItIsNeitherInTheProcessListNorLimitedByTheCommandLine() {
        val long = "x".repeat(300_000)
        listOf(CliAgentSpecs.Gemini, CliAgentSpecs.Qwen).forEach { spec ->
            assertEquals(CliPromptChannel.Stdin, spec.promptChannel, spec.id)
            CliRunMode.entries.forEach { mode ->
                val args = spec.arguments(mode, "/w", long)
                assertTrue(args.none { long.take(100) in it }, "${spec.id} $mode: the task is not an argument")
                assertEquals(CliAgentSpecs.STDIN_TASK_SENTENCE, args[args.indexOf("-p") + 1])
                assertTrue(args.sumOf { it.length } < 500)
            }
        }
    }

    @Test
    fun aForbiddenValueIsCaughtWhetherTheOptionIsWrittenWithAnEqualsSignOrAsTwoArguments() {
        assertTrue(CliAgentSpecs.violation(listOf("--approval-mode=yolo")) != null)
        assertTrue(CliAgentSpecs.violation(listOf("--approval-mode=YOLO")) != null)
        assertEquals("--approval-mode yolo", CliAgentSpecs.violation(listOf("--approval-mode", "yolo")))
        assertEquals("--approval-mode YOLO", CliAgentSpecs.violation(listOf("--approval-mode", "YOLO")))
        assertTrue(CliAgentSpecs.violation(listOf("exec", "--sandbox", "danger-full-access")) != null)
        assertTrue(CliAgentSpecs.violation(listOf("exec", "-s", "danger-full-access")) != null)
        assertTrue(CliAgentSpecs.violation(listOf("--permission-mode=bypassPermissions")) != null)
        assertTrue(CliAgentSpecs.violation(listOf("--dangerously-skip-permissions")) != null)
        assertEquals(null, CliAgentSpecs.violation(listOf("--approval-mode", "auto_edit", "-p", "x")))
        assertEquals(null, CliAgentSpecs.violation(listOf("--approval-mode=default")))
        CliAgentSpecs.all.forEach { spec ->
            CliRunMode.entries.forEach { mode -> assertEquals(null, CliAgentSpecs.violation(spec.fixedArguments(mode, "/w")), "${spec.id} $mode") }
        }
    }

    @Test
    fun aCliKnowsWhichRepositoryFilesAreItsOwnProjectSettings() {
        val claude = CliAgentSpecs.Claude
        listOf(".claude/settings.json", ".claude/settings.local.json", ".mcp.json", "pkg/.mcp.json", ".CLAUDE/Settings.json").forEach { assertTrue(claude.isProjectConfig(it), it) }
        listOf("CLAUDE.md", ".claude/commands/x.md", "src/settings.json", "docs/mcp.json").forEach { assertFalse(claude.isProjectConfig(it), it) }
        assertTrue(CliAgentSpecs.Gemini.isProjectConfig(".gemini/settings.json"))
        assertTrue(CliAgentSpecs.Gemini.isProjectConfig("app/.GEMINI/x"))
        assertFalse(CliAgentSpecs.Gemini.isProjectConfig(".geminiish/x"))
        assertTrue(CliAgentSpecs.Qwen.isProjectConfig(".qwen/settings.json"))
        assertTrue(CliAgentSpecs.Codex.isProjectConfig(".codex/config.toml"))
        assertFalse(CliAgentSpecs.Codex.isProjectConfig(".gemini/settings.json"), "only its own")
        CliAgentSpecs.all.forEach { assertTrue(it.projectConfig.isNotEmpty(), it.id) }
    }

    @Test
    fun theSandboxNotesDoNotPromiseWhatTheAppDoesNotEnforce() {
        assertFalse("no network" in CliAgentSpecs.Codex.sandboxNote.lowercase(), CliAgentSpecs.Codex.sandboxNote)
        assertFalse("no shell" in CliAgentSpecs.Claude.sandboxNote.lowercase(), CliAgentSpecs.Claude.sandboxNote)
    }
}
