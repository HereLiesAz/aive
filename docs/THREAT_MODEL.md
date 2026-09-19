# Threat Model

This document identifies the trust boundaries, threat actors, attack surfaces, and mitigations relevant to The Aive. It is a living document — update it when a new executor, integration, or data flow is added.

## Trust Boundaries

| Boundary | What crosses it | Direction |
|---|---|---|
| Device ↔ Provider (Jules, etc.) | Task objectives, role instructions, context artifacts, acceptance criteria | Outbound |
| Device ↔ GitHub API | Workflow dispatch, run status, artifact download URLs, repository operations | Both |
| Device ↔ Persistence | Workflow state, settings, credentials | Both (local) |
| Haive ↔ User | Workflow definitions, task approval decisions, escalation responses | Both |

## Threat Actors

- **Compromised provider**: A provider (Jules or other) returns malicious content disguised as task output — e.g., code that overwrites secrets, instructions injected into artifact text, fabricated approval status.
- **Malicious workflow definition**: A definition author (or import) injects a task objective, role instruction, or acceptance criterion designed to exfiltrate data or execute privileged operations.
- **Malicious artifact**: A prior task produces an artifact (code, text, data) whose content is interpreted as instructions by a downstream provider or executor.
- **GitHub Actions workflow injection**: An attacker with write access to the repository triggers workflows with controlled inputs, or a workflow file references untrusted external actions.
- **Repository write abuse**: An executor or integration that performs repository operations (branch, commit, PR) is tricked into writing malicious content or granting access.
- **Prompt injection via context**: User-controlled content (project name, objective text, imported artifacts) propagates into provider prompts and attempts to redirect agent behavior.
- **Stale/replayed run**: An old or duplicate GitHub Actions run is incorrectly credited as current task completion, bypassing work.

## Attack Surface by Integration

### Provider (Jules / agent sessions)

**Threats**
- Prompt injection: context artifacts contain `IGNORE PREVIOUS INSTRUCTIONS` style attacks directed at the provider model.
- Payload exfiltration: task objective or artifacts contain sensitive content the user did not intend to send.
- Fabricated completion: provider claims success without performing the actual task.
- Compromised response: provider returns code or instructions designed to exploit the Aive runtime or downstream tasks.

**Mitigations in place**
- `PayloadRedactionPolicy` allows per-definition exclusion of artifact kinds, objective, and role instructions from the provider payload.
- Provider artifacts are stored as opaque `ArtifactRef` values — the runtime never executes their content.
- Provider completion requires explicit reconciliation; a provider claiming immediate success returns `Completed` only after the runtime polls and confirms.
- Acceptance criteria are authored by the workflow definition, not the provider.

**Remaining gaps**
- No automated scanning of provider-returned artifacts for prompt injection before they are used as context for downstream tasks.
- No per-session content hash to detect fabricated or replayed provider responses.

### GitHub Actions executor

**Threats**
- Workflow injection: a `workflow_dispatch` payload references a user-controlled ref that triggers a different, attacker-authored workflow.
- Stale run correlation: the dispatch returns a run ID for a previously queued run rather than the newly dispatched one.
- Malicious artifact download URL: the `/artifacts` response returns a URL pointing to attacker-controlled storage.
- Cancelled/timed-out run silently treated as success: the runtime misclassifies a non-success conclusion.

**Mitigations in place**
- `dispatch()` sends `return_run_details: true` and requires the response to include a `workflow_run_id` — the run ID is bound to the exact dispatch, not guessed from a run list.
- `toStatus()` maps every known non-success conclusion (`cancelled`, `timed_out`, `stale`, `action_required`, `skipped`, `neutral`, `failure`) to `Failed`.
- Archive download URLs are stored as opaque URIs; the runtime does not fetch or execute their contents without an explicit user action.
- The `ref` used for dispatch comes from the executor definition or the repository default branch, not from user-provided free text at runtime.

**Remaining gaps**
- No verification that the returned `workflow_run_id` belongs to the expected workflow file — a race between dispatches of different workflows on the same repository could assign the wrong run.
- No signature verification of downloaded artifacts.

### Repository operations (future)

Any integration that writes to the repository (branch, commit, PR) must:
- Require an explicit `HumanApproval` gate in the workflow definition before any destructive or publishing operation.
- Record the exact operation and its parameters in an audit event before executing.
- Reject operations on protected branches without explicit override approval.
- Never propagate artifact content directly as commit messages or PR body text without sanitization.

### Persistence

**Threats**
- Malicious import: an exported run bundle contains crafted events that corrupt workflow state or inject false history.
- Credential leakage: provider tokens stored in Settings are included in exported bundles.

**Mitigations in place**
- Credentials are stored in platform-secure storage (Keychain/KeyStore/EncryptedSharedPreferences) separately from workflow persistence; they are never serialized into run state or exported bundles.
- Schema migrations validate field types and reject unknown executor kinds.

**Remaining gaps**
- Imported run bundles are not cryptographically signed; a tampered bundle is accepted if it passes schema validation.

## Invariants

The following invariants must hold regardless of the integration used:

1. A task moves to `Completed` only when the executor integration explicitly returns `Completed` after polling the external system; no task self-completes on dispatch.
2. Provider credentials never appear in workflow state, audit events, or exported bundles.
3. No executor integration executes artifact content as code or instructions.
4. Every destructive or publishing repository operation is preceded by a `HumanApproval` task in the workflow DAG.
5. Provider payloads are subject to `PayloadRedactionPolicy` before transmission.
6. Dispatch-to-run-ID correlation is always API-returned, never inferred from a run list.

## Open Items

- Automated prompt-injection scanning of artifact content before use as provider context.
- Cryptographic signing of exported run bundles.
- Verification that a dispatched `workflow_run_id` matches the expected workflow file name.
- Threat model for nested Haive workflows (executor type `NestedWorkflow`) once implemented.
- Per-session provider response content hashing to detect replay.
