# Genealogy-based inference governance

Genealogy governance is the structural trust layer for compound inference in The Haive.

It exists to answer questions such as:

- Did two candidate outputs actually come from independent information?
- Are several agreeing agents descendants of the same unsupported source?
- Does one candidate derive from another candidate that it is being compared against?
- Is there a circular derivation path?
- Is required provenance missing?

It does **not** answer whether a claim is true, false, correct, contradictory, preferable, or worthy of belief.

That boundary is intentional. Genealogy governance describes information ancestry. Epistemic judgment remains with the active reasoning/orchestration layer. Memory retains the existing human-like behavior described in `MEMORY_BANKING_AND_ATTENTION.md`: related traces remain available, associations surface them into active reasoning, and conscious reasoning handles disagreement before any resulting experience is banked again.

## Runtime model

Every concrete provider-backed inference invocation has an `InferenceGenealogy` record. In addition to task-run, artifact, memory, and tool-evidence ancestry, genealogy can contain direct upstream invocation IDs.

The runtime converts those records into `InferenceGenealogyNode`s in an `InferenceGenealogyGraph`.

`AgentProviderRegistry` owns a shared `InferenceGenealogyGovernanceRuntime`. Its configured compound-inference fabric is wrapped by `GovernedCompoundInferenceFabric`, so every concrete invocation prepared for provider execution is registered before that provider executes.

This wrapper is deliberate: replacing the underlying Blueprint fabric for testing or a future backend must not accidentally disable genealogy registration.

## Structural findings

The current deterministic evaluator can report:

- `MissingGenealogy` — an invocation requested for governance has no registered lineage node.
- `MissingEvidence` — a strict policy requires provenance, but the invocation has neither evidence references nor upstream invocation ancestry.
- `CommonAncestry` — two outputs share evidence, a transitive ancestor, or a direct derivation relationship.
- `CircularDerivation` — lineage contains a cycle.
- `InsufficientIndependence` — a candidate group does not contain enough pairwise-independent members for the requested threshold.
- `UnsupportedConsensus` — apparent agreement does not contain enough structurally independent contributors to count as independent consensus.

These findings are advisory structural facts. They are not truth labels.

## Evidence closure

Independence is evaluated over transitive evidence closure, not only direct references.

For example:

```text
source S
   |
 invocation R
   |        |
   v        v
candidate A candidate B
```

A and B are not treated as independent simply because their direct input lists differ. Both inherit ancestry from R and ultimately from S.

Likewise, if B directly consumes A, their agreement is not independent corroboration.

## Consensus governance

A `GenealogyConsensusGroup` identifies candidate invocation IDs that an aggregator or verifier wants to treat as agreeing contributors.

The evaluator calculates a maximum pairwise-independent subset and compares it to `minimumIndependentMembersForConsensus`.

This prevents a future centralized Mixture-of-Agents aggregator from mistaking repeated descendants of one claim for multiple independent votes.

Agreement still does not prove correctness. A structurally independent consensus merely means the contributors are sufficiently independent under the available genealogy. Verification must still use task acceptance criteria and evidence.

## Memory boundary

Genealogy governance must never:

- select a winning memory trace,
- emit `ConflictsWith` memory relationships,
- delete or supersede a memory because another memory disagrees,
- assign truth probabilities to remembered content,
- rewrite recalled content,
- convert structural independence into an epistemic verdict.

Conflicting memories continue to be handled by Haive's existing human-like architecture: preserve traces, associate them, surface them to conscious reasoning, reason about the discrepancy, and bank the resulting experience.

## Centralized MoA integration

Centralized MoA is the primary consumer of this governance layer.

The intended governed flow is:

```text
objective
   |
   +--> proposer A --+
   +--> proposer B --+--> genealogy check --> aggregator --> verifier
   +--> proposer C --+
```

Before the aggregator is allowed to treat candidate agreement as independent support, it can request a governance report over the proposer invocation IDs.

The report can expose common ancestry or insufficient independence. The aggregator may then request more independent evidence, escalate, or simply aggregate the candidates without presenting their agreement as independent corroboration.

The governance layer itself never chooses which candidate is correct.

## Persistence status

The current graph and latest governance reports are runtime-local.

Durable persistence and restoration remain required before genealogy can survive process restart and provide complete historical governance across resumed workflows. Persistence must preserve the same non-epistemic boundary; it stores derivation structure, not a truth database.

## Current implementation

Implemented:

- direct invocation-to-invocation ancestry in `InferenceGenealogy`
- concurrency-safe runtime genealogy graph
- automatic registration of every prepared provider-backed invocation
- transitive evidence closure
- common-ancestry and direct-dependency detection
- circular derivation detection
- configurable evidence requirements
- pairwise independence assessments
- independent-consensus threshold evaluation
- false-consensus detection
- adversarial tests covering shared evidence, transitive ancestry, cycles, strict evidence requirements, independent candidates, and provider-runtime registration

Not yet implemented:

- durable genealogy persistence/restoration
- direct governance gates in centralized MoA aggregation and verification
- automatic escalation or candidate replacement based on governance findings
- UI projection of genealogy reports
- physical-device/on-runtime end-to-end validation

None of those incomplete items should be represented as finished until their real runtime path and required verification exist.
