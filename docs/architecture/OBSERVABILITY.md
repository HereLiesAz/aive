# Observability: OpenTelemetry GenAI traces

The Aive can send a trace of each finished run to an OpenTelemetry collector the user names. It is **off by default** and set in the Keeper's room, **About and privacy › Trace export**. Nothing is collected or sent while it is off.

Source: `shared/src/commonMain/kotlin/com/hereliesaz/geministrator/telemetry/`.

## Where spans come from

Spans are derived from the run's workflow event journal (`WorkflowEventRepository.forRun`), not from instrumentation inside the engine. The engine already journals every step and every provider usage report (`ProviderUsageRecorded`); `WorkflowTraceBuilder` turns that journal into spans after the run ends. The engine never waits on export.

`WorkflowTraceExport` watches the runs the runtime publishes. When a run is Completed, Failed or Cancelled, it reads that run's journal, builds its spans and puts them on the exporter's queue. A run is exported at most once (the last 500 exported run ids are remembered), and a run that ended before export was turned on is never sent: turning export on does not backfill history.

## Span tree

~~~
invoke_workflow {workflow}            INTERNAL   one per run
└── invoke_agent {role}               INTERNAL   one per task attempt (TaskStarted … its end)
    └── chat                          CLIENT     one per ProviderUsageRecorded
~~~

- An attempt ends at the task's `TaskCompleted` (status OK), `RetryScheduled`, `TaskEscalated`, `TaskFailed` or `TaskCancelled` (status ERROR, with `error.type` `retry_scheduled`, `escalated`, `task_failed` or `cancelled`), or at the next `TaskStarted`. One still open closes when the run does.
- A chat span ends when the usage was recorded and starts `latencyMillis` earlier when the provider reported it. Its parent is the task's open attempt, else its latest attempt, else the workflow span.
- Approvals, verification failures, human decisions and artifacts become span events (`aive.approval_required`, `aive.approval_decision`, `aive.verification_failed`, `aive.human_decision_required`, `aive.artifact`) on the open attempt, or on the workflow span.
- Ids are deterministic: the trace id is a 128-bit hash of the run id, and each span id a 64-bit hash of run, task, attempt and position. The same journal always gives the same trace.

## Attributes

From the [OpenTelemetry GenAI semantic conventions](https://github.com/open-telemetry/semantic-conventions-genai):

| Attribute | On |
|---|---|
| `gen_ai.operation.name` (`invoke_workflow`, `invoke_agent`, `chat`) | all |
| `gen_ai.workflow.name` (the workflow definition id) | workflow |
| `gen_ai.agent.name` (the role id) | agent |
| `gen_ai.provider.name` (well-known value where one exists: `openai`, `anthropic`, `gcp.gemini`, `x_ai`, …; otherwise the provider id) | chat; agent when one provider served the attempt |
| `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens` (summed on agent and workflow spans) | all, when reported |
| `gen_ai.conversation.id` (the run id) | all |
| `error.type` | spans that ended in error |

The Aive's own: `aive.usage.cost_usd` (no GenAI convention for cost exists yet), `aive.usage.cache_hit_fraction`, `aive.provider.id`, `aive.provider.run_id`, `aive.workflow.run_id`, `aive.project.id`, `aive.task.id`, `aive.task.attempt`, `aive.executor.kind` (the executor's kind only, never a script's source or an approval's label), `aive.artifact.kind`, `aive.approval.approved`.

`gen_ai.request.model` is not set: the journal does not record the model.

## Content

Prompt and output content is **not exported** unless a second, separate toggle, **Include prompts and outputs**, is on (it is shown only while export is on). With it on, and only then, the trace also carries:

- `aive.workflow.objective` on the workflow span,
- `aive.artifact.label` and `aive.artifact.text` on artifact events,
- `aive.reason` on approval, verification and human-decision events, and the failure reason as the span status message.

Each is cut to 4,096 characters. Everything else (structure, timing, ids, token counts, cost) is exported whenever export is on.

## OTLP/HTTP JSON exporter

`OtlpTraceExporter` posts `ExportTraceServiceRequest` JSON (`resourceSpans › scopeSpans › spans`, resource `service.name=aive`, scope `com.hereliesaz.aive.workflow`) to `{endpoint}/v1/traces` with the project's Ktor client. Ids are lowercase hex, enums integers, nanosecond times and `intValue`s strings, as the OTLP JSON mapping requires. No OpenTelemetry SDK is used.

- **Endpoint.** `https://` only; plain `http://` is accepted for `localhost`, `127.0.0.1` and `::1`. No user info, query or fragment. `/v1/traces` is appended unless present.
- **Headers.** `name=value` pairs separated by commas or new lines (the `OTEL_EXPORTER_OTLP_HEADERS` form). `Content-Type`, `Content-Length`, `Host`, `Transfer-Encoding` and `Connection` are refused.
- **Never blocks.** `enqueue` never suspends: spans go into a bounded queue (2,048) that drops the oldest when full. A worker coroutine drains it in batches of up to 256.
- **Retry.** Network errors and HTTP 429, 502, 503 and 504 are retried up to 5 attempts with exponential backoff (1 s doubling, capped at 30 s). Any other non-2xx drops the batch. A batch that never gets through is dropped; nothing is persisted for later.
- Settings are re-read for every batch, so turning export off stops sending at once.

## Storage

Settings live in the app's local settings (`aive.telemetry.otlp.v1`; exported run ids under `aive.telemetry.otlp.exported-runs.v1`). That key is not in the setup-file allowlist, so neither the endpoint nor the headers are written to an exported `.aivesetup` file. Headers are **not** kept in the platform credential store; use an ingest key made only for sending traces. See [PRIVACY.md](../PRIVACY.md#trace-export-opentelemetry) and [THREAT_MODEL.md](../THREAT_MODEL.md#trace-export-opentelemetry).

## Limits

- Only runs the runtime publishes as its live (or viewed) run are seen. A nested child run, or a run cancelled because another was launched over it, is not exported on its own.
- A batch is handed over at most once: a run is marked exported when its spans are queued, so a batch dropped after retries is not sent again.
