# Workspace report gap analysis

"Architecting the Next-Generation Agent Management Workspace" is a Gemini-generated research report that recommends features for The Aive. Its author could not read this repository and inferred The Aive from other public repositories (IDEaz, azphalt, CueDetat and others), so its picture of the app is partly wrong. This document checks each concrete recommendation (report sections 2–8, including the section 8 roadmap) against the current source. **Status** is one of Done, Partly, Missing, or Doesn't fit (wrong for a local-first, single-user workflow app). The report's industry figures (METR horizons, Zylos and HarnessRouter analyses, "UHP", "MHS", protocol version numbers) were not verified. Where a recommendation depends on one of them, the Note says so.

Paths are repo-relative. `S/` stands for `shared/src/commonMain/kotlin/com/hereliesaz/geministrator/`.

## Durable execution and verification

| Recommendation | Status | Evidence | Note |
|---|---|---|---|
| Persist every workflow node in SQLite and survive restarts | Done | `S/persistence/SqlWorkflowPersistence.kt` (`~/.aive/workflows.db`, `aive-workflows.db`), `shared/src/commonMain/sqldelight-workflow`, append-only events via `S/persistence/RepositoryWorkflowEventSink.kt`, resume invariant in `docs/architecture/PERSISTENCE.md` | Web still uses `SettingsWorkflowPersistence`. |
| Git checkpoint per step | Partly | Local Git runs commit in an isolated worktree on a `haive/...` branch; `desktopApp/.../EntireRecorder.kt` and `EntireAgentPlugin.kt` attach Entire checkpoints (`docs/Entire.md`) | Desktop only, and one checkpoint per coding run, not one per DAG node. |
| Multi-hour approval pauses without losing context | Done | Persisted `ApprovalGate`s; `S/workflow/EnginePlanGate.kt` holds a task with no provider session; `S/domain/WorkflowGlobalPause.kt` | — |
| Time-travel: rewind to a node, edit, fork | Partly | `S/RunLineageLaunch.kt` (`Continue`/`Combine` finished runs), `S/HiveScreens.kt` `RunTimeline`, `S/Rehearsal.kt` | You can't rewind or re-run from a chosen task with an edited objective or output. |
| Executor never grades its own output; separate verifier | Done | `S/domain/Roles.kt`: `QaEngineer` (`Verify`), `CodeReviewer`, `AdversarialReviewer`, `Antagonist`; `VerificationPolicy.Required(verifierRoleId)` in `S/domain/Policies.kt`; "Verify objective" task in `S/workflow/StarterWorkflowFactory.kt`; Hall Monitor path in `S/workflow/HallMonitorGovernanceService.kt` | — |
| Tests designed independently of implementation | Done | `S/workflow/WorkflowDefinitionExpander.kt` injects `--pre-code-tests` and post-code test tasks for `CrashTestDummy` (`AuthorTests`) per `TestDesignPolicy` | — |
| Verifier backed by deterministic tests and linters | Partly | `TaskExecutor.TestRunner` and `TestRunnerExecutorIntegration` in `S/workflow/StandardExecutorIntegrations.kt` | The apps register no `TestRunnerClient` (`desktopApp/.../Main.kt`, `androidApp/.../MainActivity.kt`). Tests run only through GitHub Actions or Script executors. |

## Protocol stack

| Recommendation | Status | Evidence | Note |
|---|---|---|---|
| Full-duplex agent-to-agent channel | Done (opt-in) | `S/duplex/DuplexMcpPeer.kt` (equal peer `/mcp/a` or `/mcp/b` on an MCPServed Duplex broker over authenticated Streamable HTTP: `duplex_send`, `duplex_receive`, `duplex_ack`, `duplex_status`, SSE notifications), `DuplexAgentBus.kt`, `DuplexManagedSessionBridge.kt` and `DuplexTaskSessionAttachment.kt` bind it to one running task (`docs/architecture/DUPLEX_MCP.md`) | The Aive is a peer, not the broker (MCPServed is). No default pairing; a live Aive ↔ MCPServed acceptance test is still open. |
| MCP host: agents call tools from MCP servers | Missing | The duplex peer above carries messages between agents, not tool calls. `AzphaltStoreService` refuses `mcp` blocks in `llm` packages. | Tools reach agents only through provider-side agents (OpenCode, workspace agents). |
| MCP 2026-07-28 stateless `_meta`, Elicitation, Tasks | Missing | — | Depends on an MCP host. Approval gates and persisted `externalRunId`s already cover the same needs internally. Spec details not verified. |
| Code execution with MCP in a WASM sandbox | Missing | JS role scripts run in AndroidX JavaScriptEngine (`androidApp/.../AndroidJavaScriptExecutorIntegration.kt`, `docs/architecture/ROLE_EXECUTION_SOURCES.md`) | Pointless without an MCP host. The Aive does not use azphalt `runtime-wasm`. |
| A2A v1.0 (Google's Agent2Agent: Agent Cards, its task/message wire format) | Missing | Agent-to-agent messaging exists as the duplex channel above, in its own MCPServed wire contract; external entry points are the signature-gated `AiveRemoteService` and the relay (`docs/Remote-API.md`) | Speaking A2A would mean publishing Agent Cards and mapping duplex frames to A2A tasks; worth it only for agents outside this ecosystem. |
| Align azphalt manifests with UHP | Doesn't fit | — | Unverifiable spec. Azphalt conformance already lives in `S/azphalt/AzphaltRoleConformance.kt` and `AzphaltWorkflowConformance.kt`. |
| MHS hardware abstraction | Doesn't fit | — | No hardware in scope. Unverifiable spec. |

## Agentic UX

| Recommendation | Status | Evidence | Note |
|---|---|---|---|
| Typed event stream to frontends (AG-UI) | Partly | Internal `S/events/WorkflowEvent.kt` journal. Externally, `run_status` carries counts only (`docs/Remote-API.md`). | Remote clients get no task events, gates or artifacts. The relay host is stubbed (`RemoteAccessController.AVAILABLE = false`). |
| Mid-stream steering | Done | `S/ApplicationRuntimeMessaging.kt` `messageTask` → `ManagedSessionGateway.message`, called from `S/App.kt` | Only for providers that implement `sendMessage`. |
| Scoped sub-agent state | Done | Per-task context from dependency artifacts; memory banks per workflow (`MemoryBankScope`); `DuplexSessionObserver` bound to one handle | — |
| Cascading cancellation to children | Partly | `S/workflow/WorkflowRuntimeCoordinator.kt` `cancel` → `cancelSessions` stops agent sessions | `TaskExecutorIntegration` and `NestedWorkflowClient` have no `cancel`, so nested child runs and GitHub Actions or external runs keep going. |
| Activity panel separate from the steering thread | Partly | `S/LiveStepsFeed.kt`, `S/LiveTechnicalInspector.kt`, event journal | `AgentEvent.Thinking` is recorded to memory but not shown live. |
| Declarative generative UI (A2UI) | Partly | `S/addons/AddonScreen.kt` (fixed host catalog for add-ons), `RoleSurface` in `S/domain/Roles.kt` | Agents cannot emit UI. Low need: gates and creature panels cover the interactions. |
| Spatial canvas / DAG view | Done | `S/WorkflowTerrarium*.kt`, `S/WorkflowMindMap*.kt`, `S/HiveScreens.kt`, `docs/Swarm-OS.md` | — |
| Plan preview before execution | Done | Per-task `draftPlan` / `requirePlanApproval` (`S/providers/AgentProvider.kt`, `EnginePlanGate.kt`, `PlanRejectionService.kt`); whole-DAG Build mode and Rehearse | A plan gate takes approve or reject with a note; the user can't edit the drafted plan in place. |
| Progressive delegation from approval habits | Missing | Approval is static per task (`ApprovalPolicy`); THREAT_MODEL invariant 4 forces `HumanApproval` before mutating repository operations | Learned auto-approval would weaken invariant 4. If added at all, it belongs only on non-mutating gates. |

## Memory

| Recommendation | Status | Evidence | Note |
|---|---|---|---|
| Bitemporal episodic memory (event time vs ingest time) | Partly | `S/memory/MemoryTemporalIndex.kt` (derived from immutable episode timestamps), `MemoryTimeRange.kt` | Nodes carry only `createdAtEpochMillis`; there is no separate valid-time. |
| Hybrid retrieval: lexical + vector + graph, no LLM at recall | Done | `LexicalMemoryTool.kt`, `MemoryLexicalAssociations.kt`, `MemoryVectorEmbedder.kt`, `S/vector/HnswVectorIndex.kt`, `MemoryRelationKind` edges, cue-first hierarchy (`docs/architecture/MEMORY_BANKING_AND_ATTENTION.md`) | Not FTS5/BM25. |
| LLM conflict detector that merges or resolves facts | Doesn't fit | `MemoryRelationKind.Diverges` is advisory; `Supersedes` is written only by the deterministic coverage check (`S/memory/MemoryConsolidation.kt`); recall returns `conflicts` | By design, clerks never decide which memory is right (`docs/Memory-layer.md`). |
| Background consolidation and pruning pass | Done | `S/memory/MemoryConsolidation.kt`, `MemoryLayerController.kt` ("consolidates until idle") | — |
| User / project / agent namespaces | Done | `MemoryBankScope` (project, run, definition, task, role) in `S/memory/MemoryModels.kt`; workflow banks with read-only cross-bank provenance | Single user, so no user scope. |
| Self-updating procedural memory as diff-gated edits | Partly | Traces captured by `S/workflow/ProcedureTracingTaskExecutorIntegration.kt`; `S/memory/procedural/` (incl. `SqlProcedureStore.kt`); `S/Hatchery.kt` hatches disabled child roles that a person adopts | Nothing proposes edits to role instructions or `AGENTS.md`. `docs/architecture/PROCEDURAL_MEMORY.md` says durable SQL is unimplemented although `SqlProcedureStore` exists. |

## Computer use and edge routing

| Recommendation | Status | Evidence | Note |
|---|---|---|---|
| Accessibility-tree OS actuator | Doesn't fit | `androidApp/src/github/.../InstalledGeminiAccessibilityService.kt` drives only the Gemini app, as a keyless provider (opt-in, GitHub flavor) | The Aive manages workflows; it is not a computer-use agent. General accessibility use would also conflict with Play policy. |
| Vision fallback, logcat correlation, overlay HUD gate | Doesn't fit | — | Same reason. |
| Route small subtasks to on-device models | Partly | ONNX Runtime on Android (`androidApp/build.gradle.kts`, `AndroidOrchestrationSpecialists.kt`) and desktop (`DesktopOrtCausalGenerator.kt`); `S/orchestration/PreferLocalOrchestrationAgentRuntime.kt`; local memory clerks | Covers planning, orchestration utilities and memory clerks. Agent tasks always go to a provider. |
| Offload heavy work to a paired desktop or worker | Partly | `S/distributed/`, `computeRelay/`, `TaskExecutor.Distributed`, `docs/architecture/DISTRIBUTED_COMPUTE.md` | Hidden in the Play build. A shared pool token with no per-node identity (THREAT_MODEL open item). |

## Security, identity and FinOps

| Recommendation | Status | Evidence | Note |
|---|---|---|---|
| Per-agent workload identity (SPIFFE/WIMSE) | Doesn't fit | Provider credentials stay out of workflow state (THREAT_MODEL invariant 2) | No downstream service verifies agent identity. |
| Per-hop token exchange (RFC 8693), DPoP, audience binding | Doesn't fit | — | Same. Distributed-compute per-node identity is the one real gap (THREAT_MODEL open items). |
| Externalized policy engine (AuthZEN) | Partly | In-process authority: `RoleAuthority`, `ApprovalPolicy`, `S/addons/HostPermissions.kt`, invariant 4 | An external policy decision point (PDP) doesn't fit. |
| Context taint tracking blocks privileged calls | Missing | THREAT_MODEL "Remaining gaps" and open items (no injection scanning of artifacts) | Provider artifacts, repository files and research output enter context unlabelled. |
| OTel GenAI spans and OTLP export | Missing | `ProviderUsageRecorded` (tokens, cost, cache hit, latency) in `S/workflow/WorkflowEngine.kt`; `TaskUsage` | The data exists; there is no span model or exporter. |
| Execution waterfall timeline | Partly | `S/HiveScreens.kt` `RunTimeline` (ordered event list) | No durations or overlaps. |
| Harness-owned retries, not model-driven | Done | `RetryPolicy` (`maxAttempts`, `retryOn`) in `S/domain/Policies.kt`; observer backoff in `ProviderBackedManagedSessionGateway.kt` | — |
| Token and spend circuit breakers | Partly | `RoleDefinition.tokenBudget` shown as the Budget need (`S/CreatureNeeds.kt`); `CompoundInferencePolicy.ResourceAware.maxEstimatedCostUsd` | Display only. Nothing stops a task or run at the budget, and there is no run or hourly cap. |
| Cache static prompts across turns | Partly | `PromptReusePolicy`, `docs/architecture/PROMPT_CACHING.md` | `TextLlmProvider` declares `PromptCacheMode.Unsupported`, so the policy has no effect. |
| Signed, curated skill/package supply chain | Done | `S/azphalt/AzphaltPackageVerifier.kt` (integrity, signature, publisher pin, revocation) | — |

## Report claims about The Aive that are wrong

- **Provider coverage.** It is far wider than "8 hosted providers behind 3 adapter families plus 3 keyless". `S/ProviderCatalog.kt` and `providers/llm/.../HostedLlmProviders.kt` list OpenAI, Anthropic, Gemini and xAI, about 15 OpenAI-compatible hosts, the 3 keyless gateways, Moyai, store-installed `kind:"llm"` packages, and the workspace agents (OpenCode, GitLab, Local Git).
- **The relay.** It does not "stream approval requests and completion diffs". It carries `prompt`, `launch`, `watch` and `ping`, and its replies carry counts only. It is undeployed, and the Android host is stubbed out.
- **Engine model.** The Aive is already a persisted DAG workflow engine with gates, retries and reconnect-on-resume. The report casts it as a chat-loop "intent dispatcher" and proposes much of this as new.
- **Verifier placement.** It proposes adding the executor/verifier split before IDEaz's gate. The split already exists inside The Aive.
- **Opt-in multi-agent.** "Multi-agent is opt-in only" is CueDetat's agent rule, not The Aive's. The Aive is multi-agent by design.
- **Local runtime.** The Aive does not host azphalt's QuickJS `runtime-wasm`. It already ships ONNX Runtime itself, not only in another repository.

## Worth doing next

1. **Enforce `tokenBudget`, plus an optional per-run cost cap, in `WorkflowEngine`.** Usage already reaches `TaskUsage` every cycle, and the budget is shown but never acts. Small change; it stops runaway spend.
2. **Add `cancel` to `TaskExecutorIntegration` and `NestedWorkflowClient`, and call it from `WorkflowRuntimeCoordinator.cancel`.** Cancelling a parent today leaves child runs and Actions jobs running.
3. **Register a desktop `TestRunnerClient`** that runs in the Local Git worktree. It gives verification tasks deterministic evidence; the executor type and integration already exist.
4. **Provenance-label context evidence (user / provider artifact / repository / web).** Require `HumanApproval` before a mutating step whose inputs include unlabelled external content. This closes the top THREAT_MODEL gap.
5. **Re-run from a task.** Reset a chosen task and its descendants with an edited objective, building on persisted task runs and `RunLineageLaunch`. That is the useful part of "time travel".
6. **Implement provider prompt caching** for the Anthropic and Gemini adapters behind the existing `PromptReusePolicy` contract. It is a direct cost cut, and `cacheHitFraction` is already recorded.
