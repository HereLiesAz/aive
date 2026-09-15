# Compound inference architecture

This document defines The Haive's compound-inference architecture derived from the ideas evaluated in `../Composite Frontier Model Architectures.md` and the current runtime contracts.

The workflow DAG remains runtime truth. Compound inference is the governed execution fabric used by model-backed work inside that DAG; it is not a second workflow engine.

## Architectural invariant: memory remains human-like and non-epistemic

The existing memory architecture is intentionally preserved.

Haive manages conflicting memories by keeping related traces available, surfacing them associatively into active reasoning, allowing the conscious/orchestration layer to notice and reason about the disagreement, and banking the resulting experience back into memory for later consolidation.

Memory clerks therefore continue to organize, associate, condense, and retrieve what was thought, said, observed, requested, done, or produced. They do not decide which conflicting trace is true. Genealogy and compound inference strengthen provenance and reasoning without moving epistemic authority into the memory layer.

## Target architecture

```text
                         THE HAIVE
                            |
                 CompoundInferenceFabric
                            |
        +-------------------+-------------------+
        |                   |                   |
  Model/Agent Registry   Data Registry      Stream Fabric
        |                   |                   |
        |             memory / artifacts       |
        |             repositories / tools     |
        |             evidence / files         |
        |                                       |
        +------------ Task + Data Planner ------+
                            |
                     Workflow DAG
                            |
             +--------------+--------------+
             |              |              |
           Single      Centralized MoA    System
           model          subgraph        executor
             |              |              |
             +--------------+--------------+
                            |
                   Genealogy Governance
                            |
                    Evidence / Artifacts
                            |
                       Verification
                            |
                          Memory
```

System executors remain executor-neutral workflow nodes. They may participate in provenance, data, evidence, and governance, but they do not masquerade as model agents.

## Compound inference contract

Every provider-backed `AgentTaskRequest` carries `CompoundInferenceContext` containing:

- execution strategy
- direct `InferenceGenealogy`
- candidate budget
- aggregation depth

The default provider invocation remains `Single`. The contract also defines strategies for:

- `CentralizedMixtureOfAgents`
- `ParallelIndependent`
- `SequentialPipeline`
- `Escalate`

At the workflow-authoring layer, `TaskDefinition.compoundInferencePolicy` now authorizes either ordinary single execution or explicit centralized MoA materialization. Automatic strategy selection remains future work: the planner must eventually select a strategy based on task structure rather than assuming that more agents are always better. Strongly sequential work should normally remain single-agent or sequentially pipelined unless measured evidence justifies another topology.

## Genealogy-based governance

Genealogy records information ancestry. It does not decide truth.

Each concrete model invocation has an invocation identity plus ancestry such as producing task runs, upstream artifacts, upstream inference invocations, recalled memory addresses, tool-evidence identifiers, and optional prompt/configuration fingerprints.

`WorkflowEngine` supplies real project, workflow-run, workflow-definition, task, and role coordinates before provider dispatch. Dependency artifacts contribute producing task-run IDs, artifact IDs, and, when present, the exact inference invocation IDs that produced those artifacts.

Runtime genealogy governance is implemented. `GovernedCompoundInferenceFabric` registers every prepared concrete provider invocation in `InferenceGenealogyGovernanceRuntime` before provider execution. The deterministic evaluator can report:

- missing genealogy
- missing evidence when policy requires evidence
- common direct or transitive ancestry
- circular derivation
- pairwise structural independence
- insufficient independent members for a consensus claim
- unsupported consensus

The evaluator does not rank candidate quality, label claims true or false, resolve conflicting memories, or rewrite worker output. Its reports are structural provenance evidence only.

The genealogy graph is currently runtime-local. Durable persistence/restoration across process restart remains an explicit follow-up.

## Blueprint inference infrastructure

The Blueprint layer is now a real runtime subsystem, not only a design target.

Each `AgentProviderRegistry` owns one `BlueprintCompoundInferenceFabric`, wrapped by genealogy governance, and the `ProviderBackedManagedSessionGateway` routes every started provider-backed session through that shared fabric before `provider.start(...)`.

The first production slice is deliberately runtime-local. Workflow state, memory, repositories, provider sessions, and durable artifacts remain authoritative in their existing stores. The inference fabric indexes and coordinates those systems rather than duplicating their payload ownership.

### Model registry

`InferenceModelRegistry` tracks execution-capable model assets through `InferenceModelDescriptor`:

- logical model ID
- base model ID
- adapter ID
- precision / quantization label
- backend
- capabilities
- release digest

The registry exists now, but current provider-backed agents do not expose a portable model identity, so the runtime does not fabricate one. The Epoch-8 local model families will populate this registry when the generalized local model library is wired.

### Agent registry

`InferenceAgentRegistry` records the actual provider selected for a started session together with observed provider capabilities, prompt-cache modes, and environment-planning requirements.

This is an index of execution capability. `AgentProviderRegistry` remains the authoritative provider selector and still enforces repository compatibility and provider constraints.

### Data registry

`InferenceDataRegistry` provides symbolic references to governed inputs without flattening or copying payloads into a second store.

Today, dependency `ArtifactRef`s become entries such as `artifact:<artifact-id>` and preserve:

- artifact ID
- producing task-run ID
- label
- media type

The original artifact remains authoritative in workflow persistence. Memory, repository, tool-evidence, and imported-reference data kinds are represented by the same registry contract and will be populated by later integrations.

### Typed stream fabric

`InferenceStreamFabric` provides ordered per-invocation records with typed payloads. The current stream records:

- dispatch preparation and execution plan
- provider-run binding
- observed provider artifacts
- provider usage/resource reports
- terminal completion, failure, or cancellation

Concrete provider runs are bound to concrete invocation IDs so retry attempts and late events from older provider runs cannot cross-contaminate one another's genealogy or telemetry.

The stream does not replace workflow events; it is the inference-specific communication/observability channel that compound strategies reuse.

### Task and data planner

`CompoundInferenceTaskPlanner` is invoked before every provider-backed session starts. It receives:

- the complete `AgentTaskRequest`
- selected provider identity
- observed agent capabilities
- symbolic governed data inputs
- the authorized compound-inference context

The baseline planner produces `InferenceExecutionPlan` and honors the strategy/budgets already authorized by orchestration. It intentionally does **not** autonomously select a topology yet. Resource-aware topology selection begins only when the corresponding execution strategies are measured and safe to select automatically.

### Resource telemetry

`InferenceResourceTelemetry` records provider-reported input/output tokens, cost, cache-hit fraction, and latency against the same concrete invocation identity used by the stream and genealogy.

This telemetry is diagnostic/planning input. It does not affect workflow correctness or completion claims.

### Current Blueprint limitations

The first slice is intentionally not presented as enterprise-complete:

- registries, streams, resource history, and the genealogy graph are runtime-local and are not yet restored after process restart
- local model assets are not yet registered in the model registry
- non-agent executor evidence enters the data registry when it becomes context for provider-backed inference, not immediately at system-executor production time
- the planner does not yet automatically choose among multiple implemented compound topologies

Those are explicit follow-up items in `TODO.md`.

## Centralized mixture of agents

Centralized MoA is implemented as an explicit governed workflow subgraph rather than an unstructured swarm or a hidden mini-orchestrator inside a provider call.

```text
                         +--> proposer A --+
original dependencies ---+--> proposer B --+--> genealogy gate --> aggregator --> verifier --> downstream
                         +--> proposer N --+
```

`CompoundInferencePolicy.CentralizedMixtureOfAgents` authorizes a bounded set of proposer roles and one aggregator role. `CentralizedMixtureOfAgentsExpander` materializes the policy before execution into ordinary `TaskDefinition`s so persistence, concurrency, retries, escalation, artifacts, approvals, and runtime projection continue to use the normal workflow engine.

The first production-safe form has these rules:

- two to eight proposer roles are allowed
- proposers are isolated siblings and never depend on another proposer's output
- proposer roles may not have implementation authority or require repository-write capability
- proposers emit bounded `TaskPlan` candidate artifacts and do not mutate the repository
- each provider artifact is tagged with `haive.inferenceInvocationId`, allowing downstream genealogy to name the exact producing invocation
- a deterministic `haive.genealogy-governance` system-executor node runs after all proposers and before aggregation
- the governance gate requires at least two candidate invocation records and fails on missing genealogy, circular derivation, insufficient independence, or unsupported consensus
- the original task ID becomes the aggregator task, preserving its durable workflow identity and original required outputs
- aggregation preserves meaningful disagreement and cannot treat candidate agreement as verification
- a separate verifier role runs after aggregation and produces a `Verification` artifact
- downstream tasks that depended on the original task are also rewired to wait for the injected verifier
- the genealogy-gate executor is registered centrally by `ApplicationRuntime.create()`, so Android, Desktop, JS, and Wasm share the same runtime behavior while retaining their platform-specific executor integrations

This is centralized MoA **execution**, not automatic MoA selection. Tasks only become MoA when their workflow definition explicitly carries the MoA policy. Resource-aware automatic topology selection is a separate later step.

The current genealogy graph is still runtime-local. Therefore process-restart durability of the genealogy graph itself remains separate work and must not be inferred from ordinary workflow persistence.

## Specialized local model and LoRA library

Haive already has the seed of the specialist-library architecture in the Epoch-8 memory layer.

The Memory Epoch-8 release contains FP16 specialist artifacts, INT8 specialist artifacts, LoRA specialist artifacts, and a separate embedding specialist for association. The current Android production path primarily binds role-specific INT8 ONNX artifacts.

The next model-library step is to generalize those assets into `InferenceModelRegistry` entries and a reusable runtime library with shared-base residency where the backend supports safe adapter switching.

Target shape:

```text
shared local base model
    +-- Sectioner adapter
    +-- Salience adapter
    +-- Noun adapter
    +-- Verb adapter
    +-- Phrase adapter
    +-- Summary adapter
    +-- Category adapter
    +-- Condensation adapter
```

The same library pattern will later serve orchestration utilities:

- Memory Query Composer
- Context Packer
- Agent Router
- Tool Router
- Handoff Composer
- Escalation Gate
- Completion Gate
- Execution State Summarizer
- Verification Planner

Planner and Plan Repair remain a larger reasoning tier when necessary.

Runtime requirements include versioned adapter identity, cryptographic artifact verification, tokenizer/base compatibility checks, merged-model fallback when hot adapter switching is unavailable or unproven, hardware-aware placement, and measured execution reporting rather than assumed accelerator use.

## DSPy-optimized small models

DSPy belongs in the model-development pipeline, not in workflow correctness.

```text
Haive task contract
    -> typed signature
    -> training/evaluation set
    -> metric and adversarial gates
    -> DSPy/GEPA/MIPRO-style optimization
    -> optimized instructions/examples and/or adapter training data
    -> specialist model release
    -> runtime manifest
```

Small models should be optimized against the exact structured function they perform. Optimization must preserve role authority boundaries. A Completion Gate may be optimized to demand explicit evidence; a memory clerk may not be optimized into deciding truth or contradiction.

## Experimental phase: BitNet b1.58

BitNet b1.58 is an experimental backend/model-family direction, not a drop-in post-training quantization flag for existing Qwen artifacts.

Adoption requires BitNet-native model artifacts and benchmarking against the existing specialist family for task accuracy, latency, RAM, energy/thermals, startup time, supported hardware, and runtime portability.

Existing Qwen/ONNX paths remain until BitNet demonstrates an actual advantage for the relevant specialist workload.

## Experimental phase: Skeleton-of-Thought

Skeleton-of-Thought will be treated as another governed reasoning strategy:

```text
objective
   -> skeleton
   -> branch independence analysis
   -> parallel branch expansion
   -> genealogy-aware aggregation
   -> verification
```

It reuses the same workflow concurrency, genealogy, stream, planning, and verification contracts as centralized MoA. A skeleton branch is parallelized only when its dependencies permit it.

## Implementation sequence

1. Compound inference + direct genealogy foundation — implemented.
2. Blueprint runtime fabric — first production slice implemented; persistence/resource-aware planning remain.
3. Runtime genealogy governance — implemented; durable graph persistence remains.
4. Centralized MoA proposer → genealogy gate → aggregator → verifier execution — implemented; automatic topology selection remains.
5. Generalized local specialist/LoRA library registered into the model registry.
6. DSPy optimization and release pipeline.
7. Remaining local orchestration utility family.
8. BitNet b1.58 experiments.
9. Skeleton-of-Thought experiments.

## Current implementation status

Implemented and called by production runtime:

- `CompoundInferenceStrategy`
- `InferenceGenealogy`
- `CompoundInferenceContext`
- workflow/project/task/role orchestration coordinates on provider dispatch
- automatic dependency artifact ancestry and inference-invocation ancestry
- `BlueprintCompoundInferenceFabric`
- `InferenceModelRegistry`
- `InferenceAgentRegistry`
- `InferenceDataRegistry`
- `InferenceStreamFabric`
- `InferenceResourceTelemetry`
- `CompoundInferenceTaskPlanner`
- provider-session preparation through the fabric
- provider-run binding and retry-safe artifact/usage/terminal stream recording
- `InferenceGenealogyGraph`
- `InferenceGenealogyGovernanceRuntime`
- deterministic common-ancestry, cycle, evidence, independence, and consensus governance
- `CompoundInferencePolicy.CentralizedMixtureOfAgents`
- `CentralizedMixtureOfAgentsExpander`
- `GenealogyGovernanceExecutorIntegration`
- centralized runtime registration of the genealogy gate
- explicit proposer → governance → aggregator → verifier DAG execution

Not yet implemented:

- persisted/restored genealogy graph, inference registries, streams, and resource history
- direct non-agent executor stream ingestion
- resource-aware automatic topology selection
- generalized shared-base/LoRA model library integration
- DSPy optimization pipeline
- remaining orchestration utility models
- BitNet runtime/model family
- Skeleton-of-Thought execution

Physical-device/real-provider end-to-end verification remains separate from CI and is not claimed by this document.
