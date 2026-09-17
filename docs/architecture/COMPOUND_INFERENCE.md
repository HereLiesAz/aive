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

At the workflow-authoring layer, `TaskDefinition.compoundInferencePolicy` can authorize ordinary single execution, explicit centralized MoA materialization, or bounded resource-aware selection. `CompoundInferencePolicy.ResourceAware` never grants additional role authority. It authorizes a specific proposer set plus aggregator and requires at least one measurable resource ceiling. The resolver may select only an implemented safe topology: `Single` or centralized MoA.

Resource-aware selection is deliberately conservative. It considers task complexity, provider availability, and persisted provider cost/latency history. Missing history, unavailable participants, insufficient task complexity, or a projected budget overrun falls back to `Single`. Centralized-MoA latency is estimated as the slowest parallel proposer plus aggregator plus verifier.

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

A critical distinction applies to MoA: multiple legitimate proposers normally receive the same authorized task inputs. Shared source ancestry therefore means their agreement is **not independent evidence**, but it does not make their candidate analyses unusable. MoA governance blocks missing or circular lineage. Shared ancestry, insufficient independence, and unsupported-consensus findings remain visible to the aggregator and verifier as advisory provenance so agreement cannot be mistaken for proof.

The production genealogy graph is durable. `SettingsInferenceGenealogyGraph` persists structural ancestry with a versioned schema, and `AgentProviderRegistry` wires it into the default governance runtime. Persistence stores derivation coordinates only; it does not persist truth, confidence, contradiction resolution, or model conclusions.

## Blueprint inference infrastructure

The Blueprint layer is a real runtime subsystem, not only a design target.

Each `AgentProviderRegistry` owns a durable `SettingsCompoundInferenceFabric`, bootstrapped with the reusable local-model library and then wrapped by genealogy governance. The provider-backed session gateway routes every started provider-backed session through that shared fabric before `provider.start(...)`.

Workflow state, memory, repositories, provider sessions, and durable artifacts remain authoritative in their existing stores. The inference fabric indexes and coordinates those systems rather than duplicating their payload ownership.

### Model registry

`InferenceModelRegistry` tracks execution-capable model assets through `InferenceModelDescriptor`:

- logical model ID
- base model ID
- adapter ID
- precision / quantization label
- backend
- capabilities
- release digest

The registry is durably persisted through `SettingsInferenceStateStore`. The reusable local-model library now populates it lazily through `LocalModelBootstrapInferenceFabric`: the first model-registry read or provider dispatch registers the current local artifact catalog into the durable registry exactly once for that runtime. Provider-backed remote agents still do not fabricate portable local model identities.

### Agent registry

`InferenceAgentRegistry` records the actual provider selected for a started session together with observed provider capabilities, prompt-cache modes, and environment-planning requirements. Its entries are persisted and restored by the Settings-backed inference state store.

This is an index of execution capability. `AgentProviderRegistry` remains the authoritative provider selector and still enforces repository compatibility and provider constraints.

### Data registry

`InferenceDataRegistry` provides symbolic references to governed inputs without flattening or copying payloads into a second store.

Dependency `ArtifactRef`s become entries such as `artifact:<artifact-id>` and preserve:

- artifact ID
- producing task-run ID
- label
- media type

The original artifact remains authoritative in workflow persistence. Non-agent executor output is also indexed directly when system integrations report artifacts: `TaskExecutorIntegrationRegistry` wraps executor integrations with evidence indexing so system-produced evidence enters the durable data registry immediately instead of waiting for a later model prompt.

Memory, repository, tool-evidence, and imported-reference data kinds share the same symbolic registry contract. The registry does not take ownership of their underlying payloads.

### Typed stream fabric

`InferenceStreamFabric` provides ordered per-invocation records with typed payloads. The current stream records:

- dispatch preparation and execution plan
- provider-run binding
- observed provider artifacts
- provider usage/resource reports
- terminal completion, failure, or cancellation

Concrete provider runs are bound to concrete invocation IDs so retry attempts and late events from older provider runs cannot cross-contaminate one another's genealogy or telemetry.

The stream is durably persisted by `SettingsInferenceStateStore`. It does not replace workflow events; it is the inference-specific communication/observability channel that compound strategies reuse.

### Task and data planning

`CompoundInferenceTaskPlanner` is invoked before every provider-backed session starts. It receives:

- the complete `AgentTaskRequest`
- selected provider identity
- observed agent capabilities
- symbolic governed data inputs
- the authorized compound-inference context

The low-level planner produces `InferenceExecutionPlan` for an already-materialized task. Workflow-level topology selection happens earlier through `ResourceAwareCompoundInferencePolicyResolver`, before compound-inference DAG expansion. That resolver uses explicit `ResourceAware` authorization plus persisted telemetry and can choose only between `Single` and the implemented centralized-MoA subgraph.

This keeps topology choice visible in the normal workflow DAG rather than hiding a dynamic mini-orchestrator inside a provider call.

### Resource telemetry

`InferenceResourceTelemetry` records provider-reported input/output tokens, cost, cache-hit fraction, and latency against the same concrete invocation identity used by the stream and genealogy. Resource samples are persisted/restored through `SettingsInferenceStateStore` and are used as planning evidence by resource-aware topology selection.

Telemetry does not affect workflow correctness, evidence truth, or completion claims.

### Current Blueprint limitations

The current subsystem is still intentionally bounded:

- local shared-base adapter execution is capability-gated and only selected when a concrete runtime explicitly reports safe adapter switching; otherwise the planner chooses a verified merged artifact
- the existing Android ONNX production path therefore remains on merged INT8 Epoch-8 artifacts rather than assuming adapter hot-swapping
- resource-aware selection currently chooses only between `Single` and centralized MoA; other declared strategy shapes are not selected automatically
- resource estimates are historical means, not predictive model-based forecasts
- topology selection falls back to `Single` rather than guessing when historical evidence is insufficient

## Centralized mixture of agents

Centralized MoA is implemented as an explicit governed workflow subgraph rather than an unstructured swarm or a hidden mini-orchestrator inside a provider call.

```text
                         +--> proposer A --+
original dependencies ---+--> proposer B --+--> genealogy gate --> aggregator --> verifier --> downstream
                         +--> proposer N --+
```

`CompoundInferencePolicy.CentralizedMixtureOfAgents` authorizes a bounded set of proposer roles and one aggregator role. `CentralizedMixtureOfAgentsExpander` materializes the policy before execution into ordinary `TaskDefinition`s so persistence, concurrency, retries, escalation, artifacts, approvals, and runtime projection continue to use the normal workflow engine.

The production-safe form has these rules:

- two to eight proposer roles are allowed
- proposers are isolated siblings and never depend on another proposer's output
- proposer roles may not have implementation authority or require repository-write capability
- proposers emit bounded `TaskPlan` candidate artifacts and do not mutate the repository
- each provider artifact is tagged with `haive.inferenceInvocationId`, allowing downstream genealogy to name the exact producing invocation
- a deterministic `haive.genealogy-governance` system-executor node runs after all proposers and before aggregation
- the governance gate requires at least two candidate invocation records and blocks missing genealogy or circular derivation
- shared input/evidence is recorded as common ancestry; it makes candidate agreement ineligible to count as independent consensus but does not block synthesis of the candidates
- the original task ID becomes the aggregator task, preserving its durable workflow identity and original required outputs
- aggregation preserves meaningful disagreement and cannot treat candidate agreement as verification
- a separate verifier role runs after aggregation and produces a `Verification` artifact
- downstream tasks that depended on the original task are also rewired to wait for the injected verifier
- the genealogy-gate executor is registered centrally by `ApplicationRuntime.create()`, so Android, Desktop, JS, and Wasm share the same runtime behavior while retaining their platform-specific executor integrations

Centralized MoA can be selected explicitly or through `ResourceAware` authorization. Resource-aware selection never invents proposer roles, never broadens permissions, and falls back to `Single` when the configured resource constraints cannot be established from durable telemetry.

## Specialized local model and LoRA library

The Epoch-8 memory specialists are now generalized into a reusable, provider-neutral local model library rather than remaining a memory-only artifact catalog.

`LocalModelArtifactDescriptor` gives every published local asset a stable logical identity plus:

- foundation/base model identity
- release repository and tag
- asset filename
- lowercase SHA-256 release digest
- backend format
- precision
- artifact kind (`SharedBase`, `MergedModel`, `Adapter`, or `Standalone`)
- versioned adapter identity when applicable
- declared execution capabilities

`LocalModelSpecialistDescriptor` groups every valid execution form for one specialist. `LocalModelRuntimeCapabilities` describes what a concrete local runtime can actually load, and `LocalModelLibrary.plan(...)` selects a concrete `LocalModelLoadPlan`.

Adapter execution is deliberately opt-in. A runtime must explicitly advertise `supportsSharedBaseAdapters=true`, support the adapter/base formats and precisions, and have a compatible shared base. Only then may planning return `SharedBaseAdapter`. Otherwise the library selects a compatible cryptographically identified merged model, with standalone specialist fallback where appropriate.

The Epoch-8 implementation is `MemoryEpoch8LocalModelLibrary`. It catalogs:

- the published shared FP16 Qwen 2.5 0.5B base
- merged FP16 and INT8 artifacts for the generative memory specialists
- FP16 LoRA adapters for those specialists
- the standalone INT8 association embedding specialist

All release asset identities and SHA-256 digests are encoded in the reusable descriptors. `MemoryEpoch8ModelCatalog` remains a compatibility view over the generalized library so existing Android artifact IDs and install behavior remain stable. Its production artifact selection continues to prefer merged INT8 models.

`AgentProviderRegistry` installs the library into the durable inference fabric through `withLocalModelLibrary(...)`. Registration is lazy and mutex-guarded by `LocalModelBootstrapInferenceFabric`, so the first registry access or provider dispatch persists the catalog once per runtime and existing durable model identities remain stable across restarts.

The shared-base residency shape is therefore available to backends that can prove safe adapter switching:

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

The same library contract is intended to serve future orchestration utilities:

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

Runtime artifact requirements remain versioned identity, cryptographic verification, tokenizer/base compatibility, merged-model fallback when hot adapter switching is unavailable or unproven, hardware-aware placement, and measured execution reporting rather than assumed accelerator use. The generalized catalog and load planner now enforce the identity/fallback side of that contract; backend-specific download, verification, placement, and execution remain the responsibility of the concrete local runtime.

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
2. Durable Blueprint inference fabric — implemented for registries, typed streams, resource history, and provider-run bindings.
3. Durable runtime genealogy governance — implemented with Settings-backed structural ancestry persistence.
4. Centralized MoA proposer → genealogy gate → aggregator → verifier execution — implemented.
5. Resource-aware selection between implemented safe topologies — implemented with explicit authorization and conservative fallback.
6. Generalized local specialist/LoRA library registered into the model registry — implemented with capability-gated shared-base adapter planning and merged-model fallback.
7. DSPy optimization and release pipeline.
8. Remaining local orchestration utility family.
9. BitNet b1.58 experiments.
10. Skeleton-of-Thought experiments.

## Current implementation status

Implemented and called by production runtime:

- `CompoundInferenceStrategy`
- `InferenceGenealogy`
- `CompoundInferenceContext`
- workflow/project/task/role orchestration coordinates on provider dispatch
- automatic dependency artifact ancestry and inference-invocation ancestry
- durable `SettingsCompoundInferenceFabric`
- durable `InferenceModelRegistry`
- durable `InferenceAgentRegistry`
- durable `InferenceDataRegistry`
- durable `InferenceStreamFabric`
- durable `InferenceResourceTelemetry`
- `CompoundInferenceTaskPlanner`
- provider-session preparation through the fabric
- provider-run binding and retry-safe artifact/usage/terminal stream recording
- durable `SettingsInferenceGenealogyGraph`
- `InferenceGenealogyGovernanceRuntime`
- deterministic common-ancestry, cycle, evidence, independence, and consensus governance
- direct non-agent executor evidence indexing
- `CompoundInferencePolicy.CentralizedMixtureOfAgents`
- `CompoundInferencePolicy.ResourceAware`
- `ResourceAwareCompoundInferencePolicyResolver`
- `CentralizedMixtureOfAgentsExpander`
- `GenealogyGovernanceExecutorIntegration`
- centralized runtime registration of the genealogy gate
- explicit proposer → governance → aggregator → verifier DAG execution
- advisory false-consensus detection without blocking legitimate shared-input synthesis
- resource-aware selection using persisted cost/latency history with conservative `Single` fallback
- `LocalModelArtifactDescriptor`, `LocalModelSpecialistDescriptor`, and `LocalModelRuntimeCapabilities`
- `LocalModelLibrary` and capability-gated `LocalModelLoadPlan`
- `MemoryEpoch8LocalModelLibrary` with published FP16, INT8, LoRA, shared-base, and standalone embedding assets
- lazy durable model-registry bootstrap through `LocalModelBootstrapInferenceFabric`
- compatibility preservation through `MemoryEpoch8ModelCatalog`

Not yet implemented:

- DSPy/GEPA/MIPRO-style optimization and release pipeline
- remaining orchestration utility models
- BitNet runtime/model family
- Skeleton-of-Thought execution

Physical-device/real-provider end-to-end verification remains separate from CI and is not claimed by this document.
