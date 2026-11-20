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
    fun antigravityGrokAndCopilotTakeTheirPromptAsTheLastArgumentAfterDashPAndNeverSkipChecks() {
        listOf(CliAgentSpecs.Antigravity, CliAgentSpecs.Grok, CliAgentSpecs.Copilot).forEach { spec ->
            assertEquals(CliPromptChannel.Argument, spec.promptChannel, spec.id)
            CliRunMode.entries.forEach { mode ->
                val args = spec.arguments(mode, "/w", "do the task")
                assertEquals("-p", args[args.size - 2], "${spec.id} $mode")
                assertEquals("do the task", args.last())
                assertEquals(null, CliAgentSpecs.violation(spec.fixedArguments(mode, "/w")), "${spec.id} $mode")
            }
        }
        // Their forbidden spellings are caught.
        assertTrue(CliAgentSpecs.violation(listOf("--always-approve")) != null)
        assertTrue(CliAgentSpecs.violation(listOf("--allow-all")) != null)
        assertTrue(CliAgentSpecs.violation(listOf("--allow-all-paths")) != null)
        assertEquals(null, CliAgentSpecs.violation(listOf("--allow-tool=write")))
    }

    @Test
    fun grokAndCopilotEditOnlyWhenTheRoleMayAndNoneOfTheNewOnesCanRunAShell() {
        val grokRead = CliAgentSpecs.Grok.fixedArguments(CliRunMode.ReadOnly, "/w")
        val grokEdit = CliAgentSpecs.Grok.fixedArguments(CliRunMode.EditFiles, "/w")
        assertFalse("Edit" in grokRead)
        assertTrue("Edit" in grokEdit)
        assertEquals("/w", grokEdit[grokEdit.indexOf("--cwd") + 1])
        assertTrue(grokEdit.windowed(2).contains(listOf("--deny", "Bash")))
        val copilotRead = CliAgentSpecs.Copilot.fixedArguments(CliRunMode.ReadOnly, "/w")
        val copilotEdit = CliAgentSpecs.Copilot.fixedArguments(CliRunMode.EditFiles, "/w")
        assertTrue("--deny-tool=write" in copilotRead && "--allow-tool=write" !in copilotRead)
        assertTrue("--allow-tool=write" in copilotEdit && "--deny-tool=write" !in copilotEdit)
        listOf(copilotRead, copilotEdit).forEach { assertTrue("--deny-tool=shell" in it) }
        listOf(CliAgentSpecs.Antigravity, CliAgentSpecs.Grok, CliAgentSpecs.Copilot).forEach {
            assertFalse(AgentCapability.ShellExecution in it.capabilities, it.id)
        }
        // Antigravity is read-only: headless, a tool that needs approval is denied.
        assertFalse(AgentCapability.RepositoryWrite in CliAgentSpecs.Antigravity.capabilities)
    }

    @Test
    fun gooseTakesItsTaskOnStandardInputAndCrushAsTheLastArgumentAndNeitherEditsOrSkipsChecks() {
        val goose = CliAgentSpecs.Goose
        assertEquals(CliPromptChannel.Stdin, goose.promptChannel)
        CliRunMode.entries.forEach { mode ->
            val args = goose.arguments(mode, "/w", "do the task")
            assertFalse("do the task" in args, "the task is not in the process list")
            assertEquals(listOf("run", "--no-session", "-q", "--output-format", "text", "-i", "-"), args)
            assertEquals(null, CliAgentSpecs.violation(args))
            val crush = CliAgentSpecs.Crush.arguments(mode, "/w", "do the task")
            assertEquals(listOf("run", "do the task"), crush)
            assertEquals(null, CliAgentSpecs.violation(crush))
        }
        listOf(goose, CliAgentSpecs.Crush).forEach { spec ->
            assertFalse(AgentCapability.RepositoryWrite in spec.capabilities, spec.id)
            assertFalse(AgentCapability.ShellExecution in spec.capabilities, spec.id)
        }
        assertTrue(CliAgentSpecs.Goose.isProjectConfig(".goosehints"))
        assertTrue(CliAgentSpecs.Crush.isProjectConfig("pkg/crush.json"))
    }

    @Test
    fun cursorAmpKiloOpenCodeAndAiderNeverSkipChecksAndTakeThePromptLast() {
        listOf(CliAgentSpecs.Cursor, CliAgentSpecs.Amp, CliAgentSpecs.Kilo, CliAgentSpecs.OpenCode, CliAgentSpecs.Aider).forEach { spec ->
            CliRunMode.entries.forEach { mode ->
                val args = spec.arguments(mode, "/w", "do the task")
                assertEquals("do the task", args.last(), spec.id)
                assertEquals(null, CliAgentSpecs.violation(spec.fixedArguments(mode, "/w")), "${spec.id} $mode")
            }
            assertFalse(AgentCapability.ShellExecution in spec.capabilities, spec.id)
        }
        assertFalse("--dry-run" in CliAgentSpecs.Aider.fixedArguments(CliRunMode.EditFiles, "/w"))
        assertTrue("--dry-run" in CliAgentSpecs.Aider.fixedArguments(CliRunMode.ReadOnly, "/w"))
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
