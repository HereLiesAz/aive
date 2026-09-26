# Threat Model

This document identifies the trust boundaries, threat actors, attack surfaces, and mitigations relevant to The Aive. It is a living document. Update it when a new executor, integration, or data flow is added.

## Assets

- Provider, repository-service (GitHub/GitLab), Jules, and compute-relay credentials.
- Repository contents and history on GitHub, GitLab, and local working trees.
- Workflow definitions, run state, artifacts, and approval decisions.
- Data in attached role surfaces (spreadsheets, SQLite databases).
- The user's device. This includes, on the Android GitHub release flavor, the accessibility permission granted to the Gemini bridge and the ability to install APK updates.

## Trust Boundaries

| Boundary | What crosses it | Direction |
|---|---|---|
| Device ↔ Provider (hosted LLMs, keyless gateways, Jules) | Task objectives, role instructions, context artifacts, acceptance criteria, repository snapshots (workspace agents) | Outbound, responses inbound |
| Device ↔ GitHub API | Workflow dispatch and inputs, run status, check runs, artifact downloads, workflow-file installs, repository operations | Both |
| Device ↔ GitLab API | Repository tree/file reads, workspace-agent commits, repository operations | Both |
| GitHub runner ↔ Agent/script | Prompt or script source, repository checkout, job token (runner only) | Inside the user's repository's Actions |
| Device ↔ Local Git (desktop) | Snapshot reads, isolated worktrees, commits, bounded Git commands | Both (local) |
| Device ↔ Compute relay ↔ Pool devices | Node descriptors, task lease envelopes, progress, results | Both |
| Device ↔ Crash relay (Android GitHub flavor, opt-in) | Crash/ANR reports → public GitHub issues | Outbound |
| Device ↔ Gemini app (Android GitHub flavor, opt-in) | Prompt typed into Gemini, reply read from screen/clipboard | Both (on-device) |
| Device ↔ Update and package sources | GitHub Releases APKs and models, Play in-app updates, Azphalt packages | Inbound |
| Device ↔ Persistence | Workflow state, settings, credentials | Both (local) |
| Haive ↔ User | Workflow definitions, task approval decisions, escalation responses | Both |

## Threat Actors

- **Compromised provider**: A provider (Jules or other) returns malicious content disguised as task output. Examples include code that overwrites secrets, instructions injected into artifact text, and a fabricated approval status.
- **Logging free-tier gateway**: A keyless or free model gateway (Kilo, LLM7, OVHcloud anonymous tier, OpenCode Zen free models) retains or trains on prompts.
- **Malicious workflow definition**: A definition author (or import) injects a task objective, role instruction, or acceptance criterion designed to exfiltrate data or execute privileged operations.
- **Malicious artifact**: A prior task produces an artifact (code, text, data) whose content is interpreted as instructions by a downstream provider or executor.
- **Malicious repository content**: Files in the target repository contain prompt-injection aimed at an agent that reads them (OpenCode, GitLab/Local workspace agents, Jules).
- **GitHub Actions workflow injection**: An attacker with write access to the repository triggers workflows with controlled inputs, or a workflow file references untrusted external actions.
- **Repository write abuse**: An executor or integration that performs repository operations (branch, commit, PR/MR, push) is tricked into writing malicious content or granting access.
- **Prompt injection via context**: User-controlled content (project name, objective text, imported artifacts) propagates into provider prompts and attempts to redirect agent behavior.
- **Stale/replayed run**: An old or duplicate GitHub Actions run is incorrectly credited as current task completion, bypassing work.
- **Rogue pool member**: Any party holding a compute pool's relay token publishes leases, claims leases, or impersonates a node.
- **Compromised update/package source**: A tampered APK, model, or Azphalt package is offered for installation.

## Attack Surface by Integration

### Provider (Jules / agent sessions / hosted LLMs)

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
- Keyless use requires an explicit link. It is stored as the `anonymous` credential, and the catalog tells users about the logging risk. Keyless entries are listed last so that planning, which uses the first linked provider, prefers a keyed provider.

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
- The workflow contents are managed by the app. Before each dispatch The Aive compares the installed file with its bundled template and overwrites it when they differ.
- Results land on a new branch, never the default branch, and the task can require plan approval before dispatch. The patch is surfaced as a `CodeChange` artifact for review.
- The prompt is capped at 50,000 characters to stay within GitHub's `workflow_dispatch` input limits.

**Residual risks**
- Prompt injection can put arbitrary code on the result branch. Treat the branch as untrusted until reviewed.
- Removing the token from the agent's environment is defense in depth, not an isolation boundary. The agent's shell runs as the same runner user as the parent process that still holds the token.
- Free Zen models may log prompts.
- Installing the workflow requires a GitHub token with Contents **and Workflows** write access. That token can rewrite any workflow in the repository.
- Installing or updating the workflow commits directly to the default branch, without a `HumanApproval` task.

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
- Repository code executes on the desktop host.
- A patch clobbers the user's uncommitted work.

**Mitigations in place**
- The model never gets shell access. It receives a bounded snapshot (up to 28 files and 180,000 characters) and returns a unified diff.
- Diff paths are validated. Absolute paths, `.git/`, `..` segments, `.gitattributes`, and `.gitmodules` are rejected. The diff must pass `git apply --check` before it is applied.
- The patch is applied in an isolated `git worktree` on a new `haive/...` branch. The user's checkout is untouched, and the working tree must be clean before the agent starts.
- Commits run with `core.hooksPath` set to an empty temporary directory, so repository hooks do not execute.
- Repository code is never executed on the host. The agent records a "Host execution withheld" artifact, and nothing is pushed.

### Repository operations

GitHub and GitLab operations are implemented in `RemoteRepositoryOperationClients.kt`: `status`, `create-branch:<branch>`, `open-pull-request:…`, and `open-merge-request:…`. Local Git operations (desktop) cover `status`, `fetch`, `pull`, `add-all`, `push`, `checkout`, `create-branch`, `commit`, and `push-set-upstream`. See `docs/Repository-Services.md` and `docs/Local-Git-Repository-Operations.md`.

**Threats**
- An operation string is crafted to inject options or shell syntax.
- A mutating operation (branch, PR/MR, commit, push) runs without human review.
- Artifact content propagates into PR titles or commit messages.

**Mitigations in place**
- Operation strings are parsed into a fixed vocabulary. Remote operations are REST calls. Local operations are fixed `ProcessBuilder` argument lists, not shell commands.
- Branch names are validated: they cannot be empty, cannot start with `-`, and cannot contain NUL or newline characters.
- `WorkflowGraphValidator` rejects any `RepositoryOperation` task other than `status` or `fetch` that lacks a `HumanApproval` ancestor.
- No remote operation merges, deletes, or force-pushes.
- Remote repository credentials are scoped to `github.com` and `gitlab.com`.
- Failures are normalized into failed tasks and follow the workflow's retry/escalation policy.

**Remaining gaps**
- The approval check inspects only `task.executor`. A `RepositoryOperation` wrapped in `TaskExecutor.Distributed` is not checked.
- Operations do not reject protected branches up front. They rely on the host's branch protection.
- Workspace agents (OpenCode, GitLab, Local, Jules) write branches without a `HumanApproval` task. Plan approval is optional per task.

### Nested workflows

`TaskExecutor.NestedWorkflow` and composition (`WorkflowComposer.nest` / `inline`) ship. Inlined children become ordinary namespaced tasks and are validated with the parent. Their repository-operation approval rule and cycle detection therefore apply.

No shipped platform currently registers a `NestedWorkflowClient`. A `NestedWorkflow` node is therefore marked `Blocked` ("Nested workflow executor is not available in this runtime") rather than being dispatched.

**Remaining gaps**
- When a nested executor is wired, it needs recursion-depth limits and cycle detection across definitions, and approval gates must carry into the child run.

### Distributed compute relay and executor

`TaskExecutor.Distributed` wraps another executor and sends it as a lease through a self-hosted relay (`computeRelay/`, protocol version 1) to a device in the same pool. Workers run only system executors (GitHub Actions, script, repository operation, and similar), using their own local integrations and credentials.

**Threats**
- Anyone holding the pool's shared bearer token can join the pool and read every lease envelope. An envelope carries the project, definition, run, task, and role.
- A rogue member can publish leases that make sharing devices run repository operations or dispatch GitHub Actions, using those devices' credentials, against any repository named in the envelope.
- A rogue member can re-register an existing node ID to displace it.
- A worker can return fabricated results.
- The relay can be reached over plain `ws://`.

**Mitigations in place**
- Distributed compute has no default relay and is off until configured. Accepting work also requires `sharingEnabled`, and can additionally require an unmetered network and external power.
- A node advertises only the executor kinds its credentials support. The relay offers leases only to nodes that satisfy the lease's requirements. The worker re-checks those requirements and a per-node parallel-lease cap.
- The relay requires `Authorization: Bearer <token>`. It rejects mismatched protocol versions and caps frames at 8 MiB.
- Only a lease's origin can cancel it. Only the claiming worker can report its progress or completion. Expired claims are requeued.
- `HumanApproval` tasks are never delegated. Envelopes must carry the unwrapped executor and the matching role definition.
- Relay tokens are stored in the platform credential store.

**Remaining gaps**
- There is a single shared token per relay, with no per-node identity or signing. Node IDs are self-asserted.
- Envelopes are not end-to-end encrypted, so the relay operator sees them in plaintext.
- Workers do not re-validate approval gates. They also do not restrict leases to projects linked on the worker.
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

### Crash relay (Android GitHub flavor, opt-in)

**Threats**
- Sensitive data leaks into a public issue through an exception message or ANR trace.
- The relay endpoint is abused to spam the issue tracker.
- Reporting happens without consent.

**Mitigations in place**
- Reporting exists only in the GitHub flavor. It is off by default and must be enabled in Settings. Disabling it deletes pending reports.
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
- The GitHub-flavor updater verifies GitHub's published SHA-256 asset digest when one is present, and installs only after the user confirms. Android itself rejects an update signed with a different key.
- Play updates go through Play's in-app update API.
- Azphalt packages require HTTPS repositories, Ed25519 signatures against repository signing keys, SHA-256 checksums for model assets, and a revocation check. If revocation status is unavailable, installation requires explicit offline/untrusted approval.

**Remaining gaps**
- If a GitHub release asset has no digest, the APK is installed on Android's signature check alone.

### Persistence

**Threats**
- Malicious import: an exported run bundle contains crafted events that corrupt workflow state or inject false history.
- Credential leakage: provider tokens stored in Settings are included in exported bundles.

**Mitigations in place**
- Credentials are stored separately from workflow persistence and are never serialized into run state or exported bundles.
  - **Android:** AES-GCM with an Android Keystore key; ciphertext in SharedPreferences.
  - **Desktop:** macOS Keychain, Linux Secret Service, or Windows DPAPI. Storage is refused when none is available.
- Schema migrations validate field types and reject unknown executor kinds.

**Remaining gaps**
- Imported run bundles are not cryptographically signed. A tampered bundle is accepted if it passes schema validation.
- The web build stores credentials unencrypted in browser `localStorage`, where they are readable by any script running on the origin.

## Invariants

The following invariants must hold regardless of the integration used:

1. A task moves to `Completed` only when the executor integration explicitly returns `Completed` after polling the external system. No task self-completes on dispatch.
2. Provider credentials never appear in workflow state, audit events, or exported bundles.
3. The Aive runtime never executes artifact content as code or instructions. Remote agents with shell access (OpenCode) may act on it, and that risk is covered as a residual risk above.
4. Every mutating `RepositoryOperation` task (anything other than `status`/`fetch`) has a `HumanApproval` ancestor in the workflow DAG. Workspace agents write only to new dedicated branches.
5. Provider payloads are subject to `PayloadRedactionPolicy` before transmission.
6. Dispatch-to-run-ID correlation is API-returned. The one exception is when OpenCode resumes after a restart: it matches the unique per-run `run-name` among recent dispatches of its app-managed workflow.

## Open Items

- Automated prompt-injection scanning of artifact content before use as provider context.
- Cryptographic signing of exported run bundles.
- Verification that a dispatched `workflow_run_id` matches the expected workflow file name.
- Per-session provider response content hashing to detect replay.
- Unwrap `TaskExecutor.Distributed` when enforcing the repository-mutation approval rule.
- Distributed compute: per-node identity, end-to-end envelope encryption, enforced `wss://`, and worker-side approval re-validation and project allow-listing.
- The OpenCode result artifact is parsed without the size bounds applied to script-runner results.
- OpenCode resume looks only at the 50 most recent dispatches. A run older than that is not found and is dispatched again.
- Block CI configuration files (for example `.gitlab-ci.yml`, `.github/workflows/`) in workspace-agent change sets.
- Recursion limits and approval propagation for nested workflows once a `NestedWorkflowClient` is wired.
- Encrypted credential storage for the web build.

### Accepted risks

- `.github/workflows/multiplatform.yml` consumes the shared `HereLiesAz/workflows` actions at `@main`. The owner made this deliberate decision because both repositories share one owner. It is recorded here as accepted, not as an item to fix.
- The crash relay key ships in the APK. It is a spam filter, not an authentication secret.
