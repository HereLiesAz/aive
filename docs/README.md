# The Aive documentation

These documents describe the current product on `main`. They are not a migration diary and they are not a record of earlier Geministrator-era experiments.

## Product model

The Aive is a governed workflow operating surface. A workflow is a dependency graph of work. A node is not inherently an AI agent: it can be staffed by a role, executed by a provider, run by automation, wait on a person, or represent another concrete unit of execution.

The live H2G2 mindmap is a projection of runtime truth. It is not a second state machine.

## Documents

### Agent working rules

The workflow engine prepends shared working rules to every role-agent task request,
including custom roles. These are provider instructions, not mechanical enforcement.
Code Reviewer and Antagonist retain separate instructions. The starter workflow still
uses Code Reviewer; Antagonist is available in the roster for explicit assignment.

### Architecture

[`architecture/ARCHITECTURE.md`](architecture/ARCHITECTURE.md) defines the product boundaries, runtime model, executor model, workflow UI, provider boundary, and delivery architecture.

### Distributed compute

[`architecture/DISTRIBUTED_COMPUTE.md`](architecture/DISTRIBUTED_COMPUTE.md) defines `TaskExecutor.Distributed` placement, the `computeRelay/` WebSocket relay, relay tokens, delegation targets, lease claim/heartbeat/progress/requeue behavior, the executor kinds Android and Desktop nodes accept, and the `MeshCrypto` pairing/encryption primitives.

### Role execution sources

[`architecture/ROLE_EXECUTION_SOURCES.md`](architecture/ROLE_EXECUTION_SOURCES.md) defines how a role's execution source (agent/provider, GitHub Actions, JavaScript, Python) is kept separate from role identity, and the Aive task envelope supplied to system-backed roles.

### Workflow composition

[`architecture/WORKFLOW_COMPOSITION.md`](architecture/WORKFLOW_COMPOSITION.md) defines recursive composition: a role is the smallest workflow, and any role-task or workflow node may be replaced by another workflow graph, with shared workflow/role libraries.

### Compound inference

[`architecture/COMPOUND_INFERENCE.md`](architecture/COMPOUND_INFERENCE.md) defines the forward compound-inference architecture: centralized mixture-of-agents as governed DAG subgraphs, genealogy-based governance, Blueprint-style inference infrastructure, the local specialist/LoRA model library, DSPy optimization, governed Skeleton-of-Thought execution, and later ternary-model experiments. It preserves the existing human-like memory/reconsolidation authority boundary.

### Genealogy governance

[`architecture/GENEALOGY_GOVERNANCE.md`](architecture/GENEALOGY_GOVERNANCE.md) defines the structural trust layer for compound inference: information ancestry, independence, circular derivation, and missing provenance, without making epistemic judgments.

### BITCOS ternary runtime

[`architecture/BITCOS.md`](architecture/BITCOS.md) defines The Aive's experimental BITCOS weight encoding, HBCS container, converter, native Rust decoder, Android/Desktop bridge, capability gating, and the remaining fused-kernel/model-family work.

### BitNet b1.58 adoption benchmark

[`architecture/BITNET_B1_58_ADOPTION_BENCHMARK.md`](architecture/BITNET_B1_58_ADOPTION_BENCHMARK.md) records the gate-ordered pre-adoption review of native BitNet b1.58 specialists against the Epoch-8 Qwen/ONNX baseline (not adopted).

### Memory banking and attention

[`architecture/MEMORY_BANKING_AND_ATTENTION.md`](architecture/MEMORY_BANKING_AND_ATTENTION.md) defines the canonical memory-banking, deliberate note-to-self deposit, tag-first associative surfacing, Attention Deficit Dial, token-driven attention recovery, non-destructive memory, and no-programmatic-reminder invariants. Future memory and orchestration work should treat this document as normative.

### Local memory inference

[`architecture/MEMORY_LOCAL_INFERENCE.md`](architecture/MEMORY_LOCAL_INFERENCE.md) defines the hardware-aware local inference boundary: separate generative and embedding workloads, accelerator discovery/selection, CPU fallback, cached ONNX Runtime sessions, and observed-execution reporting.

### Deterministic-first memory semantics

[`architecture/MEMORY_DETERMINISTIC_SEMANTICS.md`](architecture/MEMORY_DETERMINISTIC_SEMANTICS.md) defines the deterministic-first semantic stack: strict bookkeeping facts, lexical/structural heuristic evidence, programmatic noun/verb fast paths with epoch-8 fallbacks, GRIP lexical normalization, and Specialist 08 restricted to semantic residue.

### Temporal memory and programmatic associations

[`architecture/TEMPORAL_MEMORY_AND_PROGRAMMATIC_ASSOCIATIONS.md`](architecture/TEMPORAL_MEMORY_AND_PROGRAMMATIC_ASSOCIATIONS.md) defines the deterministic temporal roll-up ladder (8×15m, 6×1h, 3×6h, 2×12h, 7×day, unlimited weeks), exact bookkeeping associations, weighted traversal, saturating associative reinforcement, and the rule that condensation loses specificity while shared associations strengthen.

### GRIP memory recall

[`architecture/GRIP.md`](architecture/GRIP.md) defines the memory-tool API contract: `bank(...)` for deposits, `grip(...)` for memory-specific direct recall, CoTR tags as direct memory addresses, priority-next consolidation for deliberate banks, tag-first results before deeper explicit recall, and weighted graph traversal.

### Persistence

[`architecture/PERSISTENCE.md`](architecture/PERSISTENCE.md) defines what survives restarts, where it is stored, what must never enter workflow persistence, the provider/executor resume invariants, and how interrupted Android model downloads retain resumable partial bytes outside workflow state.

### Storage scale

[`architecture/STORAGE_SCALE.md`](architecture/STORAGE_SCALE.md) records the current Settings-based workflow storage approach and the thresholds that would trigger migration to a structured backend.

### Versioning and releases

[`VERSIONING.md`](VERSIONING.md) defines the four-part build identity, immutable exact-build tags, patch-grouped GitHub Releases, centralized release/version policy, desktop package-version mapping, and Google Play versionCode rules.

### Workflow add-ons

[`architecture/ADDONS.md`](architecture/ADDONS.md) defines the mediated Azphalt workflow/role package boundary, Aive host compatibility rules, catalog discovery behavior, and the capabilities that add-ons can never access.

### Live runtime acceptance

[`architecture/LIVE_RUNTIME_ACCEPTANCE.md`](architecture/LIVE_RUNTIME_ACCEPTANCE.md) defines the provider-neutral end-to-end acceptance gate, restart/resume expectations, and failure/retry behavior.

### Prompt caching

[`architecture/PROMPT_CACHING.md`](architecture/PROMPT_CACHING.md) defines provider-neutral prompt reuse. Caching is an optimization and never workflow semantics.

### Repository services

[`Repository-Services.md`](Repository-Services.md) describes GitHub, GitLab, and Local Git repository sources, their repository-operation vocabularies, the GitLab workspace agent, orchestration artifacts, and failure behavior.

[`Local-Git-Repository-Operations.md`](Local-Git-Repository-Operations.md) defines the Desktop-only Local Git operation strings, their fixed process arguments, and the `CommandOutput` artifacts they emit.

[`REPOSITORY_AND_COMPANY_CUSTOMIZATION.md`](REPOSITORY_AND_COMPANY_CUSTOMIZATION.md) describes connected-repository search on the run setup screen and custom swarm roster management.

### Memory and orchestration model training

[`Memory-layer.md`](Memory-layer.md) is the training and deployment contract for the local memory-clerk model family.

[`Orchestration_layer.md`](Orchestration_layer.md) is the Kaggle training sequence for the orchestration model family.

### Research background

[`Agentic Memory System Research.md`](Agentic%20Memory%20System%20Research.md) and [`Composite Frontier Model Architectures.md`](Composite%20Frontier%20Model%20Architectures.md) are background research reports on hierarchical agent memory and compound AI systems. They are not product specifications.

### Swarm terrarium

[`swarm-terrarium/README.md`](swarm-terrarium/README.md) is the terrarium design brief for the 2D workflow-node mascots. [`swarm-terrarium/all_prompts.md`](swarm-terrarium/all_prompts.md) holds the role-specific rigging prompts, and [`swarm-terrarium/characters/001_orchestrator/rig/README.md`](swarm-terrarium/characters/001_orchestrator/rig/README.md) describes the first-pass Orchestrator puppet rig.

### Branding

[`BRANDING.md`](BRANDING.md) defines the canonical brand source assets in `branding/` and the icons and loader animations generated from them.

### Threat model

[`THREAT_MODEL.md`](THREAT_MODEL.md) identifies trust boundaries, threat actors, attack surfaces by integration, invariants, and open items.

### Privacy

[`PRIVACY.md`](PRIVACY.md) describes what the current app stores, what may leave the device when a user invokes an external provider, how credentials are handled, and the current analytics/tracking policy.

## Naming

The product name is **The Aive**. Technical artifact names may use `haive`. The Google Play Android application ID is `com.hereliesaz.aive`; GitHub-release Android builds keep `com.hereliesaz.haive` permanently so existing installs upgrade in place.
