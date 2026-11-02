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
| Device ↔ GitHub API | Workflow dispatch and inputs, run status, check runs, artifact downloads, workflow-file installs, repository operations | Both |
| Device ↔ GitLab API | Repository tree/file reads, workspace-agent commits, repository operations | Both |
| GitHub runner ↔ Agent/script | Prompt or script source, repository checkout, job token (runner only) | Inside the user's repository's Actions |
| Device ↔ Local Git (desktop) | Snapshot reads, isolated worktrees, commits, bounded Git commands | Both (local) |
| Device ↔ Compute relay ↔ Pool devices | Node descriptors, task lease envelopes, progress, results | Both |
| Device ↔ Crash relay (Android GitHub flavor; opt-in) | Crash/ANR reports → public GitHub issues | Outbound |
| Device ↔ OTLP collector (opt-in) | Run traces: structure, timing, ids, tokens, cost; content only with a second opt-in | Outbound |
| Device ↔ Gemini app (Android GitHub flavor, opt-in) | Prompt typed into Gemini, reply read from screen/clipboard | Both (on-device) |
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
- **Rogue pool member**: Any party holding a compute pool's relay token publishes leases, claims leases, or impersonates a node.
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
- Keyless use requires an explicit link. It is stored as the `anonymous` credential, and the catalog tells users about the logging risk. Keyless entries are listed last so that planning, which uses the first linked provider, prefers a keyed provider.
- Store-installed language models (Azphalt `kind:"llm"`, endpoint tier) are verified like any package (integrity, signature, publisher pin, revocation). Literal endpoints come from the signed manifest; self-hosted endpoints may instead come from a declared non-secret install input and are required to use HTTPS. They get the `azphalt:` id prefix, so a package cannot impersonate or replace a built-in provider. Their setup scripts are never executed on-device. `openai-chat` responses use the normal text-provider path; `moyai-session` is confined to a native `AgentProvider` adapter and cannot alter workflow semantics. Moyai model/provider credentials remain server-side; an optional Moyai workspace password is stored only in Aive's platform credential vault.

**Remaining gaps**
- No automated scanning of provider-returned artifacts for prompt injection before they are used as context for downstream tasks.
- No per-session content hash to detect fabricated or replayed provider responses.
- Nothing stops confidential tasks from being routed to a keyless or free-tier provider. The only safeguard is the catalog description.

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
- The runner deletes `GITHUB_TOKEN` and the raw task from the agent process's environment. The runner, not the agent, pushes the result branch. It uses the token in a one-off remote URL and scrubs the token from push error text.
- The workflow requests only `contents: write` and `checks: write`. The job token expires when the job ends, and the job has a 60-minute timeout.
- The workflow contents are managed by the app. Before each dispatch The Aive compares the installed file with its bundled template. Installing or updating it always requires a person to approve a plan that says the file will be committed directly to the default branch, even for tasks that otherwise run without a plan gate. With the engine's plan gate that is the drafted plan; a run whose approved plan did not disclose the change stops for approval again before writing the file.
- Results land on a new branch, never the default branch, and the task can require plan approval before dispatch. The patch is surfaced as a `CodeChange` artifact for review.
- The prompt is capped at 50,000 characters to stay within GitHub's `workflow_dispatch` input limits.
- The result artifact is bounded: at most 4 MB zipped and 2 MB of JSON.
- Resume after a restart searches up to 1,000 recent dispatches (ten pages) for the run's unique `run-name`.

**Residual risks**
- Prompt injection can put arbitrary code on the result branch. Treat the branch as untrusted until reviewed.
- Removing the token from the agent's environment is defense in depth, not an isolation boundary. The agent's shell runs as the same runner user as the parent process that still holds the token.
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
- Haive runs no repository code itself: it never builds or tests the change. The agent records a "Host execution withheld" artifact, and nothing is pushed.
- `git worktree add` (post-checkout) and `git commit` run with `core.hooksPath` set to an empty temporary directory, and every Git command runs with `core.fsmonitor=false`, so repository hooks and the fsmonitor command do not execute. Git child processes get no inherited `GIT_*` variables (such as `GIT_DIR` or `GIT_INDEX_FILE`) and cannot prompt.
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
| `KeeperWritten` | Objective, task and role text, the runtime's own control summaries, human-approval output |
| `AgentArtifact` | Output of an agent, GitHub Action, script or test runner |
| `RepositoryContent` | Repository-operation output; retrieved repository files |
| `ExternalContent` | Output of a role with the `Research` capability, an external service, a deployment, or anything returned by another pool device |
| `MemoryRecall` | Blocks recalled from the memory layer |
| `Unknown` | Anything without a label, including artifacts stored before labelling. Treated as external. |

**Threats**
- Prompt injection in web research, service responses or pool results steers a task that can write the repository, deploy, call a service or run a script.
- Injected content is laundered through an intermediate agent whose output looks trusted.
- A provider or executor labels its own output as keeper-written.

**Mitigations in place**
- The runtime labels artifacts when it takes them in (`WorkflowEngine.reconcile`, system executor dispatch and reconcile, remote compute). The runtime's label replaces the producer's. A producer can only lower trust: its own `ExternalContent` label and its untrusted-input flag are kept.
- Taint propagates. Anything made by a task whose inputs include external or unknown content is flagged `aive.provenance.untrustedInput` and counts as external downstream.
- Before a task runs, `WorkflowRuntimeCoordinator` checks it. If its executor can change something and any of its inputs are external, unknown or flagged, the task is held `AwaitingApproval` at a `SecurityRisk` gate (`taint:<run>:<task>:<attempt>`, human only). The gate names those inputs, and nothing is dispatched until a person approves. Rejecting fails the task without a retry. Executors that can change something: repository operations other than `status`/`fetch`, deployments, external services, GitHub Actions, scripts, test runners, and agents whose role needs `RepositoryWrite`, `PullRequestCreation` or `ShellExecution` (or whose role is unknown). Distributed placements are judged by their delegate.
- Keeper-written content, the project's own repository and clean agent output never trigger the gate. The gate adds to invariant 4 and never replaces a `HumanApproval` task.
- The creature's Work tab lists each input with its label. Its Needs tab shows the gate's reason.

**Remaining gaps**
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

`TaskExecutor.Distributed` wraps another executor and sends it as a lease through a self-hosted relay (`computeRelay/`, protocol version 1) to a device in the same pool. Workers run only system executors (GitHub Actions, script, repository operation, and similar), using their own local integrations and credentials.

**Threats**
- Anyone holding the pool's shared bearer token can join the pool and read every lease envelope. An envelope carries the project, definition, run, task, and role.
- A rogue member can publish leases that try to make sharing devices run repository operations or dispatch GitHub Actions with those devices' credentials.
- A rogue member can register a node ID that is not currently connected and so act as that node (receive its replayed leases, cancel its leases).
- A worker can return fabricated results.
- The relay can be reached over plain `ws://`.

**Mitigations in place**
- Distributed compute has no default relay and is off until configured. Accepting new work also requires `sharingEnabled`, and can additionally require an unmetered network and external power. Android re-evaluates and republishes those conditions when network/power state changes, and its worker checks each offer against the same live descriptor.
- A node advertises only the executor kinds its credentials support. The relay offers leases only to nodes that satisfy the lease's requirements. The worker re-checks those requirements and a per-node parallel-lease cap.
- The relay requires `Authorization: Bearer <token>`, compared in constant time. It rejects mismatched protocol versions and caps frames at 8 MiB.
- A node ID held by a live connection cannot be registered again until that connection closes.
- Lease duration (10 minutes), unfinished leases per origin (64) and leases per pool (512) are capped, and an unfinished lease is dropped 24 hours after the relay accepted it.
- Only a lease's origin can cancel it. Only the claiming worker can report its progress or completion. Expired claims are requeued. Workers continuously watch relay ownership while executing; cancellation, requeue/reassignment, or relay disconnect cancels the running coroutine rather than allowing stale work to continue.
- `HumanApproval` tasks are never delegated. Envelopes must carry the unwrapped executor and the matching role definition.
- Workers re-check every lease before running it (`SystemExecutorDistributedWorkloadRunner`): the submitted workflow must validate; the task must be a distributed placement of the requested executor; mutating repository operations are refused outright, because the approval state in an envelope is self-attested; and work that uses the worker's repository credentials (repository operations, GitHub Actions, GitHub-backed scripts) runs only against a repository of a project linked on the worker's own device. Anything else fails the lease as refused.
- Relay tokens are stored in the platform credential store.
- Origin disconnect does not destroy accepted work. The relay retains unfinished leases until they complete, are cancelled or reach the 24-hour cap; terminal results have a 24-hour replay window. When `AIVE_RELAY_STATE_DIR` is configured, those lease records survive relay-process restarts in AES-256-GCM-encrypted files keyed from the relay bearer secret. Expired terminal records are removed when an active/persisted pool is swept or loaded; an untouched encrypted pool snapshot can remain until it is accessed or explicitly removed by the operator.

**Remaining gaps**
- There is a single shared token per relay, with no per-node identity or signing. Node IDs are self-asserted. The same token also derives the optional persisted-state encryption key, so token rotation requires draining/clearing or explicitly migrating saved relay state.
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
- Surfaces are opt-in per role and named explicitly in the Swarm editor.
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
7. A task whose executor can change something does not run on external, unknown-provenance or externally derived inputs until a person approves a gate that names those inputs. An artifact without a provenance label counts as external.

Invariant 4, together with the gate-decider, approval-policy and add-on permission checks, is decided by the in-process policy decision point ([`architecture/POLICY.md`](architecture/POLICY.md)). Its denials and approval requirements are recorded on the run timeline. A policy rule may add a gate but must never relax an invariant.

## Open Items

- Automated prompt-injection scanning of artifact content before use as provider context.
- Cryptographic signing of exported run bundles.
- Verification that a dispatched `workflow_run_id` matches the expected workflow file name.
- Per-session provider response content hashing to detect replay.
- Distributed compute: per-node identity, end-to-end envelope encryption, enforced `wss://`, and signed approvals so workers can verify gates rather than trust the submitted run.
- Block CI configuration files (for example `.gitlab-ci.yml`, `.github/workflows/`) in workspace-agent change sets.
- Deciding plan approvals and failure escalations inside nested child runs from the parent node.
- Web credential protection against same-origin script (for example, a passphrase-derived key).
- Trace export headers in the platform credential store instead of app settings.

### Accepted risks

- `.github/workflows/multiplatform.yml` consumes the shared `HereLiesAz/workflows` actions at `@main`. The owner made this deliberate decision because both repositories share one owner. It is recorded here as accepted, not as an item to fix.
- The crash relay key ships in the APK. It is a spam filter, not an authentication secret.
