# Role execution sources

A role is the semantic identity and governance boundary. Its **execution source** is the mechanism
which performs the role's task. They are deliberately separate.

The Swarm role editor exposes:

- **Agent / provider** — the existing provider-selection path.
- **GitHub Actions** — dispatches a selected workflow and supplies an Aive task envelope.
- **JavaScript** — runs either in Android's isolated JavaScript sandbox or through GitHub Actions.
- **Python** — runs through a GitHub Actions script runner.

A task with no explicit executor inherits its role's execution source when a workflow run is created.
The run keeps `assignedRoleId`, so changing the execution mechanism does not erase role identity,
authority, instructions, or historical attribution. An explicit task executor still wins.

## Aive task envelope

System-backed roles receive a JSON `AiveTaskEnvelope` containing project/repository identity,
workflow and task IDs, workflow/task objectives, the responsible role and its standing instructions,
acceptance criteria, attempt number, and dependency artifacts.

The existing `PayloadRedactionPolicy` is applied before the envelope leaves the app: excluded artifact
kinds are removed, and objective/role instructions are replaced with `[REDACTED]` when configured.

GitHub Actions defaults to the input name `aive_context`. Script runners additionally receive:

- `aive_script` — the user's source text.
- `aive_language` — `javascript` or `python`.

All three input names can be changed in the role editor.

## Local JavaScript contract

Local JavaScript is executed through AndroidX JavaScriptEngine's isolated sandbox. The script body
receives a frozen `aive` object. It should return:

```javascript
return {
  status: "completed",
  message: "optional progress/result message",
  output: "optional plain-text result",
  artifacts: [
    {
      label: "Analysis",
      kind: "Research",
      textContent: "downstream-usable evidence",
      mediaType: "text/plain",
      metadata: { source: "script" }
    }
  ]
};
```

`status: "failed"` or `"error"` fails the task. `output` becomes a normal `CommandOutput`
artifact, and returned artifacts enter the normal workflow artifact/evidence path.

## JavaScript and Python through GitHub Actions

Use `docs/examples/aive-script-runner.yml` as a starting workflow. It executes the supplied script
without interpolating script text into shell syntax, writes `aive-result.json`, and uploads it as the
`aive-result` artifact.

The Aive downloads that result artifact through the authenticated GitHub API, caps archive/result
size, parses the same result contract used by local JavaScript, and inserts its output/artifacts into
the task. Other Actions artifacts remain ordinary command-output artifacts.

The example workflow grants only `contents: read`. A project may intentionally widen permissions,
but role scripts receive only the permissions/secrets the selected workflow explicitly grants.

## Inference-only policies

Centralized MoA, Skeleton-of-Thought, resource-aware compound inference, and agent environment
planning require an **Agent** execution source. A scripted or Actions-backed role is never silently
converted back to a provider/model-backed role during workflow preparation.


## Attached role surfaces

Execution source and attached surfaces are independent. A role may use an AI provider, GitHub Actions,
local JavaScript, or a GitHub-backed JavaScript/Python runner while simultaneously exposing any
number of named data/logic surfaces.

The Swarm editor currently supports:

- **Spreadsheet** — CSV/TSV from an app-owned file, inline data, a document URI, or HTTPS. App-owned
  files and writable document URIs can accept controlled row mutations.
- **SQL database** — SQLite query surfaces backed by an app-owned database or read-only document URI.
  App-owned SQLite databases can accept explicit SQL mutations.
- **Flowchart** — a native Mermaid-style `flowchart` / `graph` surface. The Aive parses the
  supported flowchart subset itself into nodes and edges; no WebView is required.

Resolved surfaces appear in the executor envelope:

```json
{
  "surfaces": [
    {
      "alias": "customers",
      "kind": "spreadsheet",
      "writable": true,
      "table": {
        "columns": ["id", "name"],
        "rows": [{"id": "1", "name": "Ada"}],
        "truncated": false
      }
    },
    {
      "alias": "db",
      "kind": "sql",
      "writable": true,
      "table": {
        "columns": ["id", "status"],
        "rows": [{"id": "1", "status": "open"}]
      }
    },
    {
      "alias": "process",
      "kind": "flowchart",
      "flowchart": {
        "direction": "TD",
        "nodes": [{"id": "A", "label": "Load", "shape": "rectangle"}],
        "edges": [{"from": "A", "to": "B", "style": "arrow"}]
      }
    }
  ]
}
```

JavaScript reads `aive.surfaces`; Python reads the same data from `aive["surfaces"]`.

### Surface mutations

A successful script/Action may return `surfaceMutations` alongside ordinary output/artifacts:

```json
{
  "status": "completed",
  "surfaceMutations": [
    {
      "alias": "customers",
      "operation": "AppendRows",
      "rows": [{"id": "2", "name": "Grace"}]
    },
    {
      "alias": "db",
      "operation": "ExecuteSql",
      "sql": "UPDATE jobs SET status = 'done' WHERE id = 7"
    }
  ]
}
```

Spreadsheet operations are `AppendRows` and `ReplaceRows`. SQL accepts `ExecuteSql` only.
Mutations are rejected unless the target surface is explicitly writable. HTTPS/inline spreadsheets,
document-URI SQLite databases, and flowcharts are read-only. The host keys mutations by task run,
attempt, and mutation index so repeated GitHub reconciliation does not normally replay a completed
write.

### Spreadsheet limits

Spreadsheet snapshots are bounded by the surface's configured row limit (1–10,000 rows) and the
Android host caps source/serialized size at 5 MiB. CSV/TSV parsing supports quoted fields, embedded
delimiters/newlines, escaped quotes, CRLF, duplicate header disambiguation, and headerless tables.

### SQL boundary

The first SQL implementation is SQLite because Android ships a native SQLite runtime. Read queries
are bounded by the configured row limit. Only app-owned SQLite databases may be mutated; imported
document-URI databases are copied to a temporary read-only file for the query and deleted afterward.
This keeps database-server credentials and arbitrary remote SQL transports out of the first surface
contract while leaving `RoleSurface.Sql`/dialect routing extensible.

### Native flowchart scripting

The first native flowchart language is the Mermaid flowchart subset. Supported syntax includes
`flowchart` / `graph` direction headers, ordinary/rounded/circle/decision/stadium nodes, arrow,
line, dotted edges, and labeled arrows. Unsupported statements are reported as parser warnings or
validation failures rather than being passed to a browser renderer.

Because the parsed graph is a normal role surface, a script can combine deterministic flow topology
with spreadsheet/SQL data in the same execution. The graph is intentionally read-only at runtime;
editing happens through the Swarm flowchart source field.
