# Storage Scale Decision

## Current approach

Android and desktop store workflow state in SQLite (`SqlWorkflowPersistence`), one row per
record. The web keeps the Settings snapshot (`SettingsWorkflowPersistence`, browser
localStorage), a single JSON document holding every project, run, definition, artifact and gate
plus a per-run event journal.

## Measurement (2026-10-12)

`StorageScaleMeasurementTest` (desktop, JVM) keeps N runs of a 15-task workflow with three
~4 KB text artifacts per task, then times one `runs.put` and one `runs.get` (median of nine). The
Settings snapshot is rewritten and re-decoded whole on every write and read, so cost follows the
user's history, not the active run:

| Runs kept | Settings snapshot | put | get |
|---|---|---|---|
| 10 | 1.9 MB | 16 ms | 6 ms |
| 50 | 9.6 MB | 83 ms | 40 ms |
| 150 | 28.8 MB | 313 ms | 115 ms |
| 300 | 57.6 MB | 754 ms | 249 ms |

The 500 KB trigger below was met at 10 runs, so Android and desktop moved to SQL. The test
reports both stores side by side in `shared/build/reports/storage-scale.txt`.

## When to migrate to a structured backend

### Stay on Settings while:
- Total serialized workflow state stays below 1 MB per run.
- The number of active runs per project stays in single digits.
- Event journal length stays below ~500 events per run (empirically around 50 KB serialized).
- Artifact payloads are stored as URIs (not inline content).
- The app does not need cross-device sync, multi-user access, or server-side querying.

### Trigger migration to SQL/IndexedDB when:
- A single run's serialized state exceeds 500 KB (settings writes become noticeably slow).
- The event journal must be streamed rather than loaded in full (memory pressure).
- Artifacts with inline content are needed (binary blobs, large text outputs).
- The app adds a web/server component that needs shared state across sessions or devices.
- Background sync or push notifications require server-readable state.

## Migration path

The `WorkflowPersistence` interface and `InMemoryWorkflowPersistence` / platform
implementations use the same contract. A SQL or IndexedDB backend implements the same
interface. No runtime code changes outside the persistence layer.

For Android: Room (SQLite); for Desktop: SQLite via JDBC; for JS/Wasm: IndexedDB via the
Web Storage API. All three share the same serialization format; only the storage call
sites differ.

## Decision

Migrated (Android, desktop). The web remains on Settings while it is a preview; move it to the
SQL store over the browser worker driver before it ships as 1.0. Originally:

Do not migrate until one of the trigger conditions above is met and measured on a real run.
Settings-backed storage is reliable, requires no schema migrations beyond the existing
JSON versioning, and adds no dependencies. Premature migration adds complexity without
a measurable user benefit.

When migration is triggered: implement the SQL backend behind `WorkflowPersistence`
and run the existing persistence tests against both implementations before shipping.
