# The Haive documentation

These documents describe the current product on `main`. They are not a migration diary and they are not a record of earlier Geministrator-era experiments.

## Product model

The Haive is a governed workflow operating surface. A workflow is a dependency graph of work. A node is not inherently an AI agent: it can be staffed by a role, executed by a provider, run by automation, wait on a person, or represent another concrete unit of execution.

The live H2G2 mindmap is a projection of runtime truth. It is not a second state machine.

## Documents

### Agent working rules

The workflow engine prepends shared working rules to every role-agent task request,
including custom roles. These are provider instructions, not mechanical enforcement.
Code Reviewer and Antagonist retain separate instructions. The starter workflow still
uses Code Reviewer; Antagonist is available in the roster for explicit assignment.

### Architecture

[`architecture/ARCHITECTURE.md`](architecture/ARCHITECTURE.md) defines the product boundaries, runtime model, executor model, workflow UI, provider boundary, and delivery architecture.

### Memory banking and attention

[`architecture/MEMORY_BANKING_AND_ATTENTION.md`](architecture/MEMORY_BANKING_AND_ATTENTION.md) defines the canonical memory-banking, deliberate note-to-self deposit, tag-first associative surfacing, Attention Deficit Dial, token-driven attention recovery, non-destructive memory, and no-programmatic-reminder invariants. Future memory and orchestration work should treat this document as normative.

### Local memory inference

[`architecture/MEMORY_LOCAL_INFERENCE.md`](architecture/MEMORY_LOCAL_INFERENCE.md) defines the hardware-aware local inference boundary: separate generative and embedding workloads, accelerator discovery/selection, CPU fallback, cached ONNX Runtime sessions, and observed-execution reporting.

### Temporal memory and programmatic associations

[`architecture/TEMPORAL_MEMORY_AND_PROGRAMMATIC_ASSOCIATIONS.md`](architecture/TEMPORAL_MEMORY_AND_PROGRAMMATIC_ASSOCIATIONS.md) defines the deterministic temporal roll-up ladder (8×15m, 6×1h, 3×6h, 2×12h, 7×day, unlimited weeks), exact bookkeeping associations, weighted traversal, saturating associative reinforcement, and the rule that condensation loses specificity while shared associations strengthen.

### GRIP memory recall

[`architecture/GRIP.md`](architecture/GRIP.md) defines the memory-tool API contract: `bank(...)` for deposits, `grip(...)` for memory-specific direct recall, CoTR tags as direct memory addresses, priority-next consolidation for deliberate banks, tag-first results before deeper explicit recall, and weighted graph traversal.

### Persistence

[`architecture/PERSISTENCE.md`](architecture/PERSISTENCE.md) defines what survives restarts, where it is stored, what must never enter workflow persistence, and the resume invariants.

### Prompt caching

[`architecture/PROMPT_CACHING.md`](architecture/PROMPT_CACHING.md) defines provider-neutral prompt reuse. Caching is an optimization and never workflow semantics.

### Privacy

[`PRIVACY.md`](PRIVACY.md) describes what the current app stores, what may leave the device when a user invokes an external provider, how credentials are handled, and the current analytics/tracking policy.

## Naming

The product name is **The Haive**. Technical artifact names may use `haive`. The Android application ID is `com.hereliesaz.haive`.
