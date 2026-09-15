# Compound inference architecture

This document defines The Haive's planned compound-inference architecture derived from the ideas evaluated in `../Composite Frontier Model Architectures.md` and the current runtime contracts on `main`.

The goal is not to replace the workflow engine with a second orchestration system. The workflow DAG remains runtime truth. Compound inference is the governed execution fabric used by model-backed work inside that DAG.

## Architectural invariant: memory remains human-like and non-epistemic

The existing memory architecture is intentionally preserved.

Haive manages conflicting memories by keeping related traces available, surfacing them associatively into active reasoning, allowing the conscious/orchestration layer to notice and reason about the disagreement, and banking the resulting experience back into memory for later consolidation.

Memory clerks therefore continue to organize, associate, condense, and retrieve what was thought, said, observed, requested, done, or produced. They do not decide which conflicting trace is true. Genealogy and compound inference must strengthen provenance and reasoning without moving epistemic authority into the memory layer.

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

System executors remain executor-neutral workflow nodes. They participate in provenance, streams, evidence, and governance, but they do not masquerade as model agents.

## Compound inference fabric

Every provider-backed task request carries compound-inference metadata. The fabric is the single seam through which future model collaboration strategies, model libraries, adapter selection, and governance are introduced.

Initial execution strategy:

- `Single`

Planned strategies:

- `CentralizedMixtureOfAgents`
- `ParallelIndependent`
- `SequentialPipeline`
- `Escalate`

The planner must select a strategy based on task structure rather than assuming that more agents are always better. Centralized multi-agent collaboration is appropriate when work can be decomposed into genuinely independent or complementary candidate contributions. Strongly sequential work should normally remain single-agent or sequentially pipelined because additional independent agents can amplify rather than reduce error.

## Centralized mixture of agents

Centralized MoA is a governed subgraph, not an unstructured swarm.

A typical MoA execution is:

```text
input
  |
  +--> proposer A --+
  +--> proposer B --+--> aggregator --> verifier --> output
  +--> proposer C --+
```

Required properties:

- proposer contexts are isolated from one another unless the topology explicitly permits sharing
- proposer outputs are typed artifacts rather than implicit transcript inheritance
- the aggregator receives bounded, validated candidate outputs
- genealogy preserves each candidate's ancestry
- the aggregator's result is not considered verified merely because several candidates agree
- verification uses evidence and acceptance criteria
- collaboration depth and candidate count are bounded by policy
- sequential tasks are not automatically converted into MoA

The existing workflow DAG, concurrency limits, artifact model, and independent verification machinery are the foundation for this strategy.

## Genealogy-based governance

Genealogy records information ancestry. It does not decide truth.

Each model invocation has a stable invocation identity plus direct ancestry such as:

- producing task runs
- upstream artifacts
- recalled memory addresses
- tool-evidence identifiers
- prompt fingerprint
- configuration fingerprint
- later: model/base/adapter identity and provider execution identity

This allows the runtime to distinguish independent evidence from repeated descendants of the same unsupported claim.

The initial code contract is `InferenceGenealogy` carried inside `CompoundInferenceContext` on every `AgentTaskRequest`. Dependency artifacts automatically contribute the producing task-run IDs and artifact IDs to this baseline genealogy.

Future genealogy governance will persist these relationships as a graph and make them available to verification and aggregation. Governance may flag common ancestry, unsupported consensus, circular derivation, missing evidence, or insufficient independence. It must not silently rewrite a worker's reasoning or convert memory into a truth database.

## Blueprint enterprise inference infrastructure

The Composite Frontier research recommends treating inference as infrastructure rather than a set of unrelated model calls. Haive will develop this as a first-class part of every workflow.

The target infrastructure has four major registries/fabrics:

### Model and agent registry

Tracks execution-capable model assets and provider agents, including:

- logical model ID
- base model ID
- adapter ID
- precision / quantization
- supported task capabilities
- tokenizer compatibility
- runtime backend
- hardware requirements
- residency state
- measured latency / memory / energy where available
- release digest and provenance

### Data registry

Provides stable symbolic references to governed data sources without flattening them into prompts prematurely:

- workflow artifacts
- memory addresses
- repositories and files
- tool outputs
- verification evidence
- imported references

### Stream fabric

Carries typed intermediate outputs between inference stages. Streams preserve bounded schemas, genealogy, execution identity, and isolation policy instead of relying on shared hidden transcript state.

### Task and data planner

Selects execution topology, models/adapters, relevant data, and resource allocation while respecting workflow authority, cost, latency, hardware, privacy, and verification policy.

The workflow DAG remains authoritative. The task/data planner may materialize governed inference subgraphs inside an authorized workflow task; it does not invent unrelated work outside the workflow contract.

## Specialized local model and LoRA library

Haive already has the seed of this architecture in the Epoch-8 memory layer.

The Memory Epoch-8 release contains:

- FP16 specialist model artifacts
- INT8 specialist model artifacts
- LoRA specialist artifacts
- a separate embedding specialist for association

The current Android production path primarily binds the role-specific INT8 ONNX artifacts. The next model-library step is to generalize these assets into a reusable local specialist library rather than treating memory models as a one-off installation path.

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

The same library pattern will later serve orchestration utilities such as:

- Memory Query Composer
- Context Packer
- Agent Router
- Tool Router
- Handoff Composer
- Escalation Gate
- Completion Gate
- Execution State Summarizer

Planner and Plan Repair remain a larger reasoning tier when necessary.

Runtime requirements:

- shared-base residency where the backend supports safe adapter switching
- versioned adapter identity
- cryptographic artifact verification
- tokenizer/base compatibility checks
- merged-model fallback when hot adapter switching is unavailable or unproven
- hardware-aware placement
- measured execution reporting rather than assumed accelerator use

## DSPy-optimized small models

DSPy belongs in the model-development pipeline, not in workflow correctness.

The intended pipeline is:

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

Small models should be optimized against the exact structured function they perform. Optimization must preserve the authority boundary of the role. For example, a Completion Gate may be optimized to demand explicit evidence, but a memory clerk may not be optimized into deciding truth or contradiction because that would violate the memory contract.

## Orchestration utility family

The orchestration design currently describes more local utility roles than the deployed Epoch-8 orchestration runtime exposes. The release-backed runtime presently has Planner and Plan Repair.

The planned specialist family includes:

- Memory Query Composer
- Context Packer
- Agent Router
- Tool Router
- Handoff Composer
- Escalation Gate
- Completion Gate
- Execution State Summarizer
- Verification Planner

These utilities should be cheap, structured, and trained to escalate when uncertain. Their objective is not maximum local handling rate; it is correct cheap handling plus correct escalation.

## Experimental phase: BitNet b1.58

BitNet b1.58 is an experimental backend/model-family direction, not a drop-in post-training quantization flag for existing Qwen artifacts.

Adoption requires BitNet-native model artifacts and backend benchmarking against the existing specialist family for:

- task accuracy
- latency
- RAM
- energy / thermals
- startup time
- supported hardware
- runtime portability

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

It reuses the same workflow concurrency, genealogy, streams, and verification contracts as centralized MoA. A skeleton branch is parallelized only when its dependencies permit it.

## Implementation sequence

1. **Compound inference + genealogy foundation**
   - attach `CompoundInferenceContext` to every model-backed task request
   - establish stable invocation ancestry
   - preserve existing workflow and memory semantics
2. **Blueprint inference infrastructure**
   - model/agent registry
   - data registry
   - typed stream fabric
   - task/data planning boundary
   - resource and execution telemetry
3. **Centralized MoA**
   - isolated proposer subgraphs
   - bounded aggregation
   - genealogy-aware independence checks
   - explicit verification
4. **Generalized local specialist/LoRA library**
   - shared bases and adapters
   - compatibility manifests
   - residency/cache policy
   - merged fallbacks
5. **DSPy optimization pipeline**
   - typed signatures
   - evaluation metrics
   - optimization and release pipeline
6. **Finish the local orchestration utility family**
7. **BitNet b1.58 experiments**
8. **Skeleton-of-Thought experiments**

## Current implementation status

Implemented foundation:

- `CompoundInferenceStrategy`
- `InferenceGenealogy`
- `CompoundInferenceContext`
- default single-model compound-inference metadata on every `AgentTaskRequest`
- automatic direct ancestry from dependency artifacts

Not yet implemented:

- persisted genealogy graph
- genealogy-aware verification policy
- centralized MoA execution
- model/agent registry
- data registry
- typed stream fabric
- shared-base runtime LoRA switching
- DSPy optimization pipeline
- remaining orchestration utility models
- BitNet runtime/model family
- Skeleton-of-Thought execution

Those items must not be represented as complete until they have real callers, tests, and runtime verification appropriate to the target platform.
