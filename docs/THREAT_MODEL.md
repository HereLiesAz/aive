# Threat Model

This document identifies the trust boundaries, threat actors, attack surfaces, and mitigations relevant to The Aive. It is a living document. Update it when a new executor, integration, or data flow is added.

## Assets

- Provider, repository-service (GitHub/GitLab), and compute-relay credentials.
- Repository contents and history on GitHub, GitLab, and local working trees.
- Workflow definitions, run state, artifacts, and approval decisions.
- Data in attached role surfaces (spreadsheets, SQLite databases).
- The user's device. This includes, on the Android GitHub release flavor, the accessibility permission granted to the Gemini bridge. The app only opens the GitHub release page for updates; it does not download or install APKs itself.

## Trust Boundaries

| Boundary | What crosses it | Direction |
|---|---|---|
| Device ↔ Provider (hosted LLMs, keyless gateways, OpenCode) | Task objectives, role instructions, context artifacts, acceptance criteria, repository snapshots (workspace agents) | Outbound, responses inbound |
| Device ↔ Remote A2A agent | Task objective, role instructions, approved context, optional bearer token; task status and artifacts back | Outbound, responses inbound |
| Local agent ↔ A2A server (desktop, on by default) | Objectives that launch workflows; run status and final artifacts | Inbound on loopback |
| Other computer on the local network ↔ A2A server (desktop, "Open to this network", off by default) | Bearer token and objectives in clear text; run status and final artifacts back; the public Agent Card | Inbound on every IPv4 interface |
| Device ↔ GitHub API | Workflow dispatch and inputs, run status, check runs, artifact downloads, workflow-file installs, repository operations | Both |
| Device ↔ GitLab API | Repository tree/file reads, workspace-agent commits, repository operations | Both |
| GitHub runner ↔ Agent/script | Prompt or script source, repository checkout, job token (runner only) | Inside the user's repository's Actions |
| Device ↔ Local Git (desktop) | Snapshot reads, isolated worktrees, commits, bounded Git commands | Both (local) |
| Device ↔ Compute relay ↔ Pool devices | Node descriptors, task lease envelopes, progress, results | Both |
| Device ↔ Crash relay (Android GitHub flavor; opt-in) | Crash/ANR reports → public GitHub issues | Outbound |
| Device ↔ OTLP collector (opt-in) | Run traces: structure, timing, ids, tokens, cost; content only with a second opt-in | Outbound |
| Device ↔ Gemini app (Android GitHub flavor, opt-in) | Prompt typed into Gemini, reply read from screen/clipboard | Both (on-device) |
| Device ↔ MCP servers (keeper-configured) | Model-written tool arguments out; tool lists and results in; on desktop, a local server process | Both |
| MCP host ↔ script sandbox (desktop, opt-in) | A model-written script in; bridged tool calls and their results; the script's final value out | Both (in-process WebAssembly guest) |
| Device ↔ Update and package sources | GitHub Releases APKs and models, Play in-app updates, Azphalt packages | Inbound |
| Device ↔ Persistence | Workflow state, settings, credentials | Both (local) |
| Haive ↔ User | Workflow definitions, task approval decisions, escalation responses | Both |

## Threat Actors

- **Compromised provider**: A provider returns malicious content disguised as task output. Examples include code that overwrites secrets, instructions injected into artifact text, and a fabricated approval status.
- **Logging free-tier gateway**: A keyless or free model gateway (Kilo, LLM7, OVHcloud anonymous tier, OpenCode Zen free models) retains or trains on prompts.
- **Malicious workflow definition**: A definition author (or import) injects a task objective, role instruction, or acceptance criterion designed to exfiltrate data or execute privileged operations.
- **Malicious artifact**: A prior task produces an artifact (code, text, data) whose content is interpreted as instructions by a downstream provider or executor.
- **Malicious repository content**: Files in the target repository contain prompt-injection aimed at an agent that reads them (OpenCode, GitLab/Local workspace agents).
- **GitHub Actions workflow injection**: An attacker with write access to the repository triggers workflows with controlled inputs, or a workflow file references untrusted external actions.
- **Repository write abuse**: An executor or integration that performs repository operations (branch, commit, PR/MR, push) is tricked into writing malicious content or granting access.
- **Prompt injection via context**: User-controlled content (project name, objective text, imported artifacts) propagates into provider prompts and attempts to redirect agent behavior.
- **Stale/replayed run**: An old or duplicate GitHub Actions run is incorrectly credited as current task completion, bypassing work.
- **Rogue pool member**: Any party holding a compute pool's relay token enrols a node, then publishes leases, claims leases, or tries to impersonate another node.
- **Compromised update/package source**: A tampered APK, model, or Azphalt package is offered for installation.

## Attack Surface by Integration

### Provider (agents / hosted LLMs)

**Threats**
- Prompt injection: context artifacts contain `IGNORE PREVIOUS INSTRUCTIONS` style attacks directed at the provider model.
- Payload exfiltration: task objective or artifacts contain sensitive content the user did not intend to send.
- Fabricated completion: provider claims success without performing the actual task.
- Compromised response: provider returns code or instructions designed to exploit the Aive runtime or downstream tasks.
- Keyless providers: Kilo Gateway, LLM7, and OVHcloud AI Endpoints can be linked without a key. Prompts are then sent unauthenticated to third-party gateways that may log them or route them to providers that do.

**Mitigations in place**
- `PayloadRedactionPolicy` allows per-definition exclusion of artifact kinds, objective, and role instructions from the provider payload.
- Provider artifacts are stored as opaque `ArtifactRef` values. The runtime never executes their content.
- Provider completion requires explicit reconciliation. A provider claiming immediate success returns `Completed` only after the runtime polls and confirms.
- Acceptance criteria are authored by the workflow definition, not the provider.
- Failure reasons and keeper messages can be quoted into a proposed edit of a role's instructions (`RoleInstructionProposals`). Such text may come from a provider or external output, so it is bounded, quoted and shown with its evidence, and it reaches a role only when the keeper approves that exact diff on the Mind tab. Nothing applies it automatically.
- Context provenance (see "Context provenance" below): provider artifacts are labelled as they are taken in, and a provider cannot label its output as more trusted than the runtime says it is.
- A bearer secret travels over plain `http://` only to a loopback or private-network host (`CleartextPolicy`); anything else needs `https://`. The host is read from the URL's authority (cut at the first `/`, `?`, `#` or `\`, then userinfo dropped), so an `@` or `.local` in a fragment, query or path cannot pose as the host.
- Keyless sources are linked by default, once each: the first launch stores the `anonymous` credential for each built-in keyless source (Kilo, LLM7, OVHcloud, AI Horde), remembered per source id so a source the keeper disconnects is not linked again at a later launch. A single switch in Connections turns them all off. Remote agents, store models, command-line agents and keyed providers such as Ollama Cloud (an account key, and credits for its larger models from 2026-11-09) are never linked this way. Connections and the first-run form say once that prompts and repository text sent to a keyless gateway leave the device and may be logged. Keyless entries are listed last so that planning, which uses the first linked provider, prefers a keyed provider; automatic provider choice also puts a keyless provider behind every keyed one, keeps it inside the limits the catalog states (Kilo 200 requests an hour, OVHcloud 2 a minute), and never assigns it to a creature pinned to another provider. Output from a keyless gateway is as untrusted as from any provider: taint rules and approval gates do not change.
- An agent's question to the keeper (`docs/Agent-UI.md`) is rendered only from the host's fixed catalog (text, choices, buttons, short text or number fields, a read-only table), every string as plain text. Unknown components or properties, data bindings without a literal, oversized or deeply nested payloads and broken references are rejected and the message is shown as plain text. Nothing in it is executed, fetched or shown as HTML, a link or an image, and the answer reaches the agent only as a message through its own session.
- Store-installed language models (Azphalt `kind:"llm"`, endpoint tier) are verified like any package (integrity, signature, publisher pin, revocation). Literal endpoints come from the signed manifest; self-hosted endpoints may instead come from a declared non-secret install input and are required to use HTTPS. They get the `azphalt:` id prefix, so a package cannot impersonate or replace a built-in provider. Their setup scripts are never executed on-device. `openai-chat` responses use the normal text-provider path; `moyai-session` is confined to a native `AgentProvider` adapter and cannot alter workflow semantics. Moyai model/provider credentials remain server-side; an optional Moyai workspace password is stored only in Aive's platform credential vault.

**Remaining gaps**
- No automated scanning of provider-returned artifacts for prompt injection before they are used as context for downstream tasks.
- No per-session content hash to detect fabricated or replayed provider responses.
- Because keyless sources are on by default, a task the keeper left to the app can reach one when no keyed source is available (or all have failed); nothing else stops confidential tasks from being routed to a keyless or free-tier provider. Provider failover (docs/Orchestration_layer.md) can also move a task to a keyless provider the keeper linked when the others fail; it only ever picks linked providers, never changes approval gates, and leaves pinned creatures where they are. The only safeguard is the catalog description.

### GitHub Actions executor

**Threats**
- Workflow injection: a `workflow_dispatch` payload references a user-controlled ref that triggers a different, attacker-authored workflow.
- Stale run correlation: the dispatch returns a run ID for a previously queued run rather than the newly dispatched one.
- Malicious artifact download URL: the `/artifacts` response returns a URL pointing to attacker-controlled storage.
- Cancelled/timed-out run silently treated as success: the runtime misclassifies a non-success conclusion.

**Mitigations in place**
- `dispatch()` sends `return_run_details: true` and requires the response to include a `workflow_run_id`. The run ID is bound to the exact dispatch, not guessed from a run list.
- `toStatus()` maps every known non-success conclusion (`cancelled`, `timed_out`, `stale`, `action_required`, `skipped`, `neutral`, `failure`) to `Failed`.
- Archive download URLs are stored as opaque URIs. The runtime does not fetch or execute their contents without an explicit user action.
- The `ref` used for dispatch comes from the executor definition or the repository default branch, not from user-provided free text at runtime.

**Remaining gaps**
- No verification that the returned `workflow_run_id` belongs to the expected workflow file. A race between dispatches of different workflows on the same repository could assign the wrong run.
- No signature verification of downloaded artifacts.

### GitHub Actions script runner and role scripts

Role scripts are JavaScript or Python. They run locally in the Android JavaScript sandbox, or on GitHub through a user-installed runner workflow. The bundled example is `docs/examples/aive-script-runner.yml`.

**Threats**
- A JavaScript/Python role script can intentionally process untrusted dependency artifacts or attempt to exfiltrate context.
- A GitHub-backed script runs with the selected workflow's token permissions and any secrets that workflow exposes.
- Oversized or malicious result archives can attempt memory exhaustion during import.
- Script source and task context are `workflow_dispatch` inputs. Anyone who can read the repository's Actions runs can see them.

**Mitigations in place**
- Local JavaScript uses AndroidX JavaScriptEngine's isolated sandbox rather than WebView page context.
- The normal `PayloadRedactionPolicy` is applied before role/task context reaches any script runner.
- The bundled runner example declares `permissions: contents: read` and has no checkout step. It does not expose repository secrets unless the user modifies the workflow.
- GitHub script source and context are passed as workflow inputs through environment variables and written to files. They are never interpolated directly into shell commands.
- Remote structured results are accepted only from the named `aive-result` artifact and are bounded before ZIP/JSON parsing.
- Script-produced artifacts re-enter the same durable workflow artifact/evidence path as other executor outputs.

### OpenCode agent on GitHub Actions

The Aive installs `.github/workflows/aive-opencode-agent.yml` on the repository's default branch and dispatches it with a JSON task. The workflow runs the open-source OpenCode agent (`opencode run --auto`) on an ephemeral GitHub-hosted runner, using a free OpenCode Zen model by default. The runner commits the agent's changes and pushes them to an `aive/opencode-*` branch. It then publishes an `aive-result` artifact.

**Threats**
- The agent runs with `--auto`, so it has unattended shell and file access on the runner.
- Prompt injection from repository files, issues, or context artifacts can make the agent write arbitrary code into the result branch.
- The agent could exfiltrate the job token and use it to push elsewhere or alter checks.
- A modified copy of the workflow in the repository could change what runs under the app's dispatch.
- The prompt goes to a free Zen model, which may log it.

**Mitigations in place**
- Checkout uses `persist-credentials: false`, so no Git credential is stored in the working copy.
- The agent is started by `sudo -u aive-agent env -i` with only `PATH` and its own `HOME`, so it does not inherit `GITHUB_TOKEN` or the raw task. Both are set only on the step that runs OpenCode, not at job level, so the earlier `npm install` of OpenCode never sees them. The runner, not the agent, pushes the result branch, passing the token to git through the process environment (a one-off `http.extraheader`, never argv or a remote URL) and scrubbing it from push error text.
- The workflow requests only `contents: write` and `checks: write`. The job token expires when the job ends, and the job has a 60-minute timeout.
- The workflow contents are managed by the app. Before each dispatch The Aive compares the installed file with its bundled template. Installing or updating it always requires a person to approve a plan that says the file will be committed directly to the default branch, even for tasks that otherwise run without a plan gate. With the engine's plan gate that is the drafted plan; a run whose approved plan did not disclose the change stops for approval again before writing the file.
- Results land on a new branch, never the default branch, and the task can require plan approval before dispatch. The patch is surfaced as a `CodeChange` artifact for review.
- The prompt is capped at 50,000 characters to stay within GitHub's `workflow_dispatch` input limits.
- The result artifact is bounded: at most 4 MB zipped and 2 MB of JSON.
- Resume after a restart searches up to 1,000 recent dispatches (ten pages) for the run's unique `run-name`.

**Residual risks**
- Prompt injection can put arbitrary code on the result branch. Treat the branch as untrusted until reviewed.
- The agent runs as a separate unprivileged user with a clean environment, which keeps the token out of its process environment; it is defense in depth, not a sandbox. The runner user's `sudo` and the workspace it hands over remain the boundary, and a flaw there would expose the token.
- Free Zen models may log prompts.
- Installing the workflow requires a GitHub token with Contents **and Workflows** write access. That token can rewrite any workflow in the repository.
- The approved workflow install still commits directly to the default branch; repositories that protect that branch must add the file themselves.

### GitLab workspace agent

A linked LLM provider receives a bounded tree (up to 700 paths) and selected file contents from a gitlab.com project. It returns structured create/update/delete actions, which The Aive commits through the GitLab Commits API.

**Threats**
- Prompt injection from repository files steers the model into writing malicious content.
- The model writes outside the repository, into `.git/`, or to files it was not shown.
- An oversized change set exhausts memory or produces an unreviewable commit.

**Mitigations in place**
- No shell or test capability is claimed, and repository code is never executed.
- Paths must be relative. Absolute paths, `.git/`, `..` segments, NUL, and newline characters are rejected. Creates must target new files. Updates and deletes must target tracked files whose complete contents were in the model's snapshot.
- Change sets are capped: 24 actions, 120,000 characters per file, and 500,000 characters in total.
- All actions are committed atomically to a new `haive/...` branch, never to the default branch. The commit diff, branch, and compare URL are emitted as evidence. The plan can be approval-gated.
- The credential is scoped to `gitlab.com`.

**Remaining gaps**
- CI configuration files such as `.gitlab-ci.yml` are not blocked. A generated change to them runs in the project's CI when the branch is pushed.

### Local Git workspace agent (desktop)

**Threats**
- A generated patch escapes the repository, modifies Git control files, or triggers hooks.
- Repository code executes on the desktop host, for example through hooks (including a tracked `core.hooksPath` such as `.husky`) or a configured fsmonitor command.
- A patch clobbers the user's uncommitted work.

**Mitigations in place**
- The model never gets shell access. It receives a bounded snapshot (up to 28 files and 180,000 characters) and returns a unified diff.
- Diff paths are validated as Git parses them: `git apply --numstat -z` on the patch and on its reverse lists both sides of every file, including rename/copy targets, quoted (octal-escaped) names and traditional `---`/`+++` sections. Absolute paths, `.git/` and `..` segments, and `.gitattributes`/`.gitmodules` (compared case-insensitively) are rejected, as is any patch Git cannot parse. The diff must then pass `git apply --check` before it is applied.
- The patch is applied in an isolated `git worktree` on a new `haive/...` branch. The user's checkout is untouched, and the working tree must be clean before the agent starts. If the run fails or is cancelled, the worktree, its `haive/...` branch and the temporary directories are removed even though the coroutine is cancelled.
- Each task's commit (its checkpoint) then moves the workflow run's `haive/run-...` branch only by compare-and-swap (`git update-ref` with the expected old value): only when that branch does not exist yet or still points at the commit the task started from, and never while it is checked out in any worktree. Otherwise the commit stays on the task's own `haive/...` branch. A task that fails after the move puts the branch back, again by compare-and-swap.
- `git worktree add` (post-checkout), `git commit` and the `update-ref`/`branch -D` that move or remove Haive's branches (which can run the `reference-transaction` hook) run with `core.hooksPath` set to an empty temporary directory, and every Git command runs with `core.fsmonitor=false`, so repository hooks and the fsmonitor command do not execute. Git child processes get no inherited `GIT_*` variables (such as `GIT_DIR` or `GIT_INDEX_FILE`) and cannot prompt.
- Haive runs no repository code itself: it never builds or tests the change. The agent records a "Host execution withheld" artifact, and nothing is pushed. Only a separate `TestRunner` task runs the change's tests, and only after a person approves it (see the next section).

**Residual risks**
- Git still honours the user's own Git configuration while checking out and committing. A clean/smudge filter or other driver that the user configured (for example Git LFS, or a filter that runs a tracked script) runs for paths the repository's existing `.gitattributes` select.
- When Entire recording is on, `entire session attach` amends the commit in the worktree with Entire's own process, which does not get the hooks override.

### Local test runner (desktop)

`TaskExecutor.TestRunner` on desktop runs a test command for a linked Local Git project (`LocalTestRunnerClient`; see `docs/Local-Git-Repository-Operations.md`). This is the one place The Aive deliberately runs repository code on the host.

**Threats**
- The test command, or the repository code it runs (build scripts, tests), does harm with the keeper's permissions. A planner or an imported workflow could name any command.
- A command string smuggles shell syntax (`&&`, `;`, `$(...)`, redirections) so it does something other than what it shows.
- The test process reads the app's secrets from its environment (provider API keys, tokens).
- An artifact's metadata points the run at another directory.
- A pool lease from another device makes this computer run a command.
- A hung or runaway test never ends, or floods memory with output.

**Mitigations in place**
- A run starts only when a human-approval task upstream of the test task was approved (completed) in this run; otherwise the task fails. The approval is checked on this device, against this device's run.
- The command is split into an argument list without a shell. Quotes group words; `$`, backticks, line breaks, NUL, and outside quotes `| & ; < > ( ) * ? [ ] { } ~ ! # \`, a leading `NAME=value` and a leading `-` are refused, never interpreted.
- The child gets an allowlisted environment (`PATH`, `HOME`, locale, temp folders, `JAVA_HOME`, Gradle/Android SDK locations and the Windows system variables), no `GIT_*` variables, no prompts, and `CI=true`; provider keys and tokens are not passed.
- It runs in a `worktree-branch` worktree only when the path lies under `<repo>-worktrees/`; otherwise at the newest upstream `headCommit` (validated as a hex commit id) in a temporary detached worktree created with hooks and the fsmonitor disabled and removed afterwards; otherwise in the linked folder.
- It has a timeout (20 minutes). On timeout, on the app closing, or when its workflow stops polling it for two minutes (cancelled or replaced), the whole process tree is ended. Output is kept to the last 32,000 characters per stream.
- The integration is registered only for the device's own runs, not in the set that pool leases use, so another device cannot make this one run tests.
- Exit code 0 completes the task; any other result, including a timeout, fails it. Runs are held in memory and are never replayed after a restart.

**Residual risks**
- Once approved, the command and the repository code run with the keeper's full permissions; nothing is sandboxed. The approval is the boundary, so a person should read what the test task will run.
- A process that leaves the process tree (a daemon that detaches, such as a Gradle daemon) can outlive the run.
- A bare program name is looked up on the app's `PATH`.

### Entire checkpoints and the `entire` CLI (desktop)

See `docs/Entire.md`.

**Threats**
- Checkpoint text (another agent's prompt, intent and summary, or a commit message) is attacker-controlled and flows into a Local Git run's planning prompt: prompt injection that steers the plan or the diff.
- The `entire` binary runs on the host with the keeper's permissions. A binary planted on `PATH`, or a compromised install, runs whatever it likes.
- `entire` runs `entire-agent-aive`, which reads transcripts; a crafted session reference could read files outside the sessions folder, or `write-session` could write outside it.
- Transcripts (prompts, plans, diffs) are committed into checkpoints in the repository's git history, so once that history is pushed they reach everyone who can read the repository.

**Mitigations in place**
- Checkpoint context is bounded (at most eight checkpoints, the newest three with intent and summary, each field truncated) and only informs the plan: the model still returns a diff that passes the Local Git agent's path validation and `git apply --check`, in an isolated worktree with hooks disabled, and nothing runs on the host.
- `entire` is located on absolute `PATH` entries or its installers' fixed folders, never resolved against a repository, and always run with a fixed argument list, never through a shell; `GIT_*` variables are cleared and every call has a timeout.
- The plugin refuses any transcript, session reference or restore target outside the sessions folder, and session ids are reduced to safe file names.
- External agents are allowed only in the run's temporary worktree's untracked `.entire/settings.local.json`; the keeper's own settings are never changed.
- Recording is off until the keeper turns it on, and only applies where the repository already has Entire set up.

**Remaining gaps**
- Prompt injection through checkpoint text is mitigated by the diff pipeline, not prevented: a plan can still be steered toward a harmful but well-formed patch, which is why the branch is left for review.
- The Aive does not verify the `entire` binary's signature or origin.
- Recorded transcripts cannot be withdrawn from a pushed checkpoint by The Aive.

### MCP servers (MCP host)

The keeper connects MCP servers in the Keeper's room (`docs/architecture/MCP_HOST.md`). Text providers that can call functions (Anthropic, OpenAI-compatible, Gemini) offer those servers' tools to the model, and The Aive executes the calls.

**Threats**
- Tool results contain prompt injection aimed at the model, or try to pass as The Aive's own text.
- A model steered by injection calls a tool to send task content to a server, or to act through the server (for example, writing to a repository with the server's credentials).
- A server's tool descriptions or annotations lie about what the tool does.
- A local (stdio) server runs arbitrary code on the desktop.
- Code execution (desktop, opt-in): a model steered by injection writes a script that escapes the sandbox, exhausts the device (an endless loop, unbounded memory or output), or calls tools in bulk without the keeper seeing each call.
- A bearer token is sent to the wrong host or over cleartext.
- A server uses elicitation to phish: it asks the keeper for a password or token in a form, or sends a look-alike URL.
- A server keeps a call open forever (endless input requests, a task that never ends) or leaves work running after the keeper cancels.

**Mitigations in place**
- Every server needs the keeper's consent. Either it is **Always allow**, or the first call per server per run stops at an approval gate that shows the tool, the server and the arguments. An unanswered gate is a refusal, and a refused call is never sent. Reaching a server or listing its tools is not consent.
- Results are fenced as external content, and the model is told to treat them as data. A result cannot close its own fence, and the tool and server names in the fence header (which a server supplies) are cut to one line without quotes or fence markers and bounded to 80 characters. Answers written with them are labelled `ExternalContent` (the label the context-taint rule reads), so a later step that can change something waits at a context-taint gate; they also carry `contentProvenance=external-tool-results` and the list of calls. The runtime never executes them.
- Calls are bounded: 8 rounds, 8 calls per round, 64 tools per run, 4 MB per response (counted in bytes while the response is read, so a server that never stops sending is cut off, not buffered), 120 s per request. Results are truncated at 20,000 characters.
- MCP calls are not repository operations and never receive The Aive's repository credentials. Workspace agents, which write branches, get no MCP tools. Runs behind a provider-side plan gate get no tools, so no tool runs before approval.
- A local (stdio) server is started as a fixed argument list with an allowlisted environment (the same list a CLI agent gets: no provider key, repository token or `GIT_*` variable the app was launched with); a server that needs a secret takes it on its own command line or in its own configuration. Wrong if you rely on a server inheriting a variable from the app: it no longer does.
- Addresses must be HTTPS, or HTTP to this device or a private network. They may not embed credentials, and redirects are not followed. Tokens live in the platform credential store, as other connection secrets do, and are excluded from Android backup.
- Local servers are added only by the keeper on the desktop. They start from a fixed argument list, never a shell. The client declares no capabilities, so a server cannot ask for sampling, elicitation or roots.
- Code execution is off until the keeper turns on **Script sandbox**, and exists only on the desktop. The script runs as JavaScript in QuickJS compiled to WebAssembly, on a pure-Java WebAssembly runtime (Endive): it is never handed to a shell, a JVM scripting engine or the host process. The guest has no filesystem, network, environment or process access; its only effective import is the bridge, and every bridged call is an ordinary call through the host's consent gate for the same run, so the sandbox adds no authority: it can do only what the model could already do with direct calls. It cannot call itself, nor tools the run was not offered.
- A script is bounded: 20,000 characters, 16 tool calls, 10 s of computing time, 12 minutes in all, 64 MB of memory, 4,000 characters of console output and a 16,000-character result, and it stops when the run is cancelled. Its final value is fenced as external content naming the tools it used, and the answer carries the same provenance as any tool use.
- Invariant 3 holds: the script is a tool argument the model wrote, not artifact content, and The Aive's runtime does not execute it; only the capability-less guest interpreter does. Nothing a script or tool returns is executed.
- Local servers are added only by the keeper on the desktop. They start from a fixed argument list, never a shell.
- The client never declares sampling or roots. It declares elicitation only on a call the keeper already allowed, and only where a keeper can be asked. Each request is titled with the server's name, warns against entering secrets (and flags secret-looking fields), and is validated against its flat schema before anything is sent. A URL is shown in full as plain text, with its host set apart and look-alikes (Punycode, embedded names, cleartext) flagged; only `http(s)` is accepted, and it is opened in the system browser only on the keeper's tap, never fetched. Declining, cancelling or 10 minutes of silence sends no data. The Aive sends answers to that server only, never to the model or run artifacts; a result that repeats them is fenced like any other.
- Input rounds are bounded (4 retries, 8 requests each). Tasks are polled with backoff, capped at 30 minutes and 1,000 polls, and cancelled with `tasks/cancel` when the run is cancelled or the wait ends.

**Residual risks**
- After consent, a server sees whatever arguments the model writes, which may include task content. A server allowed always is never asked about again.
- A local server runs with the keeper's own permissions; The Aive does not sandbox it.
- Tool descriptions are shown to the model as the server wrote them.
- With a server allowed always, a script can make its 16 calls to that server without a question per call, as the model could across turns. The keeper sees one progress line for the script, not one per bridged call.
- The sandbox's isolation rests on the WebAssembly runtime and the QuickJS build (quickjs4j); a bug there could reach the JVM. The guest still holds no credentials, and its thread holds only the bridge. Time is bounded by interruption, not instruction metering, so a guest stuck in a host call ends only when that call ends or the total time runs out.
- A keeper can still type a secret into a form, or complete a phishing page opened from a URL request; the warnings do not prevent it. A task the keeper cancels may still finish on the server.

### Research before planning (queen, opt-in per lookup)

Before drafting a plan for an objective that asks to teach, explain, learn or integrate, or names a product, the queen may run one bounded research step (`research/`). The local pass reads only what is already here: the names of the keeper's connected MCP servers' tools (listing is not consent), the names of a linked repository's docs, and the app's control interface capability names when the keeper turned it on. One outside lookup exists: the SearchA2A directory's read-only MCP `search_agents`. It is asked of the keeper first through the existing MCP consent prompt, which shows the exact words to be sent; a decline, a timeout, a missing or switched-off server or any failure means the plan is made without it. Data flow: up to four generic lowercase words (never the objective, links, addresses, paths, quoted text, numbers or key shapes: `ResearchQuery`, `ControlRedaction`) and the caller's IP reach the directory; nothing from the repository leaves. Answers are untrusted: only a cleaned name and numeric level are kept, one line, fence marks, tags, backticks and links removed, at most 8 entries and 2 KB, marked "untrusted reference" and "not connected" in the planner's `availableTools`; the plan instruction says to use them as information only and never to build a step on a tool that is not connected. The step never connects, installs or runs anything and calls no model, so it spends nothing. Residual risk: a private subject's own name can be one of the four words (the keeper sees it and can decline); the directory rate limits per IP; its ranking is not a trust rating. What was consulted is one Story row and one Messages line.

### Repository operations

GitHub and GitLab operations are implemented in `RemoteRepositoryOperationClients.kt`: `status`, `create-branch:<branch>`, `open-pull-request:…`, and `open-merge-request:…`. Local Git operations (desktop) cover `status`, `fetch`, `pull`, `add-all`, `push`, `checkout`, `create-branch`, `worktree-branch`, `commit`, and `push-set-upstream`. See `docs/Repository-Services.md` and `docs/Local-Git-Repository-Operations.md`.

**Threats**
- An operation string is crafted to inject options or shell syntax.
- A mutating operation (branch, PR/MR, commit, push) runs without human review.
- Artifact content propagates into PR titles or commit messages.

**Mitigations in place**
- Operation strings are parsed into a fixed vocabulary. Remote operations are REST calls. Local operations are fixed `ProcessBuilder` argument lists, not shell commands.
- Branch names are validated: they cannot be empty, cannot start with `-`, and cannot contain NUL or newline characters.
- `WorkflowGraphValidator` rejects any `RepositoryOperation` task other than `status` or `fetch` that lacks a `HumanApproval` ancestor, including one placed on another device through `TaskExecutor.Distributed`.
- Running a finished run again from a task (`TaskRerunPlanner`) never reuses an earlier approval for a repository change that runs again: when no unconditional `HumanApproval` ancestor of the change runs again, its approvals run again too, and replacing an output behind such an approval is refused. The finished run is not reopened; the rerun is a new run whose carried results came from tasks that completed, and output the keeper wrote is marked `editedBy: keeper`.
- No remote operation merges, deletes, or force-pushes.
- Remote repository credentials are scoped to `github.com` and `gitlab.com`.
- Failures are normalized into failed tasks and follow the workflow's retry/escalation policy.

**Remaining gaps**
- Operations do not reject protected branches up front. They rely on the host's branch protection.
- Workspace agents (OpenCode, GitLab, Local) write branches without a `HumanApproval` task. Plan approval is optional per task.

### Context provenance

Every artifact a task receives is labelled with where it came from, in `ArtifactRef.metadata["aive.provenance"]` (`ContextProvenance` in `domain/ContextProvenance.kt`; the rule is in `workflow/ContextTaint.kt`). Prompt context blocks carry the same label (`PromptContextBlock.provenance`).

| Label | Given to |
|---|---|
| `KeeperWritten` | The objective the keeper typed, task and role text, the runtime's own control summaries, human-approval output |
| `AgentArtifact` | Output of an agent, GitHub Action, script or test runner |
| `RepositoryContent` | Repository-operation output; retrieved repository files |
| `ExternalContent` | Output of a role with the `Research` capability, a remote A2A agent (any `a2a:` provider, whatever the role), an answer written with MCP tool results in view, an external service, a deployment, or anything returned by another pool device. Also a run's objective that did not come from the keeper's own typing: a launch by another agent (A2A `SendMessage`, the Android remote service, the control API's `startRun`) or by a schedule that is fed model-written evidence |
| `MemoryRecall` | Blocks recalled from the memory layer |
| `Unknown` | Anything without a label, including artifacts stored before labelling. Treated as external. |

**Threats**
- Prompt injection in web research, service responses or pool results steers a task that can write the repository, deploy, call a service or run a script.
- Injected content is laundered through an intermediate agent whose output looks trusted.
- A provider or executor labels its own output as keeper-written.

**Mitigations in place**
- The runtime labels artifacts when it takes them in (`WorkflowEngine.reconcile`, system executor dispatch and reconcile, remote compute). The runtime's label replaces the producer's. A producer can only lower trust: its own `ExternalContent` label and its untrusted-input flag are kept.
- Taint propagates. Anything made by a task whose inputs include external or unknown content is flagged `aive.provenance.untrustedInput` and counts as external downstream.
- The run's objective is an input to every task, a task with no dependencies included. A run records where its objective came from (`WorkflowRun.objectiveProvenance`, keeper-written for runs saved before it existed); when that is not the keeper's own typing the objective counts as an external input, so every step that can change something waits at the gate, and what every task makes carries the flag. A run made from one (a rerun) keeps the mark; an objective the keeper types into the launch form or a schedule's own objective text stays keeper-written.
- Before a task runs, `WorkflowRuntimeCoordinator` checks it. If its executor can change something and any of its inputs are external, unknown or flagged, the task is held `AwaitingApproval` at a `SecurityRisk` gate (`taint:<run>:<task>:<attempt>`, human only). The gate names those inputs, and nothing is dispatched until a person approves. Rejecting fails the task without a retry. Executors that can change something: repository operations other than `status`/`fetch`, deployments, external services, GitHub Actions, scripts, test runners, and agents whose role needs `RepositoryWrite`, `PullRequestCreation` or `ShellExecution` (or whose role is unknown). Distributed placements are judged by their delegate.
- Keeper-written content, the project's own repository and clean agent output never trigger the gate. A launch by another agent on a hive whose steps cannot change anything (a read-only hive) is never held. The gate adds to invariant 4 and never replaces a `HumanApproval` task.
- The creature's Work tab lists each input with its label. Its Needs tab shows the gate's reason.

**Remaining gaps**
- Remote A2A Agent Cards contain untrusted operator-written names and descriptions. Connections validates the JSON-RPC binding before adding a provider, without turning card text into role instructions. Roles authored through the retired hive-edge flow may still contain bounded quotes from cards; those remain untrusted, and A2A providers have no repository authority or power to approve a plan, gate or release.
- Recalled memory, which can hold content from earlier runs, and role-surface data (including HTTPS spreadsheets) enter agent prompts after the check, so neither triggers the gate. Role-surface blocks are unlabelled.
- A gate inside a nested child run is named on the parent node but, like plan approvals, cannot be decided from it.
- Labels are not signed. A tampered store or imported bundle can relabel artifacts (see Persistence).
- Each new attempt opens a new gate, even when the inputs are unchanged.
### Progressive delegation

After the keeper approves the same role's plan gate on the same provider N times in a row (default 5, 2 to 20, set under the Keeper's room › Delegations), The Aive offers once to stop asking (`workflow/ProgressiveDelegation.kt`). Accepted, later gates of that kind approve themselves; each is recorded as an `ApprovalDecisionReceived` with `byDelegation = true` and shown as "Approved by delegation".

**Threats**
- Learned auto-approval lets a mutating action through without a person (invariant 4).
- A forged or imported delegation grants itself.
- A role or provider gains write access after it was delegated.

**Mitigations in place**
- One function, `DelegationPolicy.eligibility`, decides eligibility, and only an eligible gate yields a `DelegationKey` (its constructor is internal). Only plan approval (`DelegableGateKind`) can be delegated. Never eligible: `HumanApproval` tasks (including nested ones), repository operations, deployments, external services, executors that run code (GitHub Actions, scripts, test runners), nested and distributed work, roles with `Implement`, `AuthorTests`, `ApproveIntegration` or `ApproveRelease`, tasks exercising those, roles needing or providers offering `RepositoryWrite`, `ShellExecution`, `Testing`, `TestAuthoring` or `PullRequestCreation`, roles with a writable surface, providers whose capabilities are unknown, and gates raised by context taint. The authority and capability classifications are exhaustive `when`s, so a new value must be classified.
- Eligibility is checked again at every automatic approval; a role or provider that has since gained write access asks again. `ApprovalGateCoordinator.decide` refuses `byDelegation` for anything but an approved plan gate, and the runtime refuses it for `HumanApproval` tasks.
- The offer is explicit: Keep asking declines it for the rest of the streak. Any plan rejection for the role resets its streak and ends its delegation. Every delegation is listed under the Keeper's room › Delegations with Revoke.
- Delegations live only in this device's settings store. They are not in the setup file, `.ive` files or exports, so a file cannot grant one.

**Remaining gaps**
- The Aive has no context-taint signal yet; `GateDelegationContext.raisedByContextTaint` is the hook and is always false today.

### Nested workflows

`TaskExecutor.NestedWorkflow` and composition (`WorkflowComposer.nest` / `inline`) ship. Inlined children become ordinary namespaced tasks and are validated with the parent. Their repository-operation approval rule and cycle detection therefore apply.

Every platform runs `NestedWorkflow` nodes through `WorkflowRunNestedWorkflowClient`, registered in the shared `ApplicationRuntime`. Each node starts a child run of the referenced definition on the same engine and storage, and the child's result becomes the node's result.

**Threats**
- A workflow that nests itself, directly or through other workflows, starts child runs without end.
- Deep nesting multiplies work and storage.
- A child run could skip approvals the user expects to see.
- A retried dispatch or a restart could start a second child run.

**Mitigations in place**
- Nesting deeper than four levels (`DEFAULT_MAX_DEPTH`) is refused.
- Before a child starts, the nesting graph reachable from its definition is checked. The start is refused if the graph reaches the parent definition or contains any cycle, which also covers cycles through ancestors further up.
- Refusals fail the node without retry.
- `HumanApproval` tasks in a child run are ordinary gates that are never approved automatically. A pending gate is named on the parent node, and a person must approve it.
- The child run ID is derived from the parent task run and attempt and stored as the node's external run ID. A repeated dispatch or a resume reattaches to the same child run.

**Remaining gaps**
- Plan approvals and failure escalations inside a child run are named on the parent node but can't be decided from it.
- The child runs the stored definition without the preparation pass a direct launch applies.

### Distributed compute relay and executor

`TaskExecutor.Distributed` wraps another executor and sends it as a lease through a self-hosted relay (`computeRelay/`, protocol version 2) to a device in the same pool. Workers run only system executors (GitHub Actions, script, repository operation, and similar), using their own local integrations and credentials.

**Threats**
- Anyone holding the pool's shared token can enrol a new node, join the pool and read every lease envelope. An envelope carries the project, definition, run, task, and role.
- A rogue member can publish leases that try to make sharing devices run repository operations or dispatch GitHub Actions with those devices' credentials.
- A rogue member can try to act as another node (receive its replayed leases, cancel its leases, take credit for work).
- Any enrolled member can revoke other members.
- A worker can return fabricated results.
- The relay can be reached over plain `ws://`.

**Mitigations in place**
- Distributed compute has no default relay and is off until configured. Accepting new work also requires `sharingEnabled`, and can additionally require an unmetered network and external power. Android re-evaluates and republishes those conditions when network/power state changes, and its worker checks each offer against the same live descriptor.
- A node advertises only the executor kinds its credentials support. The relay offers leases only to nodes that satisfy the lease's requirements. The worker re-checks those requirements and a per-node parallel-lease cap.
- Per-node identity. The shared token (`Authorization: Bearer`, compared in constant time) only enrols a node's P-256 public key, with proof of possession. Every session opens with a request signed by that key: node ID, timestamp within 5 minutes, and a nonce the relay refuses to see twice. The session is bound to the signing node, and `register` must name it. Keys are generated in the Android Keystore (not exportable) or kept in the desktop OS credential store.
- An enrolled node ID cannot be taken over with another key, and a node ID held by a live connection cannot be registered again until that connection closes.
- Any enrolled node can revoke another from the Keeper's room. Revocation closes its session, cancels the leases it published, requeues the leases it held, and refuses its key and node ID from then on.
- Lease attribution comes from the authenticated session: progress, completion and the per-node work counts name the node that really ran the lease.
- The relay rejects mismatched protocol versions and caps frames at 8 MiB and enrolment bodies at 64 KiB.
- Lease duration (10 minutes), unfinished leases per origin (64) and leases per pool (512) are capped, and an unfinished lease is dropped 24 hours after the relay accepted it.
- Only a lease's origin can cancel it. Only the claiming worker can report its progress or completion. Expired claims are requeued. Workers continuously watch relay ownership while executing; cancellation, requeue/reassignment, or relay disconnect cancels the running coroutine rather than allowing stale work to continue.
- `HumanApproval` tasks are never delegated. Envelopes must carry the unwrapped executor and the matching role definition.
- Workers re-check every lease before running it (`SystemExecutorDistributedWorkloadRunner`): the submitted workflow must validate; the task must be a distributed placement of the requested executor; mutating repository operations are refused outright, because the approval state in an envelope is self-attested; and work that uses the worker's repository credentials (repository operations, GitHub Actions, GitHub-backed scripts) runs only against a repository of a project linked on the worker's own device. Anything else fails the lease as refused.
- Relay tokens are stored in the platform credential store.
- Origin disconnect does not destroy accepted work. The relay retains unfinished leases until they complete, are cancelled or reach the 24-hour cap; terminal results have a 24-hour replay window. When `AIVE_RELAY_STATE_DIR` is configured, those lease records survive relay-process restarts in AES-256-GCM-encrypted files keyed from the relay bearer secret. Expired terminal records are removed when an active/persisted pool is swept or loaded; an untouched encrypted pool snapshot can remain until it is accessed or explicitly removed by the operator.

**Remaining gaps**
- There is still a single shared enrolment token per relay. Any holder can enrol a new node and join; revocation stops a key and node ID, not the token, so a revoked device that may still know the token has to be shut out by rotating it. Any enrolled node can revoke others (a rogue member can disrupt, and honest members can revoke it back). Without `AIVE_RELAY_STATE_DIR`, enrolments and revocations are lost when the relay restarts. Desktop keys fall back to plaintext preferences when no OS credential store works. The same token also derives the optional persisted-state encryption key, so token rotation requires draining/clearing or explicitly migrating saved relay state (an unreadable node record starts the pool with no enrolments; nodes enrol again under the new token).
- Signed requests authenticate the session, not each frame; frames inside the session rely on the transport (see `wss://` below).
- The Play build hides distributed compute as a store-policy choice; it is not a mitigation and per-node identity does not change it.
- Envelopes are not end-to-end encrypted in transit through the relay, so the relay operator sees them while processing. Optional process-restart persistence encrypts lease files at rest with AES-256-GCM and opaque pool filenames, but the relay process itself necessarily holds plaintext while routing work.
- A pool member can forge a run that claims its approval gate completed. Without signed approvals the worker cannot verify that, so remote workers run only read-only repository operations (`status`, `fetch`); a mutation placed on another device fails there and has to run on the origin device.
- Transport security depends on the relay URL. `wss://` is suggested but not enforced, and the bundled server has no TLS of its own.

### Attached spreadsheet and SQL surfaces (role surfaces)

**Threats**
- A role can expose sensitive spreadsheet/database rows to a local or remote execution source.
- Script-returned mutations can corrupt user-owned data if a surface is made writable.
- Repeated remote-run reconciliation can replay non-idempotent writes.
- Imported SQLite documents and network spreadsheets can be oversized or malformed.

**Mitigations in place**
- Surfaces are opt-in per role and named explicitly in the Roster editor.
- HTTPS is required for network spreadsheet sources.
- Snapshot row counts and spreadsheet byte sizes are bounded.
- Only app-owned SQLite databases may execute returned SQL mutations. Imported SQLite documents are query-only.
- Inline and HTTPS spreadsheets are read-only. Document spreadsheets require a writable URI before writes can succeed.
- Flowchart surfaces are read-only and parsed by The Aive rather than executing browser content.
- Surface mutations are accepted only for declared writable aliases and are keyed by task run/attempt/index to suppress ordinary reconciliation replay.

### Crash relay (Android GitHub flavor)

**Threats**
- Sensitive data leaks into a public issue through an exception message or ANR trace.
- The relay endpoint is abused to spam the issue tracker.
- Reporting happens without consent.

**Mitigations in place**
- Reporting exists only in the GitHub flavor and is disclosed in `docs/PRIVACY.md`. It is opt-in and off by default (`CrashReportPolicy.DEFAULT_ENABLED = false`); turning it off in Settings deletes pending reports.
- The payload is fixed: device and app metadata, plus a stack trace truncated to 38,000 characters. No workflow content or credentials are added.
- Historic ANRs are not backfilled on first enable.
- The app holds no GitHub credential. The relay files issues with its own server-side token. The shipped relay key is only a spam filter.
- Each failure signature is sent at most once per app version. Undeliverable reports are dropped after 7 days, and 4xx responses other than 429 are not retried.

**Residual risks**
- Exception messages can carry fragments of in-flight data. For example, HTTP error bodies of up to 500 characters are included in some error messages.
- The relay key is public, so anyone can post to the relay.

### Installed-Gemini accessibility bridge (Android GitHub flavor, opt-in)

**Threats**
- An accessibility service reads screen content from other apps.
- The service acts outside an explicit handoff.
- The Play build ships the service in violation of Play policy.
- Clipboard use exposes the prompt or reply to other apps.

**Mitigations in place**
- The service exists only in the `github` source set. Play builds get a stub transport and a disclosure that returns immediately.
- Enabling the bridge requires an in-app prominent disclosure and the user's agreement, and then the user must turn the service on in Android Accessibility settings. At runtime the service also requires the in-app opt-in.
- The service is declared for `com.google.android.apps.bard` and `com.google.android.googlequicksearchbox` only. It ignores events from any other package and acts only while a handoff is pending (`InstalledGeminiBridge.waiting`). Handoffs are serialized and time out after 120 seconds.
- CI step "Verify Play build excludes accessibility bridge" fails the build if the merged Play manifest contains `InstalledGeminiAccessibilityService`, `BIND_ACCESSIBILITY_SERVICE`, or the GitHub self-update components. A companion step verifies that the GitHub manifest keeps them.

**Residual risks**
- The paste fallback and copy-button capture pass the prompt and reply through the system clipboard.
- Screen scraping is heuristic, so a UI change in Gemini can capture the wrong text.

### Updates and packages

**Mitigations in place**
- The app never downloads or installs an APK itself. The GitHub-flavor check reads the public GitHub releases feed and, on request, opens the release page in the browser; Android's own installer then enforces that an update is signed with the same key. The GitHub build declares neither `REQUEST_INSTALL_PACKAGES` nor an update `FileProvider`, and CI fails if either appears in a manifest.
- Each build updates only from its own channel (the Play build and the GitHub build are different packages). A Play build asks Play's in-app update API, and only when Play installed it; a Play build installed any other way makes no update check.
- The release page URL comes from the feed but is opened only if it is on `https://github.com/`; anything else falls back to the releases list.
- Azphalt packages require HTTPS repositories, Ed25519 signatures against repository signing keys, SHA-256 checksums for model assets, and a revocation check. If revocation status is unavailable, installation requires explicit offline/untrusted approval.

**Remaining gaps**
- Azphalt trust keys come from the repository's own index, so a malicious custom repository can vouch for its own packages; revocations match package id plus version rather than key or digest. Installing from a custom repository is an explicit user action.
- The GitHub release feed is unauthenticated and rate-limited; a failed check is shown as no update, not as an error.

### A2A (Agent2Agent) client and server

The Aive delegates a role's task to a remote A2A agent, and on desktop can accept tasks from local agents. Protocol and behavior: `docs/A2A.md`.

**Assets**
- A remote agent's optional bearer token (platform credential store, id `a2a:<host>`).
- The desktop server's bearer token (OS keychain, or memory only when there is none). Whoever holds it can start workflows on the user's linked providers. It is never logged, shown on screen or placed in the Agent Card; **Copy token** is the only way out of the app.

**Threats**
- A malicious or compromised Agent Card points the JSON-RPC endpoint (and so the prompts and token) at another host.
- A remote agent returns prompt-injection in its artifacts, or claims success it did not reach.
- A web page in the user's browser reaches the loopback server (CSRF, DNS rebinding).
- A local process or agent holding the token floods The Aive with launches, or replaces the user's running work.
- Because the server is on by default, any local process can reach the port and read the public Agent Card without the keeper having done anything.
- Open to the network: another computer on the network sniffs the token from cleartext traffic and replays it; scans the port; spoofs or takes over an allowed computer's address; floods `SendMessage`; or reaches the server through a DNS name it controls.
- A remote agent is used for confidential work it should not see.
- The public Agent Card tells a reader which kinds of provider are linked here and which provider each role uses (information disclosure).
- A pasted hive card is untrusted input: oversized or deeply nested JSON, duplicate ids, cycles, too many creatures, hostile ids or text, or a card that tries to bring code, a provider connection or a fetch with it.

**Mitigations in place**
- Remote agents are `explicitOnly`: they run only for roles that prefer them or tasks that require them by id, never by automatic routing.
- Card and endpoint URLs must pass `CleartextPolicy`. The endpoint must share the card's origin whether or not a token is set (the prompts are what it guards). Redirects are not followed, and the card is fetched without the token. A card is read to at most 256 KB and an RPC answer to 4 MB, counted in bytes while it streams in.
- A remote task completes only when a `GetTask` poll reads `completed` (or the agent answers with a final message). `unknown` and unrecognized states are never success. Artifacts are stored as opaque text and links; inline file bytes are dropped.
- The server is on by default on desktop, but only on loopback: other machines cannot reach it. A local process needs the token, which only the keeper hands out (**Copy token**), so default-on adds a listening port, not a client. The keeper can turn it off, and that choice is kept. It refuses requests whose `Host` is not a loopback name. It sends no CORS headers, so a browser cannot attach the `Authorization` header cross-origin. Every JSON-RPC call needs the bearer token, compared in constant time; the card is public, as A2A discovery expects. Bodies are capped at 1 MB and objectives at 20,000 characters. Who is asking (network, `Host`, token, allowed address) is judged from the headers before the body is read, so a caller that would be refused costs only its headers; a declared length over the cap is refused unread, and the JDK server's request timer (15 s) drops a body that dribbles in.
- Opening to the network is a separate switch, off by default and never turned on by enabling the server. While it is off the server binds loopback and refuses every non-loopback caller, card included. While it is on, a network caller needs the token and an allowlisted IP address; one with the token but not listed is refused and shown to the keeper to allow, and one without the token is never listed. The `Host` check accepts only loopback names and this computer's own addresses as IP literals, so DNS rebinding still fails. `SendMessage` is limited to 6 calls a minute per client address (HTTP `429`). The Agent Card names a network URL only to callers that reached it on a network address. The cleartext warning is shown beside the switch at all times.
- Provider description (`docs/A2A.md`, "Describing providers"): the card lists a linked provider's id, shown name and capability names only, never a key, token, base URL, account, model or balance (the data comes from `AgentProviderRegistry` through `a2aProviderInfos`, which reads nothing else). A remote A2A agent shows as a hash, not its host. Counts and lengths are capped and text is one sanitized line. Rule: with no explicit choice, providers are described while the server is loopback only and left out as soon as it is open to the network; an explicit switch (on or off) always wins.
- Hive cards (`docs/A2A.md`, "Hive cards"): an import reads a bounded (256 KB, depth 16, 12 creatures) record, accepts only version 1, sanitizes every id and text, builds a NEW hive, takes each creature's executor from this device's own role (never from the card), leaves out any step that runs code or touches a repository, never connects a provider or fetches a URL, and runs the result through `WorkflowGraphValidator`. Approval gates are recreated as plain human approvals only. An export holds no secret, path, memory or prompt, and omits objectives unless asked.
- The server never approves a plan, task or gate, picks a provider or touches a repository. It only queues a launch through the same path as the launch form, and the run's objective is marked as external content (context provenance), so a step that can change something waits at a context-taint gate. It refuses a launch while a run is unfinished or another launch is queued, so it cannot replace the user's work. Cancel reaches only the run its own task started.

**Residual risks**
- Anyone with the server token is a full client until the token is reset; there is no per-client identity or spend cap.
- On by default, every desktop install has a loopback listener. A local process without the token can read the Agent Card (version, enabled role names) and can hold the port so the server fails to start; a process that already runs as the user can read the keychain item anyway.
- Without an OS keychain the server token is regenerated on every start and held only in memory.
- Open to the network, traffic is plain HTTP: the token, objectives, status and artifacts can be read by anyone on the network path, and a sniffed token can be replayed from an allowed address. TLS is not offered. The keeper is told beside the switch; **New token** recovers.
- The allowlist is keyed by IP address. On a network with DHCP churn, shared NAT or an attacker who can take over an address (ARP spoofing), an allowlisted address is not one identity. Combined with the cleartext token, a capable attacker on the same network can launch workflows. Launches still wait at every gate for the person at The Aive (invariant 4 unchanged), cannot replace an unfinished run, and are rate-limited.
- Anyone on the network can read the Agent Card (version, enabled role names) and probe the port while the switch is on. Card reads are not rate-limited.
- Remote A2A agents see the task content they are sent; whether they retain it is up to their operator.
- Describing providers is a small disclosure: anyone who can read the card learns which provider kinds are linked and which roles use them. It is on by default only while loopback only (readable by local processes, which can already find the port) and off by default on the network; a keeper who switches it on explicitly accepts it there.
- A hive card shared by a keeper carries role and provider ids, strands and, if asked, one-line objectives, which can reveal what the work is about; the keeper chooses to send it and objectives are off by default. An imported card can still name roles and a hive shape the keeper did not intend; it needs a keeper to create and launch it, and every gate stays.

### Control API and MCP bridge (desktop, opt-in)

External agents (Claude Desktop, Claude Code, any MCP client) can read and steer a running desktop app through a standalone bridge process, `mcpBridge`, which speaks MCP over stdio and calls the app's control API. Behavior and tool list: `docs/MCP.md`.

**Assets**
- The control token (a file in `~/.aive/control`, readable only by the user). Whoever holds it can read hives, runs and logs, edit authored hives and start or stop runs on the user's linked providers.
- Run text (objectives, progress, artifacts, logs), which can hold anything a provider produced.

**Threats**
- A web page in the user's browser reaches the loopback server (CSRF, DNS rebinding).
- A local process without the token probes the port; one with the token acts as a full client.
- An external agent tries to approve its own work: by an approve endpoint, by a tool, or by removing or relaxing a gate in a hive and then running it.
- An external agent authors a hive that changes a repository, runs a script or deploys, and starts it.
- A key or the token itself is carried out in a result (a pasted log, an error).
- Objective text from an agent is prompt injection for the creatures that read it.

**Mitigations in place**
- Off by default. It is turned on with `AIVE_CONTROL_API=on` or the preference `enabled`, and listens only on `127.0.0.1` (default port 41242). It refuses a caller that is not loopback, any request with an `Origin` header, and any `Host` that is not a loopback name. It sends no CORS headers.
- Every call needs `Authorization: Bearer <token>`, compared in constant time. The token is 32 random bytes made once per install, written to a file created with mode 0600 in a 0700 folder. It is never logged, shown, placed in `control.json` (the discovery file, which holds only the address and the token file path), in a result, or in the docs. Every answer is scrubbed of the token and of the common key shapes (`ControlRedaction`, the one redactor the whole app uses: provider error bodies, CLI output and patches go through it too; an unterminated private-key block is cut at its own end, not at the end of the text). The bridge reads the token per call, sends it only to a loopback address, and refuses any other.
- **No approval is reachable.** The route table has no route that approves, rejects, releases or delegates a gate, lifts a spend stop, or reads a key store, and the host interface has no member that could. `GET /approvals` lists what waits, read-only. Tests enumerate the route table and the tool list, hit every route and method plus approval-shaped paths, check that a pending gate stays pending, and scan the control sources for calls that decide a gate (`ControlApprovalBoundaryTest`, `RuntimeControlHostTest`, `ControlApiTest`, `BridgeAgainstControlApiTest`).
- **Invariant 4 holds.** A hive can be authored over this interface from only two kinds of creature: an agent working as a switched-on role, and a human-approval gate. Scripts, deployments, GitHub actions, nested workflows and repository operations cannot be authored, and the fields that would carry an executor are refused. A role whose capabilities include repository write, pull-request creation or shell execution is refused for a creature, whether it is added, created or swapped in; those hives are made in the app. Every save runs `WorkflowGraphValidator`, so a repository mutation already in a hive keeps its human-approval ancestor. A creature that waits for a person, or whose approval is human, cannot be removed or relaxed here; a creature that is not an agent or a gate cannot be changed here. No edit made here may move a creature out from behind a creature that waits for a person (by changing its dependencies, or by removing a creature that links it to one), and a human-approval gate may be made to wait on more but not on less. The validator itself knows only repository operations, not roles, so the order of an agent that writes, in a hive made in the app, is protected from this interface by those two rules and not by the validator.
- Only hives made in the app (the authored list) can be changed or deleted, never while a run of them is live, and never one that came from a package or is used as a circuit by another hive.
- Starting a run uses the same launch path as the launch form, only for a saved hive, and is refused while a run is unfinished. A run started this way waits at every gate for the person at the app. Stopping a run cancels it like a replaced run; it decides nothing.
- Role provider assignment uses the same save as the Roster's picker (`preferredProviderId`), and only to a provider that is linked. Provider listings show connection state and the kind of failure, never a key or a provider's own message.
- Bodies are capped at 256 KB, strings in answers at 4,000 characters. Loopback, `Origin`, `Host` and the token are checked from the headers before the body is read; a declared length over the cap is refused unread, and the JDK server's request timer (15 s) drops a body that dribbles in.

**Residual risks**
- Anyone holding the token is a full client until the file is deleted and the app restarted; there is no per-client identity, rate limit or spend cap. A process that already runs as the user can read the token file.
- An objective, hive or creature text written by an agent is external content to the creatures that read it. A run started here is marked so (its objective is `ExternalContent`), and invariant 7 applies: a creature that can change something waits at a context-taint gate for a person, even one with no upstream creature. Creature text written here is not marked: a hive's own task text is read as the keeper's, so an agent that authors a creature's objective is steering it directly; the gate and the role restriction above, not the label, are what limit that. Authored hives are not scanned for injected instructions.
- An agent can add creatures with a role that has broad authorities, and start a run that spends the user's provider credit. Each run still waits at its gates, and spend caps still stop it.
- The pause, resume, spend-stop and Hall Monitor controls are not offered over this interface.

### Remote access and relay (Android)

The Aive can be driven from IDEaz on another device through a relay Worker (`relay/`), or by IDEaz on the same device through the bound service. Protocol: `docs/Remote-API.md`.

**Assets**
- The pairing code (`aive1:...`). It carries the relay room, the client token and the 256-bit end-to-end key, so it is a bearer secret: whoever holds it can run prompts on the user's linked provider keys, queue workflows and read the shown run's events (task changes, gate reasons, plan and artifact previews).
- The room host token (never leaves the device) and the linked provider/GitHub credentials.

**Threats**
- A relay operator or network observer reads or alters traffic. Frames are sealed end to end (AES-256-GCM, room-bound AAD, per-session sequence numbers and timestamps), so the relay sees only sizes, timing and IP addresses and cannot forge a frame. It can drop frames or disconnect clients.
- A captured sealed frame is replayed. The host keeps one replay guard for the life of the link, shared across reconnects, and rejects any sequence number it has seen and any timestamp more than 10 minutes from its clock.
- The pairing code leaks (share sheet, clipboard, screenshot).
- Someone floods the relay with rooms or frames.
- A different app binds the local service or impersonates a client.

**Mitigations in place**
- The bound service is protected by a signature-level permission; both builds must be signed by the same key. IDEaz additionally checks that the Aive package's signing certificate matches its own before sending anything.
- The relay stores only SHA-256 hashes of the host and client tokens; a client token never authenticates as host; a room expires after 30 days without its host; at most 8 clients per room.
- The relay meters room creation per client address, closes any socket that exceeds a frame-rate token bucket, and caps a frame at 1 MB.
- Reset pairing, or turning Remote access off, deletes the room and makes the old code useless. The copied pairing code is cleared from the clipboard after 60 seconds. The pairing credentials are excluded from backups.
- Pairing and relay use are opt-in; the host runs as a foreground service with a visible notification.
- Run events are read-only: no remote message approves or rejects a gate. Previews are capped (4 KB), artifact URIs are not sent, and tasks that hold their output send no preview.

**Residual risks**
- Anyone with the pairing code is a full client until the pairing is reset: there is no per-client enrolment, spend cap or revocation of a single device.
- The replay guard is in memory, so a frame captured shortly before the app restarts can be replayed once after it within the 10-minute window.
- A relay operator can drop or delay frames (denial of service) and sees connection metadata.
- The relay's shared client-token model means a leaked code cannot be told apart from its owner.

### Trace export (OpenTelemetry)

**Threats**
- Prompts, outputs or secrets in them leak to a collector, or to whoever operates it.
- Traces are sent without consent, or history is backfilled when export is turned on.
- Collector headers (API keys) leak, or traces go over cleartext.
- A slow or failing collector stalls the workflow engine.

**Mitigations in place**
- Off by default (`TraceExportSettings.enabled = false`) and disclosed in `docs/PRIVACY.md`. There is no default collector.
- Content (objective, artifact names and text, failure, approval and escalation reasons) is excluded unless a second, separate toggle is on; the builder never reads it otherwise (`TraceContentPolicy`). Executors are exported by kind only, never a script's source.
- Runs that ended before export was turned on are never sent; each run is sent at most once.
- `https://` is required, except for a loopback collector. URLs with user info, a query or a fragment are refused. Credentials never enter workflow state or spans.
- Export reads the persisted journal after a run ends, in its own coroutine; the queue is bounded, never suspends the caller and drops the oldest spans when full. Failed batches are retried with backoff and then dropped.
- The settings key is not in the setup-file allowlist, so headers are never exported in a `.aivesetup` file.

**Residual risks**
- Collector headers are stored in the app's local settings, not the platform credential store. Users are told to use a trace-only ingest key.
- Ids exported by default (project, run, task, role, workflow definition, provider session) and token counts can still reveal activity patterns to the collector.

### Persistence

**Threats**
- Malicious import: an exported run bundle contains crafted events that corrupt workflow state or inject false history.
- Forged approvals: a crafted `.ive` project file carries a workflow that skips `HumanApproval`, a run whose approval task is already `Completed`, pre-approved gates, or a `Ready` repository operation. The app imports a detected `.ive` file on first launch and opens its latest run.
- Credential leakage: provider tokens stored in Settings are included in exported bundles.

**Mitigations in place**
- Credentials are stored separately from workflow persistence and are never serialized into run state or exported bundles.
  - **Android:** AES-GCM with an Android Keystore key; ciphertext in SharedPreferences.
  - **Desktop:** macOS Keychain, Linux Secret Service, or Windows DPAPI. Storage is refused when none is available. The OS tools run as fixed argument lists (never a shell string) with a 20-second bound and stderr discarded, so a locked keyring cannot hang the app; account ids that are not plain catalog-style ids are never passed to them.
- Schema migrations validate field types and reject unknown executor kinds.
- `.ive` project files are untrusted input (`decodeProjectFile`, used by every backend). Every workflow definition in the file must pass `WorkflowGraphValidator`, or the import is refused and nothing is written. Imported runs arrive as history: a run that had not finished is imported `Cancelled` (unfinished tasks cancelled, finished ones kept), and every pending or applying gate is imported `Rejected`. Nothing from the file resumes or executes on its own, including after the first-launch auto-import; continuing the work takes a new run that a person starts and approves.

**Remaining gaps**
- Imported run bundles are not cryptographically signed. A tampered bundle is accepted if it passes schema validation.
- An imported `.ive` file's history (finished runs, their approved gates and artifacts) is shown as the file states it; it is not evidence that those approvals happened.
- Whole-store JSON restore (`importJson`) is treated as the user's own backup and is not re-validated.
- The web build encrypts credentials in `localStorage` with a non-extractable AES-GCM key held in IndexedDB (`credential-vault.js`). That defeats copied or dumped storage, not script running on the origin, which can still ask the key to decrypt. Browsers without Web Crypto or IndexedDB (for example, plain-http origins) fall back to plaintext.

## Invariants

The following invariants must hold regardless of the integration used:

1. A task moves to `Completed` only when the executor integration explicitly returns `Completed` after polling the external system. No task self-completes on dispatch.
2. Provider credentials never appear in workflow state, audit events, or exported bundles.
3. The Aive runtime never executes artifact content as code or instructions. Remote agents with shell access (OpenCode) may act on it, and that risk is covered as a residual risk above.
4. Every mutating `RepositoryOperation` task (anything other than `status`/`fetch`) has a `HumanApproval` ancestor in the workflow DAG. Workspace agents write only to new dedicated branches.
5. Provider payloads are subject to `PayloadRedactionPolicy` before transmission.
6. Dispatch-to-run-ID correlation is API-returned. The one exception is when OpenCode resumes after a restart: it matches the unique per-run `run-name` among up to 1,000 recent dispatches of its app-managed workflow.
7. A task whose executor can change something does not run on external, unknown-provenance or externally derived inputs until a person approves a gate that names those inputs. An artifact without a provenance label counts as external, and so does a run's objective that did not come from the keeper's own typing.

Invariant 4, together with the gate-decider, approval-policy and add-on permission checks, is decided by the in-process policy decision point ([`architecture/POLICY.md`](architecture/POLICY.md)). Its denials and approval requirements are recorded on the run timeline. A policy rule may add a gate but must never relax an invariant.
7. An agent's question to the keeper is drawn only from the host catalog in `AgentUi`; anything outside it is shown as plain text, and no part of it is executed, fetched or rendered as markup.
8. No remote or local-agent interface (MCP bridge, control API, A2A server, headless control commands) can approve, reject or release a gate, lift a spend stop, or remove a human approval. Those decisions are made only inside the application runtime, by the keeper. The independent headless runtime's local attached keeper console is an in-process human-decision surface, not a remote API; it requires an exact confirmation after displaying task details.
9. The plan reviewer (`orchestration/PlanReview.kt`) is advisory. It reads a plan before a run exists and returns words; it decides no approval gate, relaxes no invariant, and the reworked plan passes `OrchestrationPlan.validateAgainst` and the workflow graph validator like any plan, so invariant 4 still holds for it. The reviewer's text and the plan are model text (the reviewer reads step objectives a model wrote; the planner reads the reviewer's objections back): both are shown as plain text only, and a plan reaches a run only when the keeper starts it in the app (invariant 8). A reviewed plan left undecided is kept on the device (`ReviewedPlanStore`) so a restart does not lose it; the store sits outside workflow persistence, the setup file and every export (so an import cannot forge it and no remote interface can read or write it), holds no approval, and is dropped if anything the plan was made from has changed (for a saved hive's rough design, the design itself). A saved hive is planned only from the hive's own Launch in the app; the control API and MCP `startRun` path stay unplanned, as invariant 8 requires, and the planned run is saved under a new id so the saved design is never overwritten. Starting it is still the keeper's Start in the app.

## Open Items

- Automated prompt-injection scanning of artifact content before use as provider context.
- Cryptographic signing of exported run bundles.
- Verification that a dispatched `workflow_run_id` matches the expected workflow file name.
- Per-session provider response content hashing to detect replay.
- Distributed compute: end-to-end envelope encryption, enforced `wss://`, signed approvals so workers can verify gates rather than trust the submitted run, and an enrolment step that the shared token alone cannot pass (for example, approval of new nodes by an enrolled one).
- Block CI configuration files (for example `.gitlab-ci.yml`, `.github/workflows/`) in workspace-agent change sets.
- Deciding plan approvals and failure escalations inside nested child runs from the parent node.
- Web credential protection against same-origin script (for example, a passphrase-derived key).
- Trace export headers in the platform credential store instead of app settings.

### Accepted risks

- The workflows under `.github/workflows/` consume the shared `HereLiesAz/workflows` actions (four-part-version, ci-report, patch-grouped-release) at `@main`. The owner made this deliberate decision because both repositories share one owner, so repinning them is the owner's call; it is recorded here as accepted, not as an item to fix. Third-party actions and the two vendored checkouts are pinned to full commit SHAs (see the README).
- The crash relay key ships in the APK. It is a spam filter, not an authentication secret.

## Scheduled launches

A schedule (`workflow/schedule/`) starts a saved hive by itself while the app is open. It adds no authority: it goes through the same `launchSavedWorkflow` as a keeper's launch, as drawn, with no planner and no plan to confirm, and it never approves, so invariant 4 holds (a repository change still waits behind a person's approval step, which stops the run and holds every later schedule behind it). A schedule is off until the keeper turns it on; only a keeper edits one (the Keeper's room); it names a hive and an objective, not a command; and each decision is written in the app log. The coding suite's scheduled hives contain no approval, implementation or test-authoring step, a property `CodingWorkflowSuiteTest` enforces. A schedule is bound to a hive by id, so it also stores a fingerprint of the definition and holds the run, turning itself off, when the hive differs at a start (a definition replaced through an import, a package update, an adopted improvement or a control-API edit no longer runs unattended under a schedule the keeper made for something else); the keeper's turning it on again is the review. A hive with the film executors is refused (it spends money). A start is claimed in one atomic step before it is launched, and the ticker never writes back a stale copy, so a slow launch cannot resurrect a schedule the keeper turned off; a stored list that cannot be read is kept and never overwritten. Residual risk: an edited copy of a suite hive, put on a schedule and turned on by the keeper, runs whatever the keeper put in it, unattended, with the open project's repository and the connected agents' reach; the fingerprint covers the definition, not the roles it names; two app instances sharing storage are not serialised against each other.

## Self-improvement

The self-improvement cycle (`workflow/improvement/`, [SELF_IMPROVEMENT.md](architecture/SELF_IMPROVEMENT.md)) is a read-only hive that proposes and nothing else. Its steps hold no approval, repository, script, deployment, action, external-service, nested-workflow or code-changing step, none escalates to a person or another role (a step that cannot finish fails the run, so the loop never holds a scheduled start or a remote launch up), and no role it uses can write, run commands or release (`LoopIntegrity`, `SelfImprovementClauseTest`). That is enforced where the system acts on the loop and not only in the shipped copy: adopting a proposal that would leave the loop breaking those rules is refused, and a schedule does not start a loop hive that no longer keeps them (a keeper's own edit of it is never refused). The check is on structure and on the proposal line forms the parser reads; it cannot check what a model does with its instructions. What it writes is text from a model, so it is untrusted (invariant 3): proposal lines are parsed deterministically (exactly seven fields; a line with another number is counted as malformed, never read with its fields shifted), bounded, stripped of control characters, shown with their evidence and exact text (a step proposal beside the objective it replaces), and change a role, hive or schedule only when the keeper adopts that proposal in the app (invariants 4 and 8; no remote interface adopts one). Adoption records the text before and after so it can be reverted, and a revert is refused if the text changed since. A decision runs once (a second tap on a decided proposal changes nothing), reads the role or hive as it is when the button is pressed, and is recorded only after the change was saved; the ledger never forgets an adopted proposal, so a revert stays possible. What adoption adds to a role's instructions is capped, so a prompt cannot be grown without limit one proposal at a time. **Run a cycle now** starts one cycle and does not turn the schedule on. The evidence it is given is counts computed on the device, plus short quotations of earlier proposals (their summaries, targets and promised effects) so it does not repeat what the keeper turned down. Those quotations are model text: each is one line, bounded, free of control characters and fence marks, and inside a marked fence the loop is told to read as data; a scheduled launch fed this evidence has an objective marked as external content. The amendment clause is a property the system keeps, not a restriction on the keeper: nothing may lock, exempt or hide the loop or any of its parts from review, and a proposal about the loop is applied by the same code as any other. Residual risks: a persuasive but wrong proposal the keeper adopts without reading (mitigated by two adversarial steps, the exact text, the measure and the undo); the loop proposing a change to its own evidence that makes its failures invisible (the Antagonist and the keeper are the check, and the next cycle compares the numbers before and after); a `META-NO-CHANGE` that is a bare gesture, so the loop appears to have examined itself (it counts only as a sentence that cites a number from the evidence, and the next cycle is told what it said as a finding to re-examine, but a model can still write a plausible one).

## Local CLI agents (desktop)

A vendor LLM CLI (`claude`, `codex`, `gemini`, `qwen`) can be driven headless as an agent provider (`CliAgentProvider`, [Local-CLI-Agents.md](Local-CLI-Agents.md)). It is the most powerful agent the app runs: a program on the keeper's computer, running as the keeper, with its own login, tools and network. What the app does about it: off until the keeper turns it on; explicit only (no Auto routing); it runs only after a person approved a plan (the engine's plan gate; without the approved plan the task fails); **the approval shows the whole task the CLI is told** (the full objective, role and criteria, and for each context block its label, provenance, size and a SHA-256 prefix; context is labelled as data and its labels are one escaped line each, so a label cannot forge a section); it runs in a fresh git worktree of `HEAD` on a new branch, never in the keeper's checkout; **a checkout that tracks the CLI's own project settings (`.claude/settings*`, `.mcp.json`, `.gemini/`, `.qwen/`, `.codex/`) is refused unless the keeper switched on that CLI's "repository settings" (off by default), as is one that tracks a symbolic link pointing outside the worktree (and a link the CLI makes fails the task)**; its environment is an allowlist (no `GIT_*`, no repository-host token, no vendor API-key variable unless the keeper chose to pass that CLI's own); no flag that skips a CLI's permission checks or lifts its sandbox is ever passed (`CliAgentSpecs.FORBIDDEN_FLAGS` and `violation`, tested over every spec and mode and checked again before each start, for `--option=value` and `--option value` alike); a read-only task runs read-only and discards what it leaves; edits are committed with hooks off to the new branch and nothing is merged, pushed or released (invariant 4 is unaffected: no repository operation is performed); a change to Git's control files fails the task (compared without regard to case, at any depth); timeout, whole-tree kill on cancel, worktree and branch cleanup; stdin carries the prompt for every CLI (Gemini and Qwen get a fixed sentence after `-p` and the task on stdin, so it is not in the process list or limited by the command line); on Windows a `.cmd`/`.bat` shim, which `cmd.exe` would re-parse, is started only with arguments free of shell metacharacters; output and the change artifact are bounded, scrubbed of key-shaped strings (`ControlRedaction`) and treated as untrusted text. Listing the Local agents screen only looks for programs; `--version` runs only for a CLI the keeper turned on or tested. The `Thinking` events a CLI streams are shown live and not kept.

Residual risks the app cannot remove: the CLI can read anything the keeper can read, outside the worktree, and reach the network with its own tools (the app does not set Codex's network switch, so commands inside its sandbox reach the network exactly when the keeper's own Codex configuration lets them; Claude Code and Gemini rely on tool allow-lists, and a repository's settings that would widen them are refused as above, unless allowed); **prompt injection from context** (artifacts, repository files) can steer the CLI within those limits, which is the reason for the approved plan and for labelling context as data; the CLI's own configuration (home-folder settings, memory files, MCP servers) and any project instruction file it reads (`CLAUDE.md`, `GEMINI.md`, `AGENTS.md`) are outside the app's view; a symlink check is made on the checked-out worktree at start, not a guarantee against a CLI that races it; a CLI that auto-updates or phones home does so on its own; invariant 3 ("the runtime never executes artifact content as code or instructions") holds for the app, not for a CLI that acts on what it is given. Codex is the only one of these that can run commands (inside its sandbox); the others edit files only, so the statement that the app runs no repository code holds for them and not for Codex. A keeper who cannot accept these leaves the CLIs off.

## Short film (desktop)

The short-film hive ([Short-Film.md](Short-Film.md)) turns model text into image-model requests and an ffmpeg run. It changes no repository (invariant 4 is untouched), approves nothing, and ends at a person's approval. Its two executors exist only in the desktop app and only for this computer (not in the pool's base set); where they are absent the engine blocks the step.

**Untrusted storyboard text as image prompts.** The storyboard, the style sheet and the frame review are written by models that read the keeper's objective, so they are untrusted (invariant 3). `parseStoryboard` bounds them (60 shots, 300 s, 1500 characters a prompt, 200,000 characters read), strips control characters, refuses an unsafe id and never throws. A shot's prompt goes only into the image request, under a `SHOT` heading below instructions that say it is a description and not an instruction; narration is never used. Residual risk: a prompt can still steer the image model within its own limits (a picture the keeper did not want, or text in it); the frame review and your approval are the checks, and the image provider's own content policy applies. The prompt text leaves the device for the image provider.

**API spend.** One image costs money, a storyboard decides how many, and a model wrote it. So: an image budget per workflow run (`FILM_IMAGE_BUDGET_DEFAULT`, 40; raised only by the keeper, through `AIVE_FILM_IMAGE_BUDGET`, never by anything in the workflow), every attempt counted, a storyboard that needs more than is left refused before anything is drawn, three frames at a time, two retries a shot, no automatic retry of the step (`retryDispatchFailures = false`), kept frames reused at no cost, and no schedule. The count is kept in the frame store before each picture is asked for, so a restart does not reset it, and a `regenerate-shots` line is acted on once per run. Residual risks: a run started again from a task is a new workflow run with a new count (a keeper pressing that repeatedly spends repeatedly); the budget counts calls, not what the provider charges; the OpenAI connection is the same one the node-character pipeline uses.

**ffmpeg arguments and paths.** The command is an argument list for `ProcessCliRunner`, never a shell string (`FfmpegFilmPlan`, `FfmpegFilmPlanTest`). The filter graph is built only from constants and integers: no storyboard text, id or path is in it. A frame path is one argument after `-i`, is checked again (absolute, no `..`, no control character, not an option), and comes only from `FileFilmFrameStore.verifiedFrame`, which accepts nothing but this film's folder and shot id under `~/.aive/film/frames`, refuses a link, a link out of the folder and a folder that resolves outside the store, and rejects an artifact whose address was changed (`FileFilmFrameStoreTest`, `FilmAssembleExecutorIntegrationTest`). Ids that name files are `[A-Za-z0-9_-]{1,40}` and film ids are a fixed hash shape. `-f image2 -pattern_type none` makes a path a file name, never a pattern. ffmpeg gets an environment of `PATH`, the temp folders and locale, with no connection secret. It is stopped, with its whole process tree, after 15 minutes or when the run is cancelled, and a half-written film is deleted. Residual risks: ffmpeg decodes the PNG an image API returned, so a decoder bug in ffmpeg is reachable through that image (the executor checks the PNG signature and a size cap only; keep ffmpeg up to date); the frame store's `regenerate.txt` and the pictures are plain files that anything running as the keeper can edit; ffmpeg is whichever one `CliLocator` finds first on `PATH`.

**Provenance of generated media.** Every frame artifact and the film carry `aiGenerated = true` in their metadata, frames record whether they were `generated` or `reused`, and the `.mp4` has a `comment` tag saying the frames are AI-generated and assembled by The Aive. This is a label the app applies, not a signature: it is not C2PA content credentials, it is lost if the file is re-encoded or the metadata stripped, and the app cannot stop a keeper presenting the film as something else. Frames and films stay under `~/.aive/film` until the keeper deletes them; the prompts they were drawn from are in the run's artifacts.
