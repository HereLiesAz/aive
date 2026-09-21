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
