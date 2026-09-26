# Live runtime acceptance

This branch exists to exercise Aive's provider-neutral live runtime acceptance gate through the
centralized workflow system.

The acceptance test must use a configured real provider through the normal provider contract and
prove the complete durable lifecycle:

1. create a project and materialize a workflow definition;
2. define the workflow objective;
3. start real provider-backed work and receive the provider plan;
4. pause at the explicit human approval gate without inventing numeric progress;
5. export the project as an `.ive` bundle;
6. reconstruct the runtime from a fresh durable store and import that project;
7. reconnect the same provider run rather than allocating a replacement;
8. approve provider execution and reach provider completion;
9. exercise a deterministic failure and human escalation decision;
10. retry the failed step only after approval;
11. collect durable verification and review artifacts;
12. pass the explicit terminal approval gate;
13. reach a completed workflow state; and
14. reconstruct the runtime again and prove the terminal result remains completed.

The workflow is intentionally provider-neutral. It may use OpenAI, Anthropic, Gemini, or xAI when
the corresponding credential is configured. When no hosted-provider credential is available, the
central acceptance workflow bootstraps a real local Ollama server and exercises Aive's existing
OpenAI-compatible provider transport against SmolLM2. Jules is not part of this acceptance
requirement.

`AIVE_LIVE_PROVIDER` may also name any hosted OpenAI-compatible provider (for example `groq` or
`kilo`). Its key comes from `AIVE_LIVE_HOSTED_CREDENTIAL` and its model from
`AIVE_LIVE_HOSTED_MODEL`; the keyless providers (`kilo`, `llm7`, `ovhcloud`) need neither. Run it
locally with:

~~~
AIVE_LIVE_RUNTIME_VERIFICATION=1 AIVE_LIVE_PROVIDER=kilo \
  ./gradlew :providers:llm:desktopTest --tests '*ProviderNeutralLiveRuntimeVerificationTest'
~~~

The test prints `[live]` lines (time to each gate, the provider's plan, and the kind of each provider
artifact) to the test report's system-out. If the provider task fails while a plan or escalation is
awaited, the test stops at once and reports the provider's reason instead of waiting out the
timeout.

### Recorded local runs

- 2026-09-26, desktop JVM, Kilo Gateway (`kilo-auto/free`, no key): all 14 steps passed in 1 m 37 s.
  Plan gate reached in 12–18 s; the provider's artifact was a `TaskPlan`.
- 2026-09-26, desktop JVM, LLM7 (no key): passed once (8.2 s), then failed on HTTP 503 and later
  HTTP 429 "Daily token quota exceeded". Anonymous keyless tiers are not reliable enough for a gate.

These are local runs, not the centralized verification below.

A successful centralized Live Runtime Verification run against this branch is the evidence required
before the matching roadmap items in `TODO.md` are marked complete: the on-runtime verification item
under "Runtime integrity audit" and the "Make one complete workflow actually work end-to-end" section.
Other checked P0 items record implementation with automated test coverage, not live verification.


## Android runtime recovery expectations

A runtime retry must not restart a large local-model transfer from byte zero. Memory and
orchestration artifacts use the resumable Android downloader described in
[`PERSISTENCE.md`](PERSISTENCE.md): retained `.download` bytes are resumed with HTTP Range
requests and the final file is accepted only after size/digest verification.

The acceptance surface should distinguish provider/runtime state failure from artifact transport
failure. A transport message such as a request timeout or incomplete `Content-Length` is actionable
download state; it must not be misreported as a completed model install or an empty workflow run.

The Inbox likewise reflects runtime truth. It may report the download/runtime failure, but a partial
asset is not a task artifact and is never evidence of task completion.
