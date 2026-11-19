# The Aive: independent command-line engine

`aive` runs the **real shared workflow engine** without the desktop app. It owns provider sessions, the workflow coordinator, recovery, and a distinct durable SQLite state store. No GUI or desktop process is required.

## Install

Requires JDK 21.

```sh
./gradlew :cliApp:test :cliApp:installDist
# cliApp/build/install/aive/bin/aive
```

## Foreground execution

```sh
export OPENAI_API_KEY=...           # or GEMINI_API_KEY, ANTHROPIC_API_KEY, XAI_API_KEY, etc.
aive run --objective "Audit the documentation" --project Docs
aive run --hive my-hive --objective "Prepare release notes"
aive resume
```

`run` plans a fresh objective with a connected LLM, or executes a saved hive. It stays alive while the coordinator dispatches agents, polls work, retries, verifies artifacts, and persists state. `resume` reopens the last run after interruption. Planning a new objective requires an attached interactive terminal; human decisions are confirmed there. Without a terminal, workflows waiting on a person remain pending and exit with code `4` rather than auto-approving.

## Long-running daemon and API

```sh
aive serve                            # separate process; no desktop app
# In a second shell:
aive status --json
aive providers
aive hives list
aive hives create --file hive.json
aive hives draft --prompt "Document the API"
aive runs start --hive-id my-hive --objective "Check release"
aive runs get <run-id>
aive runs events <run-id> --limit 50
aive approvals                        # read only
aive sources                          # read only: which Sources are on
aive agents                           # read only: which vendor CLIs are installed and on
aive runs stop <run-id> --yes
```

`serve` uses the same authenticated loopback `ControlApi` and `RuntimeControlHost` as The Aive's desktop JVM runtime, with 23 commands and the same validation and context-taint checks. Its default port is 41243. Set `AIVE_HEADLESS_PORT` to change it. `AIVE_HEADLESS_CONTROL_URL` and `AIVE_HEADLESS_TOKEN_FILE` override discovery when needed; they never direct commands to the desktop app automatically. `--json` produces machine-readable responses; `--file -` reads JSON input from stdin; destructive actions require `--yes`. `aive --help` lists all commands.

When the *daemon itself* has a real terminal, its in-process keeper console supports `status`, `approvals`, `approve <task-id>`, `quit`. Human approval requires an exact second confirmation after showing the task details. No approval decision can be sent via the HTTP control API, MCP, or remote commands. A service without a terminal leaves gates pending; stop it and use `aive resume` in a keeper terminal.

`aive serve` does not host or join a compute pool, run the A2A server or hold a Remote access link: those live in the desktop app and the Android GitHub build. See [Platform parity](Platform-Parity.md).

## Local-only management of Sources and CLI agents

```sh
aive agents install cli-claude      # one `npm install -g <package>`, for a CLI that has one
aive agents enable|disable cli-claude
aive agents test cli-claude         # runs the installed CLI read-only on a tiny prompt (uses your own quota)
aive sources enable|disable openai
```

These run **inside the CLI process** and are deliberately not control API commands: no HTTP route, MCP tool or token can install, switch on or run a vendor CLI. Each needs an attached interactive terminal (without one it exits `2` and changes nothing, even with `--yes`) and either `--yes` or the id typed back at the prompt. The only flags are `--yes` and `--json`; a key is never accepted as an argument. They edit the same durable stores as the desktop app (`CliAgentSettings` in the user's `java.util.prefs`, and `ProviderEnablement` in the durable UI state), so a switch made here shows in the desktop and the other way round. `aive agents` and `aive sources` alone remain the read-only listings.

Follow-ups, not built: the headless engine does not yet use enabled CLI agents as providers (that needs `CliAgentProvider`, the worktree runner and its Git helpers moved out of `desktopApp`), does not yet honour the Source switches when it builds providers, and its `aive agents`/`aive sources` listings are empty (no views are wired into the headless control host).

## State, secrets, and isolation

The headless engine defaults to `~/.aive/headless/` (override with `AIVE_HEADLESS_HOME`). It stores `workflows.db` (SQLite workflow history and events), `engine.lock` (one live engine per state directory) and `control/control-token` plus `control/control.json` (token file and nonsecret discovery). The desktop database `~/.aive/workflows.db` and desktop control port 41242 remain separate. Only `serve` exposes a local HTTP listener; `run` and `resume` work without one. Two engine processes may not run against the same database simultaneously.

API credentials come from the environment: `OPENAI_API_KEY`, `GEMINI_API_KEY`, `ANTHROPIC_API_KEY`, `XAI_API_KEY`, and the supported hosted-provider keys. `GITHUB_TOKEN` adds the GitHub Actions/OpenCode and repository-operation integrations; `GITLAB_TOKEN` adds GitLab repository operations. Secrets are not stored in the workflow database or passed as command-line flags. Platform-only features (local model installers, desktop file chooser, native rendering) are not launched.

CLI results exit `0` on success, `1` on workflow failure/API refusal, `2` on invalid usage, `3` on transport/runtime failure, and `4` for pending human approval without a terminal. See [MCP](MCP.md), [Threat model](THREAT_MODEL.md), [Architecture](architecture/ARCHITECTURE.md).

## Tests

```sh
./gradlew :cliApp:test
./gradlew test
```
