# Persistence

## Goal

A workflow should survive Android process death, a desktop restart, a browser reload, or a provider session that outlives the UI process.

The engine therefore treats durable workflow state as a first-class product requirement rather than as UI state.

## Current backend

`SettingsWorkflowPersistence` is the current cross-platform persistence implementation. It stores a versioned serialized workflow snapshot through Multiplatform Settings. Workflow events appended during normal operation use a per-run settings journal so event growth does not force the entire snapshot to be deserialized and rewritten for every event.

Current platform backing stores are:

- Android: SharedPreferences
- Desktop/JVM: Java Preferences
- Web JS: browser localStorage
- Web Wasm: browser localStorage

The persisted model includes:

- projects
- prepared workflow definitions
- workflow runs
- task runs
- append-only workflow events
- role definitions
- artifacts
- approval gates

Writes are guarded so the engine has a coherent restart-safe baseline. Failure-escalation decisions that must update the gate, run, and audit event together remain embedded in one snapshot write so those three pieces cannot recover in a split state.

## Durable user-entered state

Workflow truth is not the only state that must survive process death. The application also writes
non-secret user drafts and presentation choices through `DurableUiStateStore` as they change.
This includes project/run creation drafts, repository fields, in-screen navigation state, appearance,
workflow composition drafts, custom-swarm/role editing, artifact browsing state, agent-message drafts,
compute-configuration drafts, and Store navigation.

The top-level destination is intentionally chosen fresh on each app startup: Settings only when no
provider is configured, otherwise Overview.

Saved secrets are intentionally different: provider API keys, repository tokens, and relay tokens
remain in their platform credential stores. On Android their writes are synchronous before the UI
reports the value as saved. Unsaved password/token text is never copied into the general Settings
store.

A product rename must never rename a persistence key merely for cosmetic consistency. Existing
`haive.*` and `geministrator.*` keys are compatibility identifiers and remain valid storage
names unless an explicit read-old/write-new migration is shipped.

## Portable `.ive` project files

The Aive project document extension is **`.ive`**. A project document contains a versioned wrapper
around the same workflow persistence schema used by the runtime, scoped to one project and its run
state, definitions, events, artifacts, approval gates, and the role definitions required to execute
the project.

Credentials and tokens are never embedded in a `.ive` file.

Android and Desktop expose two load paths:

- a detected list containing files in the normal Aive project directory plus previously selected
  external project files;
- an unrestricted platform file picker for a `.ive` file stored somewhere that was not detected.

Import merges the project into local durable storage rather than clearing unrelated projects. The
runtime is then rebuilt so imported role definitions and workflows are active before the imported
project is reopened.

## Android package identity is persistence

Android application IDs define the application sandbox. Changing the application ID is not a
rename; Android installs a different application with different preferences and Keystore access.

The release identities are therefore fixed:

- **GitHub flavor:** `com.hereliesaz.haive` — retained for in-place compatibility with the
  original GitHub APK lineage and its existing data.
- **Play flavor:** `com.hereliesaz.aive` — the Play package identity.

Future branding work must not change either ID. The GitHub flavor may update itself from GitHub
Releases and therefore requests package-install permission only in the GitHub manifest. The Play
flavor never requests that permission; it only informs the user and links to Google Play.

## What is deliberately not persisted here

`WorkflowPersistence` must not contain:

- provider API keys
- OAuth access or refresh tokens
- passwords
- private signing keys
- keystore contents
- service-account credentials
- arbitrary secret values

A workflow may carry the **name** of a secret required by an environment, but not the secret itself.

Credentials belong in platform-secure storage or, where a browser cannot safely hold a credential, behind an appropriate service boundary.

## Resume invariant

A task with an existing executor/provider run identifier must reconnect to that existing run when possible. Restarting The Aive must not create duplicate external work merely because the local process restarted.

`TaskRun` persists executor identity separately from responsibility and provider state:

- `executor` — resolved `TaskExecutor`
- `assignedRoleId` — optional responsibility/authority role
- `assignedProviderId` and `providerRunId` — provider-backed agent execution
- `externalRunId` — system/external executor run identity

This separation lets an external build, deployment, repository operation, approval, or nested workflow resume without pretending to be an agent session.

## Schema versioning

The current persistence schema is **3**.

Schema `2` introduced executor-neutral task/run state. The migration from schema `1` is explicit:

- a stored `TaskDefinition` with a role but no executor becomes `TaskExecutor.RoleAgent(roleId)`
- a stored `TaskRun` with an assigned role but no executor becomes `TaskExecutor.RoleAgent(assignedRoleId)`
- existing workflow IDs, task-run IDs, statuses, attempts, provider IDs, provider run IDs, artifacts, blocking reasons, and progress are preserved

Schema `3` makes retained artifact identity retry-safe. Legacy provider and GitHub artifact IDs are rewritten to include the task attempt so evidence from an earlier failed attempt cannot collide with the current attempt.

A successful migration is written back immediately at the current schema version instead of existing only in memory until some unrelated later write. Readers reject snapshots created by a newer unsupported schema and leave the stored bytes untouched. Any future incompatible schema change likewise requires an explicit migration path rather than silent reinterpretation or replacement with an empty snapshot.

The current storage key is `geministrator.workflow.persistence.v2`. The previous `geministrator.workflow.persistence.v1` key remains a read fallback: when legacy data is found there, it is migrated, written to the current key, and the legacy key is retired.

## Scale boundary

The Settings-backed snapshot plus per-run event journal is a restart-safe baseline, not the final high-volume event database.

The repository contracts are intentionally replaceable. Likely future storage includes transactional SQL on Android/Desktop and IndexedDB on Web when event volume or indexed queries justify it.

A storage migration must preserve domain IDs and resume behavior.

## User control

Local workflow data remains on the device/browser until the application removes it or the user clears application/browser storage. Data created at external providers is governed separately by those services.

See [`../PRIVACY.md`](../PRIVACY.md).
