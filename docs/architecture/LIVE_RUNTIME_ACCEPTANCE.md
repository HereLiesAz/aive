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
the corresponding credential is configured, or a keyless provider (`kilo`, `llm7`, `ovhcloud`) when
the `provider` dispatch input names one. Pull-request runs from `acceptance/live-runtime-verification`
take no input and use `kilo`. When a manual run names no provider and no hosted-provider credential
is available, the central acceptance workflow bootstraps a real local Ollama server and exercises Aive's existing
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

The test lives in `providers/llm/src/commonTest` and runs on every target. Each target reads the
same settings from a different place: desktop from the process environment, an Android device from
instrumentation arguments, and JS/Wasm browsers from the Karma client configuration that
`providers/llm/karma.config.d/live-runtime-verification.js` fills from the Gradle environment. With
`AIVE_LIVE_RUNTIME_VERIFICATION` unset the test skips on every target, and a live run never reuses an
up-to-date or cached Gradle test result.

Browsers enforce CORS. The Kilo Gateway (`https://api.kilo.ai/api/gateway`) sends no
`Access-Control-Allow-Origin` header, so it cannot be called from a browser; the keyless providers
that work there are `llm7` and `ovhcloud`, which both send `access-control-allow-origin: *`. Both are
rate-limited anonymous tiers. LLM7's catalog default is its `default` alias (a named model,
`GLM-5.3-Flash`, answered HTTP 400 "currently unavailable" on 2026-09-29);
`AIVE_LIVE_HOSTED_MODEL` still overrides it. Web builds resolve h2g2 from Maven Local (see `settings.gradle.kts`),
so publish it first, as CI does:

~~~
./gradlew -p vendor/conveyance-h2g2/vendor/Conveyance \
  :conveyance-core:publishToMavenLocal :conveyance-compose:publishToMavenLocal
./gradlew -p vendor/conveyance-h2g2 publishToMavenLocal

AIVE_LIVE_RUNTIME_VERIFICATION=1 AIVE_LIVE_PROVIDER=llm7 AIVE_LIVE_HOSTED_MODEL=default \
  ./gradlew -Phaive.useLocalH2g2=false -Phaive.useMavenLocalH2g2=true \
  :providers:llm:jsBrowserTest :providers:llm:wasmJsBrowserTest \
  --tests 'com.hereliesaz.geministrator.providers.llm.ProviderNeutralLiveRuntimeVerificationTest'
~~~

Use the fully qualified class name: in the browser test runner the `*ProviderNeutral...Test` pattern
matches no test, and the task then succeeds without running anything. Karma needs a Chrome binary
(`CHROME_BIN`; when running as root, a wrapper that adds `--no-sandbox`). When `HTTPS_PROXY` is set,
the Karma config starts the headless browser with `--proxy-server` pointing at it, and the browser
must trust that proxy's certificate authority. In the browser the `[live]` lines appear only in the
test report's system-out (`providers/llm/build/test-results/<task>/`), not on the console.

On an Android device or emulator the settings are instrumentation runner arguments. No CORS applies
there, so Kilo should be usable; no device run has been recorded yet:

~~~
./gradlew :providers:llm:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.AIVE_LIVE_RUNTIME_VERIFICATION=1 \
  -Pandroid.testInstrumentationRunnerArguments.AIVE_LIVE_PROVIDER=kilo \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hereliesaz.geministrator.providers.llm.ProviderNeutralLiveRuntimeVerificationTest
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
- 2026-09-28, desktop JVM in a cloud container, Kilo Gateway (no key), `main` at `dccfa26a`: passed
  in 26 s. Plan gate after 12.5 s, failure escalation after 10.3 s; the provider's artifact was a
  `TaskPlan`.

- 2026-09-29, JS in headless Chromium 141 (Karma, through the container's HTTPS proxy), LLM7 with
  `AIVE_LIVE_HOSTED_MODEL=default` (no key): passed. Plan gate after 4.2 s, failure escalation after
  3.3 s, release gate after 3.0 s; the provider's artifact was a `TaskPlan`.
- 2026-09-29, Wasm in headless Chromium 141, same LLM7 setup: passed. Plan gate after 2.1 s, failure
  escalation after 1.0 s, release gate after 3.1 s; the provider's artifact was a `TaskPlan`.
- 2026-09-29, JS in headless Chromium, LLM7 with its catalog default model: failed at once with
  HTTP 400 "Model 'GLM-5.3-Flash' is currently unavailable" (`model_unavailable`).
- 2026-09-29, JS and Wasm in headless Chromium, OVHcloud (no key, default
  `Qwen3-Coder-30B-A3B-Instruct`): failed. OVHcloud answered HTTP 429 "API rate limit exceeded"
  (checked from the same browser and with curl), and the provider task stayed in `Planning` with no
  progress message until the 300 s plan-gate timeout, instead of failing with the provider's reason.
  A JS retry after the limit had briefly cleared timed out the same way. Fixed since: a hosted
  text provider's refusal now ends the run as `Failed` with the provider's reason (for example
  "OVHcloud: HTTP 429 …") instead of reaching the gateway as an observer fault that it retried with
  backoff.
- 2026-09-29, desktop JVM, Kilo Gateway (no key), after the move to the common test tree: passed.
  Plan gate after 11.5 s, failure escalation after 60.6 s; the provider's artifact was a `TaskPlan`.
- 2026-09-29, desktop JVM, Kilo Gateway (no key), `launchFromObjectiveThroughTheLivePlanner`:
  project name and objective through `launchOrchestratedWorkflow` with Kilo as the planner, the
  planned DAG persisted, and its task dispatched to Kilo: passed twice (8.5 s and 12.5 s to the
  first provider response). Earlier attempts failed twice. Once, `kilo-auto` returned an empty
  reply ("did not contain assistant text"). The router picks a different model per request; this
  did not recur, and the launch reports it as a failure rather than retrying. Three times, the plan
  used a role no linked provider could staff ("No agent provider satisfies required capabilities
  [Research]"). Fixed: the planner is now offered only roles a linked provider can staff.
- Same day, both live tests after test runtimes stopped sharing the platform's durable inference
  store (each workflow store now carries its own): the lifecycle test passed in 25 s.

These are local runs, not the centralized verification below.

### Centralized runs

- 2026-09-29, pull-request run from `acceptance/live-runtime-verification` at `00f9bb6b`, Kilo
  Gateway (no key): passed. Central run
  [36508417915](https://github.com/HereLiesAz/workflows/actions/runs/36508417915); the Gradle build
  including the lifecycle test took 4 m 52 s.

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
