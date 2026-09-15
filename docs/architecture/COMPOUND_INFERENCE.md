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

The currently authorized default is `Single`. The contract also defines future strategies:

- `CentralizedMixtureOfAgents`
- `ParallelIndependent`
- `SequentialPipeline`
- `Escalate`

The planner must select a strategy based on task structure rather than assuming that more agents are always better. Strongly sequential work should normally remain single-agent or sequentially pipelined unless measured evidence justifies another topology.

## Genealogy-based governance

Genealogy records information ancestry. It does not decide truth.

Each model invocation has a stable invocation identity plus direct ancestry such as producing task runs, upstream artifacts, recalled memory addresses, tool-evidence identifiers, and later prompt/config/model/adapter/provider fingerprints.

`WorkflowEngine` supplies real project, workflow-run, workflow-definition, task, and role coordinates before provider dispatch. Dependency artifacts contribute producing task-run IDs and artifact IDs to the request genealogy.

Future genealogy governance will persist these relationships as a graph and expose them to verification and aggregation. Governance may flag common ancestry, unsupported consensus, circular derivation, missing evidence, or insufficient independence. It must not silently rewrite a worker's reasoning or convert memory into a truth database.

## Blueprint inference infrastructure

The Blueprint layer is now a real runtime subsystem, not only a design target.

Each `AgentProviderRegistry` owns one `BlueprintCompoundInferenceFabric`, and the `ProviderBackedManagedSessionGateway` routes every started provider-backed session through that shared fabric before `provider.start(...)`.

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
- observed provider artifacts
- provider usage/resource reports
- terminal completion, failure, or cancellation

Stream records carry the stable genealogy invocation ID. The stream does not replace workflow events; it is the inference-specific communication/observability channel that centralized MoA and later compound strategies will reuse.

### Task and data planner

`CompoundInferenceTaskPlanner` is now invoked before every provider-backed session starts. It receives:

- the complete `AgentTaskRequest`
- selected provider identity
- observed agent capabilities
- symbolic governed data inputs
- the authorized compound-inference context

The baseline planner produces `InferenceExecutionPlan` and honors the strategy/budgets already authorized by orchestration. It intentionally does **not** select an unimplemented topology yet. Resource-aware topology selection begins only when the corresponding execution strategies are real and testable.

### Resource telemetry

`InferenceResourceTelemetry` records provider-reported input/output tokens, cost, cache-hit fraction, and latency against the same invocation identity used by the stream and genealogy.

This telemetry is diagnostic/planning input. It does not affect workflow correctness or completion claims.

### Current Blueprint limitations

The first slice is intentionally not presented as enterprise-complete:

- registries, streams, and inference telemetry are runtime-local and are not yet restored after process restart
- local model assets are not yet registered in the model registry
- non-agent executor evidence enters the data registry when it becomes context for provider-backed inference, not immediately at system-executor production time
- the planner does not yet choose among multiple implemented compound topologies
- stream records are not yet the persisted genealogy graph

Those are explicit follow-up items in `TODO.md`.

## Centralized mixture of agents

Centralized MoA is the next major execution strategy. It is a governed subgraph, not an unstructured swarm.

```text
input
  |
  +--> proposer A --+
  +--> proposer B --+--> aggregator --> verifier --> output
  +--> proposer C --+
```

Required properties:

- proposer contexts are isolated unless the topology explicitly permits sharing
- proposer outputs are typed artifacts/stream records rather than implicit transcript inheritance
- the aggregator receives bounded, validated candidate outputs
- genealogy preserves candidate ancestry
- common ancestry is not mistaken for independent consensus
- agreement is not itself verification
- verification uses evidence and acceptance criteria
- collaboration depth and candidate count are policy-bounded
- strongly sequential tasks are not automatically converted into MoA

The existing workflow DAG, concurrency limits, artifact model, Blueprint stream fabric, genealogy, and independent verification machinery are the foundation for this strategy.

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
3. Genealogy persistence and governance.
4. Centralized MoA proposer/aggregator/verifier execution.
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
- automatic dependency artifact ancestry
- `BlueprintCompoundInferenceFabric`
- `InferenceModelRegistry`
- `InferenceAgentRegistry`
- `InferenceDataRegistry`
- `InferenceStreamFabric`
- `InferenceResourceTelemetry`
- `CompoundInferenceTaskPlanner`
- provider-session preparation through the fabric
- provider artifact, usage, and terminal stream recording

Not yet implemented:

- persisted genealogy graph and governance policy
- persisted/restored inference registries and streams
- direct non-agent executor stream ingestion
- resource-aware automatic topology selection
- centralized MoA execution
- generalized shared-base/LoRA model library integration
- DSPy optimization pipeline
- remaining orchestration utility models
- BitNet runtime/model family
- Skeleton-of-Thought execution

No item above should be represented as complete until it has real callers, tests, and runtime verification appropriate to its target platform.
