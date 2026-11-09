package com.hereliesaz.geministrator.providers.cli

import com.hereliesaz.geministrator.domain.AgentCapability

/** How much a CLI agent may do in its checkout. */
enum class CliRunMode {
    /** Reads and answers; edits nothing. Used by the Test button and for read-only roles. */
    ReadOnly,

    /** Edits files in its own isolated worktree. */
    EditFiles,
}

/** Where a CLI reads its prompt from. */
enum class CliPromptChannel {
    /** Piped to its standard input: no argv size limit, and not visible in the process list. */
    Stdin,

    /** Given as an argument (bounded to [CliAgentSpec.MAX_ARGUMENT_PROMPT_CHARS]). */
    Argument,
}

/** The shape of a CLI's output, which decides how its lines are read. */
enum class CliOutputFormat { ClaudeStreamJson, CodexJsonl, PlainText }

/**
 * One LLM CLI the app can drive headless (docs/Local-CLI-Agents.md). The app runs the vendor's own CLI
 * under the login the keeper already has on this computer, so it needs and keeps no API key for it.
 *
 * A spec is data: the command line it builds is pure, and **never asks the CLI to skip its own
 * permission checks** (`CliAgentSpecs.FORBIDDEN_FLAGS`; the tests build every spec in every mode and refuse a
 * command that contains one). The flags are written from the CLIs' documentation and have not been run by the
 * app's authors against every version; the Test button in Keeper's room runs the installed CLI read-only and
 * says what it did, and a flag that is wrong for a version is fixed here.
 */
data class CliAgentSpec(
    /** The provider id: `cli-<name>`. */
    val id: String,
    val displayName: String,
    /** Executable names to look for, in order. */
    val binaries: List<String>,
    val versionArgs: List<String> = listOf("--version"),
    val promptChannel: CliPromptChannel,
    val outputFormat: CliOutputFormat,
    val capabilities: Set<AgentCapability>,
    /** Variables the CLI needs beyond the basics to find its own login (never an API key). */
    val authEnvironment: List<String> = emptyList(),
    /** API-key variables the keeper may choose to pass; none is passed unless they do. */
    val optionalKeyEnvironment: List<String> = emptyList(),
    /** What the CLI's own sandbox does, in words, for the keeper and the docs. */
    val sandboxNote: String,
    private val build: (CliAgentSpec, CliRunMode, String) -> List<String>,
) {
    /** The arguments (after the program) for [mode]; [workingDirectory] is where it runs. Pure. */
    fun arguments(mode: CliRunMode, workingDirectory: String, prompt: String): List<String> =
        build(this, mode, workingDirectory) + if (promptChannel == CliPromptChannel.Argument) listOf(prompt.take(MAX_ARGUMENT_PROMPT_CHARS)) else emptyList()

    companion object {
        const val MAX_ARGUMENT_PROMPT_CHARS = 100_000
        const val MAX_STDIN_PROMPT_CHARS = 400_000
    }
}

object CliAgentSpecs {
    /**
     * Flags that make a CLI stop asking before it acts, or lift its sandbox. The app never passes them: the
     * only approval the keeper gives is the plan gate, and the CLI's own checks stay on.
     */
    val FORBIDDEN_FLAGS: List<String> = listOf(
        "--dangerously-skip-permissions", "--yolo", "--yes-always", "--dangerously-bypass-approvals-and-sandbox",
        "danger-full-access", "--allow-all-tools", "--force", "bypassPermissions", "--approval-mode=yolo",
    )

    private val claudeEdit = "Read,Glob,Grep,Edit,Write"
    private val claudeRead = "Read,Glob,Grep"

    /** Claude Code. `claude -p` reads its prompt from stdin; no shell or web tool is allowed, so it only edits files. */
    val Claude = CliAgentSpec(
        id = "cli-claude", displayName = "Claude Code", binaries = listOf("claude"),
        promptChannel = CliPromptChannel.Stdin, outputFormat = CliOutputFormat.ClaudeStreamJson,
        capabilities = setOf(
            AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite, AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval, AgentCapability.TestAuthoring,
        ),
        authEnvironment = listOf("CLAUDE_CONFIG_DIR"),
        optionalKeyEnvironment = listOf("ANTHROPIC_API_KEY"),
        sandboxNote = "Allowed tools are Read, Glob, Grep and, when it may edit, Edit and Write. It has no shell and no web tool.",
        build = { _, mode, _ ->
            listOf(
                "-p", "--output-format", "stream-json", "--verbose",
                "--permission-mode", if (mode == CliRunMode.EditFiles) "acceptEdits" else "plan",
                "--allowedTools", if (mode == CliRunMode.EditFiles) claudeEdit else claudeRead,
                "--disallowedTools", "Bash,WebFetch,WebSearch",
            )
        },
    )

    /** Codex CLI. `codex exec -` reads stdin; its own OS sandbox confines commands to the workspace with no network. */
    val Codex = CliAgentSpec(
        id = "cli-codex", displayName = "Codex", binaries = listOf("codex"),
        promptChannel = CliPromptChannel.Stdin, outputFormat = CliOutputFormat.CodexJsonl,
        capabilities = setOf(
            AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite, AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval, AgentCapability.TestAuthoring, AgentCapability.ShellExecution, AgentCapability.Testing,
        ),
        authEnvironment = listOf("CODEX_HOME"),
        optionalKeyEnvironment = listOf("OPENAI_API_KEY"),
        sandboxNote = "Its own sandbox (workspace-write) lets it run commands inside the worktree with no network; read-only when it may not edit.",
        build = { _, mode, dir ->
            listOf(
                "exec", "--json", "--color", "never", "--sandbox", if (mode == CliRunMode.EditFiles) "workspace-write" else "read-only",
                "-C", dir, "-",
            )
        },
    )

    /** Gemini CLI. `gemini -p <prompt>`; `auto_edit` approves file edits only, and shell commands stay unapproved (denied headless). */
    val Gemini = CliAgentSpec(
        id = "cli-gemini", displayName = "Gemini CLI", binaries = listOf("gemini"),
        promptChannel = CliPromptChannel.Argument, outputFormat = CliOutputFormat.PlainText,
        capabilities = setOf(
            AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite, AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval, AgentCapability.TestAuthoring,
        ),
        authEnvironment = listOf("GEMINI_CONFIG_DIR"),
        optionalKeyEnvironment = listOf("GEMINI_API_KEY", "GOOGLE_API_KEY"),
        sandboxNote = "File edits are approved automatically; shell commands are not, so headless it cannot run them.",
        build = { _, mode, _ ->
            listOf("--approval-mode", if (mode == CliRunMode.EditFiles) "auto_edit" else "default", "-p")
        },
    )

    /** Qwen Code, a fork of Gemini CLI with the same headless flags. */
    val Qwen = CliAgentSpec(
        id = "cli-qwen", displayName = "Qwen Code", binaries = listOf("qwen"),
        promptChannel = CliPromptChannel.Argument, outputFormat = CliOutputFormat.PlainText,
        capabilities = Gemini.capabilities,
        optionalKeyEnvironment = listOf("OPENAI_API_KEY"),
        sandboxNote = Gemini.sandboxNote,
        build = { _, mode, _ -> listOf("--approval-mode", if (mode == CliRunMode.EditFiles) "auto_edit" else "default", "-p") },
    )

    /** Every CLI the app knows how to drive. Adding one is adding a spec here. */
    val all: List<CliAgentSpec> = listOf(Claude, Codex, Gemini, Qwen)

    fun byId(id: String): CliAgentSpec? = all.firstOrNull { it.id == id }
}
