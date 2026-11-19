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
    /**
     * Paths in a repository that this CLI reads as its own project settings (hooks, MCP servers, tool permissions), so a
     * checkout that tracks one can make it run commands. A path ending in `/` is a folder; any other entry matches a path
     * that starts with it. Matched at any depth and without regard to case.
     */
    val projectConfig: List<String> = emptyList(),
    /**
     * The npm package that installs this CLI (`npm install -g`), or null when the vendor installs it another way. The keeper
     * switching the CLI on, with it missing, runs exactly that command and nothing else; see DesktopCliAgents.install.
     */
    val npmPackage: String? = null,
    private val build: (CliAgentSpec, CliRunMode, String) -> List<String>,
) {
    /** The arguments (after the program) for [mode], without the prompt; [workingDirectory] is where it runs. Pure. */
    fun fixedArguments(mode: CliRunMode, workingDirectory: String): List<String> = build(this, mode, workingDirectory)

    /** The arguments (after the program) for [mode]; [workingDirectory] is where it runs. Pure. */
    fun arguments(mode: CliRunMode, workingDirectory: String, prompt: String): List<String> =
        fixedArguments(mode, workingDirectory) + if (promptChannel == CliPromptChannel.Argument) listOf(prompt.take(MAX_ARGUMENT_PROMPT_CHARS)) else emptyList()

    /** Whether [path] (relative to a checkout, `/`-separated) is one of this CLI's project settings ([projectConfig]). */
    fun isProjectConfig(path: String): Boolean {
        val where = "/" + path.lowercase().trimStart('/')
        return projectConfig.any { entry -> where.contains("/" + entry.lowercase()) }
    }

    companion object {
        /** An argument prompt shares the process list and the operating system's command-line limit (about 32 K on Windows). */
        const val MAX_ARGUMENT_PROMPT_CHARS = 24_000
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
        "danger-full-access", "--allow-all", "--always-approve", "--force", "bypassPermissions", "--approval-mode=yolo",
    )

    /** Options whose value lifts a check, in either spelling (`--approval-mode=yolo` and `--approval-mode yolo`). */
    val FORBIDDEN_OPTION_VALUES: Map<String, Set<String>> = mapOf(
        "--approval-mode" to setOf("yolo"),
        "--permission-mode" to setOf("bypassPermissions"),
        "--sandbox" to setOf("danger-full-access"),
        "-s" to setOf("danger-full-access"),
    )

    /**
     * The first thing in [arguments] (a fixed command line, not a prompt) that skips a CLI's own checks, or null: a
     * forbidden flag anywhere in an argument, or a forbidden value for an option written as `--option=value` or as
     * `--option value`. Structural, so a spec cannot slip one past by changing how it spells the option.
     */
    fun violation(arguments: List<String>): String? {
        arguments.forEachIndexed { index, argument ->
            FORBIDDEN_FLAGS.firstOrNull { argument.contains(it, ignoreCase = true) }?.let { return it }
            val joined = argument.contains('=')
            val option = argument.substringBefore('=').lowercase()
            val forbidden = FORBIDDEN_OPTION_VALUES[option] ?: return@forEachIndexed
            val value = if (joined) argument.substringAfter('=') else arguments.getOrNull(index + 1).orEmpty()
            if (forbidden.any { it.equals(value, ignoreCase = true) }) return "$option $value"
        }
        return null
    }

    /** What `-p` carries when the real task arrives on standard input. */
    const val STDIN_TASK_SENTENCE = "Carry out the task given on standard input."

    private val claudeEdit = "Read,Glob,Grep,Edit,Write"
    private val claudeRead = "Read,Glob,Grep"

    /** Claude Code. `claude -p` reads its prompt from stdin; no shell or web tool is allowed, so it only edits files. */
    val Claude = CliAgentSpec(
        npmPackage = "@anthropic-ai/claude-code",
        id = "cli-claude", displayName = "Claude Code", binaries = listOf("claude"),
        promptChannel = CliPromptChannel.Stdin, outputFormat = CliOutputFormat.ClaudeStreamJson,
        capabilities = setOf(
            AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite, AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval, AgentCapability.TestAuthoring,
        ),
        authEnvironment = listOf("CLAUDE_CONFIG_DIR"),
        optionalKeyEnvironment = listOf("ANTHROPIC_API_KEY"),
        sandboxNote = "The app passes only Read, Glob, Grep and, when it may edit, Edit and Write as allowed tools, and disallows its shell and web tools on the command line. " +
            "Settings in the repository could change that, so a repository that tracks Claude Code settings is refused unless you allow project settings.",
        projectConfig = listOf(".claude/settings", ".mcp.json"),
        build = { _, mode, _ ->
            listOf(
                "-p", "--output-format", "stream-json", "--verbose",
                "--permission-mode", if (mode == CliRunMode.EditFiles) "acceptEdits" else "plan",
                "--allowedTools", if (mode == CliRunMode.EditFiles) claudeEdit else claudeRead,
                "--disallowedTools", "Bash,WebFetch,WebSearch",
            )
        },
    )

    /** Codex CLI. `codex exec -` reads stdin; its own OS sandbox confines writes to the workspace (its network setting is Codex's own). */
    val Codex = CliAgentSpec(
        npmPackage = "@openai/codex",
        id = "cli-codex", displayName = "Codex", binaries = listOf("codex"),
        promptChannel = CliPromptChannel.Stdin, outputFormat = CliOutputFormat.CodexJsonl,
        capabilities = setOf(
            AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite, AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval, AgentCapability.TestAuthoring, AgentCapability.ShellExecution, AgentCapability.Testing,
        ),
        authEnvironment = listOf("CODEX_HOME"),
        optionalKeyEnvironment = listOf("OPENAI_API_KEY"),
        sandboxNote = "Its own sandbox (workspace-write) lets it run commands inside the worktree and write only there; read-only when it may not edit. " +
            "Whether those commands can reach the network follows Codex's own configuration, which the app does not set.",
        projectConfig = listOf(".codex/"),
        build = { _, mode, dir ->
            listOf(
                "exec", "--json", "--color", "never", "--sandbox", if (mode == CliRunMode.EditFiles) "workspace-write" else "read-only",
                "-C", dir, "-",
            )
        },
    )

    /**
     * Gemini CLI. The task goes to standard input and `-p` carries only a fixed sentence (Gemini appends stdin to the `-p` text),
     * so the task is neither in the process list nor limited by the command-line size. `auto_edit` approves file edits only,
     * and shell commands stay unapproved (denied headless).
     */
    val Gemini = CliAgentSpec(
        npmPackage = "@google/gemini-cli",
        id = "cli-gemini", displayName = "Gemini CLI", binaries = listOf("gemini"),
        promptChannel = CliPromptChannel.Stdin, outputFormat = CliOutputFormat.PlainText,
        capabilities = setOf(
            AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite, AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval, AgentCapability.TestAuthoring,
        ),
        authEnvironment = listOf("GEMINI_CONFIG_DIR"),
        optionalKeyEnvironment = listOf("GEMINI_API_KEY", "GOOGLE_API_KEY"),
        sandboxNote = "File edits are approved automatically; shell commands are not, so headless it cannot run them. " +
            "A repository that tracks Gemini settings is refused unless you allow project settings.",
        projectConfig = listOf(".gemini/"),
        build = { _, mode, _ ->
            listOf("--approval-mode", if (mode == CliRunMode.EditFiles) "auto_edit" else "default", "-p", STDIN_TASK_SENTENCE)
        },
    )

    /** Qwen Code, a fork of Gemini CLI with the same headless flags. */
    val Qwen = CliAgentSpec(
        npmPackage = "@qwen-code/qwen-code",
        id = "cli-qwen", displayName = "Qwen Code", binaries = listOf("qwen"),
        promptChannel = CliPromptChannel.Stdin, outputFormat = CliOutputFormat.PlainText,
        capabilities = Gemini.capabilities,
        optionalKeyEnvironment = listOf("OPENAI_API_KEY"),
        sandboxNote = "File edits are approved automatically; shell commands are not, so headless it cannot run them. " +
            "A repository that tracks Qwen settings is refused unless you allow project settings.",
        projectConfig = listOf(".qwen/"),
        build = { _, mode, _ -> listOf("--approval-mode", if (mode == CliRunMode.EditFiles) "auto_edit" else "default", "-p", STDIN_TASK_SENTENCE) },
    )

    /**
     * Google Antigravity CLI (`agy`). `agy -p <prompt>` runs once and prints the answer; the prompt is an argument (its documented
     * form; a stdin prompt is only for `--input-format stream-json`). In headless mode a tool that needs approval is soft-denied, so
     * with no rule in the keeper's own Antigravity settings it reads and answers and cannot edit or run commands: it is declared
     * for read-only work, and the app never passes `--dangerously-skip-permissions`. Some versions reported no output when stdout is a
     * pipe; the Test button shows it.
     */
    val Antigravity = CliAgentSpec(
        id = "cli-antigravity", displayName = "Antigravity", binaries = listOf("agy"),
        promptChannel = CliPromptChannel.Argument, outputFormat = CliOutputFormat.PlainText,
        capabilities = setOf(AgentCapability.RepositoryRead, AgentCapability.PlanGeneration, AgentCapability.PlanApproval),
        sandboxNote = "Headless, a tool that needs approval is denied and the run goes on, so it can read and answer but not edit or run commands " +
            "unless your own Antigravity settings already allow them. The app never skips its permission checks. " +
            "A repository that tracks Antigravity settings is refused unless you allow project settings.",
        projectConfig = listOf(".antigravity/", ".gemini/"),
        build = { _, _, _ -> listOf("--output-format", "text", "-p") },
    )

    /**
     * xAI's Grok Build CLI (`grok`). `grok -p <prompt>` runs once; the app allows only Read and Grep (and Edit when the role may edit),
     * denies its shell and web tools on the command line, and never passes `--always-approve`.
     */
    val Grok = CliAgentSpec(
        id = "cli-grok", displayName = "Grok Build", binaries = listOf("grok"),
        versionArgs = listOf("version"),
        promptChannel = CliPromptChannel.Argument, outputFormat = CliOutputFormat.PlainText,
        capabilities = setOf(
            AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite, AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval, AgentCapability.TestAuthoring,
        ),
        optionalKeyEnvironment = listOf("XAI_API_KEY"),
        sandboxNote = "The app allows only the Read and Grep tools and, when it may edit, Edit, and denies its shell and web tools on the command line. " +
            "A repository that tracks Grok settings is refused unless you allow project settings.",
        projectConfig = listOf(".grok/"),
        build = { _, mode, dir ->
            listOf("--no-auto-update", "--cwd", dir, "--output-format", "plain", "--allow", "Read", "--allow", "Grep") +
                (if (mode == CliRunMode.EditFiles) listOf("--allow", "Edit") else emptyList()) +
                listOf("--deny", "Bash", "--deny", "WebFetch", "--deny", "WebSearch", "-p")
        },
    )

    /**
     * GitHub Copilot CLI (`copilot`). `copilot -p <prompt> -s --no-ask-user` runs once and prints only the answer; the app denies its
     * shell tool, allows file writes only when the role may edit, and never passes `--allow-all` or `--yolo`.
     */
    val Copilot = CliAgentSpec(
        npmPackage = "@github/copilot",
        id = "cli-copilot", displayName = "GitHub Copilot CLI", binaries = listOf("copilot"),
        promptChannel = CliPromptChannel.Argument, outputFormat = CliOutputFormat.PlainText,
        capabilities = Gemini.capabilities,
        sandboxNote = "Its shell tool is denied on the command line; file writes are allowed only when it may edit. " +
            "A repository that tracks Copilot settings is refused unless you allow project settings.",
        projectConfig = listOf(".copilot/", ".github/agents/", ".github/copilot/"),
        build = { _, mode, _ ->
            listOf("--no-auto-update", "--no-ask-user", "-s", "--deny-tool=shell") +
                (if (mode == CliRunMode.EditFiles) listOf("--allow-tool=write") else listOf("--deny-tool=write")) +
                listOf("-p")
        },
    )

    /**
     * Cursor's agent CLI (`agent -p <prompt>`). Headless, a change that needs approval is refused unless `--force` is given, which the
     * app never does; there is no read-only flag, so a read-only run relies on that refusal and on Cursor's own permission rules.
     */
    val Cursor = CliAgentSpec(
        id = "cli-cursor", displayName = "Cursor", binaries = listOf("agent", "cursor-agent"),
        promptChannel = CliPromptChannel.Argument, outputFormat = CliOutputFormat.PlainText,
        capabilities = setOf(AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite, AgentCapability.PlanGeneration, AgentCapability.PlanApproval),
        optionalKeyEnvironment = listOf("CURSOR_API_KEY"),
        sandboxNote = "Headless, anything that needs approval (shell commands included) is refused; the app never passes --force. " +
            "A repository that tracks Cursor settings is refused unless you allow project settings.",
        projectConfig = listOf(".cursor/"),
        build = { _, _, _ -> listOf("--output-format", "text", "-p") },
    )

    /** Amp (`amp -x <prompt>`): the final message on stdout. It has no documented skip-everything flag; its own tool rules apply. */
    val Amp = CliAgentSpec(
        id = "cli-amp", displayName = "Amp", binaries = listOf("amp"),
        npmPackage = "@sourcegraph/amp",
        promptChannel = CliPromptChannel.Argument, outputFormat = CliOutputFormat.PlainText,
        capabilities = setOf(AgentCapability.RepositoryRead, AgentCapability.PlanGeneration, AgentCapability.PlanApproval),
        optionalKeyEnvironment = listOf("AMP_API_KEY"),
        sandboxNote = "Run with its own tool permissions, which the app cannot narrow from the command line; declared for reading and planning only. " +
            "A repository that tracks Amp settings is refused unless you allow project settings.",
        projectConfig = listOf(".amp/"),
        build = { _, _, _ -> listOf("-x") },
    )

    /** Kilo Code CLI (`kilo run <prompt>`). Without `--auto` a request for a permission is rejected, so it reads and answers; the app never passes `--auto`. */
    val Kilo = CliAgentSpec(
        id = "cli-kilo", displayName = "Kilo Code", binaries = listOf("kilo"),
        npmPackage = "@kilocode/cli",
        promptChannel = CliPromptChannel.Argument, outputFormat = CliOutputFormat.PlainText,
        capabilities = setOf(AgentCapability.RepositoryRead, AgentCapability.PlanGeneration, AgentCapability.PlanApproval),
        sandboxNote = "Headless, a request for a permission is rejected and the run ends, so it reads and answers; the app never passes --auto. " +
            "A repository that tracks Kilo settings is refused unless you allow project settings.",
        projectConfig = listOf(".kilo/", "kilo.json"),
        build = { _, _, dir -> listOf("run", "--dir", dir, "--format", "default") },
    )

    /** OpenCode (`opencode run <prompt>`). Its flags beyond `run` are not documented on its CLI page, so none are passed. */
    val OpenCode = CliAgentSpec(
        id = "cli-opencode", displayName = "OpenCode", binaries = listOf("opencode"),
        npmPackage = "opencode-ai",
        promptChannel = CliPromptChannel.Argument, outputFormat = CliOutputFormat.PlainText,
        capabilities = setOf(AgentCapability.RepositoryRead, AgentCapability.PlanGeneration, AgentCapability.PlanApproval),
        sandboxNote = "Run with its own permission settings, which the app cannot narrow from the command line; declared for reading and planning only. " +
            "A repository that tracks OpenCode settings is refused unless you allow project settings.",
        projectConfig = listOf(".opencode/", "opencode.json"),
        build = { _, _, _ -> listOf("run") },
    )

    /**
     * Aider (`aider --message <prompt>`). It edits only the files it is given and commits nothing here (`--no-auto-commits`); a
     * read-only run is a `--dry-run`. The app never passes `--yes-always`, so a confirmation is declined.
     */
    val Aider = CliAgentSpec(
        id = "cli-aider", displayName = "Aider", binaries = listOf("aider"),
        promptChannel = CliPromptChannel.Argument, outputFormat = CliOutputFormat.PlainText,
        capabilities = setOf(AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite, AgentCapability.PlanGeneration, AgentCapability.PlanApproval),
        sandboxNote = "It asks before changing anything and the app never answers yes for it; a read-only run is a dry run. Commits are off. " +
            "A repository that tracks Aider settings is refused unless you allow project settings.",
        projectConfig = listOf(".aider"),
        build = { _, mode, _ ->
            listOf("--no-auto-commits", "--no-pretty", "--no-stream") +
                (if (mode == CliRunMode.ReadOnly) listOf("--dry-run") else emptyList()) + listOf("--message")
        },
    )

    /** Every CLI the app knows how to drive. Adding one is adding a spec here. */
    val all: List<CliAgentSpec> = listOf(Claude, Codex, Gemini, Qwen, Antigravity, Grok, Copilot, Cursor, Amp, Kilo, OpenCode, Aider)

    fun byId(id: String): CliAgentSpec? = all.firstOrNull { it.id == id }
}
