# The Aive — TODO

This is the canonical near-term roadmap for The Aive. It is ordered by product dependency: make the execution model correct first, then make it genuinely usable, then broaden providers and delivery.

Tracking issue: #35

## P0 — Make the runtime model correct

- [x] Separate **task responsibility** from **executor identity**.
  - `TaskDefinition` must stop assuming every task is backed by a `RoleDefinitionId`.
  - Introduce an executor-neutral contract that can represent role-backed agent work, GitHub Actions, test runners, deployments, repository operations, human approvals, external services, and nested Haive workflows.
  - Preserve role semantics for responsibility/authority without forcing non-agent work to masquerade as an employee.
- [x] Refactor dispatch around executor capabilities rather than role-only provider assignment.
- [x] Update workflow validation for the new executor model.
- [x] Update persistence/schema for executor-neutral task definitions and runs, with an explicit migration from the current schema.
- [x] Update workflow events so executor assignment/progress/completion are neutral across agent and non-agent execution.
- [x] Extend `WorkflowMindMapProjection` so node identity can come from role, executor type, or system operation without inventing fake roles.
- [x] Add tests for mixed workflows: agent + GitHub Action + approval + deployment in the same DAG.

## P0 — Wire the live runtime into the app

- [x] Remove the sample `ActiveWorkflow` as the normal startup path; keep it only as preview/demo data if still useful.
- [x] Create the real application bootstrap that builds persistence, provider registry, workflow engine, and runtime publisher.
- [x] Feed live `WorkflowDefinition` + `WorkflowRun` state into `LiveWorkflowPresentation` continuously.
- [x] Make selection/technical inspector survive live state updates.
- [x] Show real task artifacts, attempts, blocking reasons, provider/executor IDs, and progress in the inspector.
- [x] Add explicit empty, loading, disconnected, failed-to-resume, and no-project states.

## P0 — Runtime integrity audit

- [x] Enforce terminal workflow/task guards and legal task transitions across provider and system executors.
- [x] Make runtime cycles single-writer and cover concurrent dispatch against duplicate external work.
- [x] Resume durable provider/system sessions instead of redispatching existing external work.
- [x] Correlate GitHub workflow dispatches to the exact returned workflow-run ID.
- [x] Keep provider completion behind explicit approval and route application approval through durable gates.
- [x] Make plan approval rejection, transport failure, recovery, and audit events consistent.
- [x] Make failure-escalation gates actionable from the application boundary.
- [x] Commit failure-escalation gate decision, workflow transition, and audit event atomically.
- [x] Preserve retry-attempt artifact identity and deduplicate replayed provider artifacts.
- [x] Replace whole-snapshot event rewrites with append-only Settings event journaling.
- [x] Preserve reconstructable workflow-created events and strict approval-gate identity with legacy compatibility.
- [x] Build and stage both JS and Wasm outputs for GitHub Pages.
- [x] Add regression coverage for concurrency, approvals, escalation atomicity, persistence, transitions, artifacts, and GitHub dispatch correlation.
- [ ] Complete explicit on-device/on-runtime verification of launch → provider approval → execution → failure escalation → restart/resume → terminal outcome using real integrations.

## P0 — Compound inference foundation

- [x] Document the compound-inference architecture and preserve the existing human-like memory/reconsolidation authority boundary.
- [x] Add provider-neutral compound-inference strategy and direct genealogy contracts.
- [x] Attach baseline single-model compound-inference metadata to every `AgentTaskRequest`, including direct dependency artifact ancestry.
- [x] Populate complete workflow/project/task/role coordinates before provider dispatch so genealogy is fully namespaced without fallback inference.
- [x] Persist the genealogy graph and expose it to verification/aggregation without turning it into a truth database.
- [x] Add genealogy governance for common ancestry, unsupported consensus, circular derivation, missing evidence, and independence checks.
- [x] Establish the Blueprint-style runtime fabric: model/agent registry, data registry, typed stream fabric, task/data planning boundary, and provider resource telemetry for provider-backed inference.
- [x] Persist/restore inference registries, streams, and resource history, and ingest non-agent executor evidence directly instead of only when it later enters model context.
- [x] Make task/data planning resource-aware and capable of choosing an implemented execution topology rather than only honoring the already-authorized strategy.
- [x] Implement centralized mixture-of-agents as a governed proposer → genealogy gate → aggregator → verifier subgraph with bounded candidate and aggregation budgets.
- [x] Generalize the Epoch-8 FP16/INT8/LoRA specialist assets into a reusable local model library with shared-base residency where supported and merged-model fallback where not.
- [ ] Build the DSPy/GEPA/MIPRO-style offline optimization and release pipeline for small specialist/control models. Prompt/program optimization, held-out/adversarial gates, manifests, and bundles exist; remaining work is specialist training/export, catalog registration, and runtime consumption.
- [ ] Finish the local orchestration utility family: Memory Query Composer, Context Packer, Agent Router, Tool Router, Handoff Composer, Escalation Gate, Completion Gate, Execution State Summarizer, and Verification Planner. Contracts, deterministic baselines, unit tests, production runtime wiring, and the injectable specialist-family seam exist; remaining work is releasing trained specialist artifacts and selecting them through that seam.
- [ ] Benchmark BitNet b1.58-native specialist models against the existing Qwen/ONNX family before adopting them. Deployment-readiness gates are documented; the remaining benchmark requires task-equivalent BitNet specialists and same-machine quality/performance measurements.
- [x] Implement Skeleton-of-Thought as a governed skeleton → independence analysis → parallel expansion → aggregation → verification strategy after the inference fabric is established.

## P0 — Make one complete workflow actually work end-to-end

- [ ] Create/import a project.
- [ ] Define an objective.
- [ ] Materialize a workflow definition.
- [ ] Approve any required plan/specification gates.
- [ ] Dispatch provider-backed work through a configured real provider.
- [ ] Reconcile provider progress without fabricating percentages.
- [ ] Persist and resume the run after process/browser restart.
- [ ] Collect artifacts.
- [ ] Run verification/review stages.
- [ ] Reach a terminal run state with a clear outcome.
- [ ] Verify this flow on Android, Desktop, JS, and Wasm where provider/browser constraints permit.

## P1 — GitHub as a first-class executor/integration

- [x] Add a repository integration boundary separate from agent providers.
- [x] Represent GitHub Actions jobs/steps as real workflow execution rather than agent roles.
- [x] Read workflow run/job/step status and project it into task progress.
- [x] Capture build/test artifacts and attach them to Haive task/run artifacts.
- [x] Support repository operations needed by workflows: branch, commit, PR, merge-state checks, and release metadata.
- [x] Add explicit approval/policy boundaries before destructive or publishing operations.
- [x] Handle GitHub failures, cancellation, reruns, and stale runs cleanly.

## P1 — Workflow authoring and governance

- [x] Build the workflow template editor around the DAG rather than a generic form.
- [x] Add/create/edit role definitions and standing instructions.
- [x] Add executor selection/policy per task or task class.
- [x] Make injected policy work visible: environment planning, pre-code verification, post-code testing, independent review, release gates.
- [x] Add human approval UI with clear evidence, decision scope, and consequences.
- [x] Add retry/escalation policy UI.
- [x] Add bounded concurrency controls.
- [x] Validate workflows before execution and explain invalid dependency/policy states in human language.

## P1 — Provider system

- [x] Finish Jules as the reference provider implementation.
- [x] Define provider configuration UI and secure credential handling per platform.
- [x] Add provider health/capability reporting.
- [x] Add provider-neutral token/cost/latency telemetry where providers expose it.
- [x] Implement prompt-reuse telemetry without making cache behavior part of workflow correctness.
- [x] Add a second provider to prove interchangeability; native OpenAI, Anthropic, Gemini, and xAI adapters plus OpenAI-compatible hosted providers now share the provider-neutral task contract.
- [x] Test provider substitution for the same role/task contract.

## P1 — Persistence and recovery

- [x] Add schema migrations; never silently reinterpret incompatible persisted workflow state.
- [x] Add corruption/recovery handling and a user-visible recovery path.
- [x] Ensure active provider/executor sessions reconnect rather than duplicate after restart.
- [x] Add run export/import for debugging and portability.
- [x] Decide when Settings-backed storage has reached its scale limit and implement SQL/IndexedDB backends behind the existing repository contracts.
- [x] Add retention/deletion controls consistent with `docs/PRIVACY.md`.

## P1 — H2G2 execution surface

- [x] Keep the mindmap as a projection of runtime truth; do not put execution state into renderer state.
- [x] Add distinct visual identities for non-agent executor nodes without losing the shared H2G2 language.
- [x] Preserve role personality motion + inherited ancestry motion for role-backed nodes.
- [x] Define equivalent motion/behavior rules for system executors.
- [x] Make progress fill work for exact, indeterminate, phase-only, blocked, waiting, and terminal states.
- [x] Improve large-DAG navigation: focus, pan/zoom, branch isolation, and selected-node tracking.
- [x] Improve tiny-screen/mobile behavior without turning the product into a conventional dashboard.
- [x] Accessibility pass: reduced motion, contrast, semantics, keyboard/focus, screen-reader labels.

## P1 — Product shell

- [x] Replace placeholder screens for Company, Artifacts, and Inbox with live runtime data; Workflows remains static templates.
- [x] Project chooser / recent projects.
- [x] Run history and resumable active runs.
- [x] Artifact browser with provenance (live artifacts from task runs, grouped by task).
- [x] Inbox for approvals, failures, escalations, and requests for human attention.
- [x] Settings shows connected providers; static fallback when none configured.
- [x] Surface The Aive icon/brand consistently across Android, Desktop, and Web.

## P1 — Delivery

- [x] Keep `main` CI green across shared tests, Jules provider, Android, Desktop, JS, and Wasm.
- [x] Confirm Android release signing from reconstructed keystore material on CI.
- [x] Add Google Play publishing using `PLAY_SERVICE_ACCOUNT_JSON` as a separate publishing job.
- [x] Automatically publish the desired Google Play internal-testing track after a successful release build.
- [x] Ensure versionCode/versionName have one clear source of truth and cannot regress.
- [x] Verify GitHub Pages deployment after the rename to `aive` and `haive.js`.
- [x] Package Desktop icon/metadata correctly for each supported OS.
- [x] Add release notes/changelog generation from actual shipped changes.

## P2 — Security and privacy hardening

- [x] Implement platform-secure credential storage; credentials must never enter workflow persistence.
- [x] Audit provider payloads so users can see what context leaves the device before execution.
- [x] Add optional redaction/exclusion rules for files/artifacts/context sent to providers.
- [x] Add clear data deletion controls for local workflow state.
- [x] Keep analytics/telemetry opt-in if product analytics are ever introduced; update the privacy policy before shipping any such collection.
- [x] Add dependency/security scanning without blocking development on noisy non-actionable findings.
- [x] Threat-model repository write access, workflow injection, malicious artifacts, prompt injection, and compromised provider responses.

## P2 — Cost and observability

- [x] Per-run/provider/executor timing.
- [x] Token and cost accounting where available.
- [x] Cache-hit/cache-write observability where supported.
- [x] Retry and failure-rate metrics.
- [x] Run timeline/event viewer.
- [x] Exportable diagnostic bundle with secrets stripped.

## P2 — Workflow composition

- [x] Nested Haive workflows as executors.
- [x] Reusable workflow fragments/subgraphs.
- [x] Conditional branches grounded in explicit outputs/evidence.
- [x] Fan-out/fan-in helpers without hiding the underlying DAG.
- [x] Cross-project workflows where permissions allow them.

## P2 — Brand and release polish

- [x] Final Android adaptive-icon safe-zone check at launcher sizes.
- [ ] Validate monochrome/themed Android icon on supported launchers.
- [x] Final Play Store icon/screenshots/feature graphic.
- [x] Splash/loading treatment using the approved Haive mark.
- [x] Make the privacy-policy URL stable for Play Store listing.
- [x] Audit all user-facing text for leftover Geministrator branding; internal historical package names may remain only where intentionally preserved.

## Definition of a useful alpha

The Aive is ready to call an alpha when a user can open a project, give it an objective, approve the generated plan where required, watch a real mixed workflow execute in the H2G2 mindmap, leave/restart the app without losing the run, inspect evidence and failures, intervene at explicit gates, and reach a verified terminal outcome — without any non-agent executor pretending to be an AI employee.
