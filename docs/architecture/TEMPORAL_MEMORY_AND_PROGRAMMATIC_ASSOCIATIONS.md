# Temporal memory and programmatic associations

This document defines The Aive's deterministic time-bucketing and non-model association rules. It is normative for memory-backend work.

## Principle

Do not train a model to perform bookkeeping that deterministic code can perform exactly.

The semantic Memory Clerks remain responsible for semantic decomposition, summarization, categorization, and genuinely fuzzy semantic proximity. Time organization, exact identity, provenance, orchestration scope, and other mechanically knowable relationships belong in deterministic code.

These deterministic mechanisms do not decide what a memory means, whether it is true, whether two memories conflict, or which memory should win.

## Temporal retention ladder

Recent memory remains temporally granular. Older time windows roll upward into coarser derived buckets.

The active temporal index permits:

| Level | Maximum active buckets | Rolls upward to |
| --- | ---: | --- |
| 15 minutes | 8 | 1 hour |
| 1 hour | 6 | 6 hours |
| 6 hours | 3 | 12 hours |
| 12 hours | 2 | 1 day |
| 1 day | 7 | 1 week |
| 1 week | unlimited | — |

When a level exceeds its cap, the oldest active time window is folded into its parent time window. The newest memories therefore retain the finest temporal resolution while progressively older memories become coarser.

Examples:

- the first eight active quarter-hour windows remain quarter-hour windows;
- when a ninth quarter-hour window appears, the oldest quarter-hour window(s) belonging to the oldest one-hour period roll into that one-hour bucket;
- when more than six one-hour buckets remain active, the oldest one-hour period(s) roll into a six-hour bucket;
- the same process continues through 6-hour, 12-hour, day, and week levels;
- week buckets are never rolled up further.

The temporal index is **derived from immutable episode timestamps**. It does not require model inference or destructive persistence mutation.

A coarse bucket preserves the episode IDs represented by its finer source buckets. The underlying episodes, semantic nodes, tags, phrases, summaries, categories, and provenance remain untouched.

Temporal compaction is therefore retrieval/index compaction, not memory deletion.

## The MemoryStore is the association boundary

Project IDs are memory metadata, not memory-isolation boundaries.

Different saved/cloned projects use different memory-store locations. If two project IDs occur inside the same `MemoryStore`, The Aive should assume that they are intentionally part of the same remembered working universe and permit associations between them.

This matters when one orchestration works on two projects at once. The context switch itself, shared time window, shared task/workflow run, shared identifier, or shared semantic cue may be exactly what lets a later agent remember that the two pieces of work were connected.

Therefore deterministic association must **not** reject a relationship merely because the two source episodes have different `projectId` values.

`projectId` can still be used as a useful positive association signal, but never as an association firewall inside one store.

## Programmatic associations

The backend should create neutral `AssociatedWith` graph edges whenever a relationship can be established mechanically.

Each deterministic edge records:

- `deterministic = true`
- a `basis` describing why the link exists
- optional bounded `detail`

Current deterministic association bases include:

### Exact semantic cue identity

Two noun/entity tags, verb/action tags, or category/subject tags with the same normalized text can be associated without inference.

Examples:

- `Web Wasm` ↔ `Web Wasm`
- `test` ↔ `test`
- `build verification` ↔ `build verification`

This identity matching applies across project IDs when those memories live in the same store.

### Exact identifiers and artifacts

Exact machine-readable identifiers can be associated directly, including:

- URLs
- repository/file paths
- commit hashes
- PR/issue references such as `#234`
- exact backticked code spans/symbols
- exception/error type names
- Gradle/task-style paths such as `:shared:desktopTest`

This is identity matching, not semantic inference. Exactness must remain genuinely exact: case-sensitive identifiers such as repository paths and code symbols retain case. Project-relative identifiers such as repository paths, PR/issue numbers, task paths, and code symbols are grouped inside their source-project namespace rather than treated as globally unique. Global identifiers such as a full URL or commit hash may associate across project IDs inside the same store.

### Shared provenance

Memory nodes that point to the same evidence section can be associated programmatically because their shared source is explicit.

### Orchestration scope

Episode representatives can be associated from exact shared orchestration coordinates:

- source session
- task run
- workflow run
- task definition
- workflow definition
- role/project combination

Source session, task-run, and workflow-run identity intentionally remain capable of spanning project IDs. One orchestration may legitimately be touching several projects at once.

Task-definition identity is qualified by both project and workflow definition because a task name may be reused by multiple workflows.

### Sequential work history

Chronologically adjacent episodes in the same memory store can be linked as neighboring work-history events. This preserves project-to-project context switches as real remembered sequence.

Same-project adjacency can also be recorded as a slightly stronger bookkeeping signal.

Sequence expresses temporal ordering only; it does not imply causation.

### Temporal co-bucketing

Episode representatives that occupy the same active temporal bucket can be associated programmatically even when they have different project IDs. The edge basis records the bucket level, such as:

- `temporal:FifteenMinutes`
- `temporal:OneHour`
- `temporal:SixHours`
- `temporal:TwelveHours`
- `temporal:Day`
- `temporal:Week`

Temporal proximity does not imply semantic similarity or causal relationship. It only records that the memories occurred within the same active time window in the same memory store.

Coarser temporal buckets therefore carry weaker edge weights than precise bookkeeping relationships. A week-only association must not behave like an exact cue or shared-provenance edge during recall.

## Association weights are retrieval evidence

`MemoryEdge.weight` is semantically meaningful and must survive into traversal. GRIP and explicit expansion must propagate edge strength instead of scoring graph paths solely by hop count.

A traversal path combines two independent attenuations:

1. **association strength** — the accumulated weight of the edges actually traversed;
2. **graph distance** — the ordinary depth penalty for increasingly indirect recall.

A weak one-hop temporal edge must therefore score below a strong one-hop exact edge, and a chain of weak edges must attenuate further rather than becoming equivalent merely because the hop count matches.

When multiple independent association edges connect the same pair of nodes, their evidence is accumulated before traversal rather than taking only the strongest edge or adding weights linearly.

## Associative strength accumulates on a saturating exponential curve

Repeated supporting associations should strengthen memory indefinitely in the sense that every additional independent piece of evidence can increase associative strength, while the increase has diminishing returns and approaches a ceiling asymptotically.

For independent association weights `w1 ... wn`, The Aive uses complementary exponential accumulation:

```text
combined = 1 - Π(1 - wi)
```

For repeated equal evidence `w`, this becomes:

```text
combined(n) = 1 - (1 - w)^n
```

For example, repeated `0.50` evidence produces:

```text
1 association  -> 0.500
2 associations -> 0.750
3 associations -> 0.875
4 associations -> 0.9375
```

The first reinforcement is large and later reinforcements become progressively smaller. Mathematically the ideal curve approaches `1.0` without linear runaway or reaching it at any finite number of sub-unit inputs; production floating-point arithmetic may round sufficiently close values to `1.0`, so `1.0` is treated as saturation rather than as a claim of certainty.

This is deliberately **not** `sum(weights)` and not simple doubling with a hard clamp.

This rule applies wherever multiple independent graph associations support the same relationship, including parallel deterministic/semantic evidence and inherited overlap during condensation.

## Independence is required for reinforcement

The accumulation curve applies to **independent support**, not merely to every edge that happens to connect the same pair of nodes.

Some append-only edges are later representations of the same underlying evidence. Those representations must not reinforce one another. Temporal roll-up is the canonical example: a pair of memories may first be represented by a `temporal:FifteenMinutes` edge and later by a `temporal:Week` edge after the temporal index compacts. The week edge is a coarser replacement representation of the same temporal co-presence, not a second observation that the two memories are related.

Therefore temporal co-bucket edges belong to one evidence family with a **latest-representation** policy:

```text
old temporal:FifteenMinutes 0.88
new temporal:Week           0.50

current temporal evidence  = 0.50
not                        = 1 - (1 - 0.88)(1 - 0.50) = 0.94
```

This preserves the intended loss of temporal specificity as memories age. Historical edges remain stored because the memory layer is non-destructive; retrieval simply recognizes that correlated historical representations are one evidence family and uses the current representation once.

Independent evidence still reinforces normally. If the current week-level temporal evidence is `0.50` and an unrelated exact/provenance/semantic association independently contributes `0.50`, their effective relationship becomes `0.75` through the normal saturating curve.

The same evidence-family rule applies before condensation inherits a source memory's associative strength. A generalized memory must inherit the source's **current effective support**, not accidentally treat old and new representations of one fact as multiple independent reasons.

New replaceable evidence families should explicitly store `evidenceFamily` and `evidencePolicy=latest`. The backend also recognizes legacy `temporal:*` bases as the temporal family so already-persisted memories remain correct after upgrades.

## Condensation trades specificity for associative strength

When several highly similar memories are mechanically condensed into a more general representation, their shared associations become more important, not less.

The generalized memory therefore receives direct `AssociatedWith` support for external targets that were associated with **two or more** of the condensed source memories. Source-specific parallel evidence is first accumulated per source, then independent source contributions are accumulated again with the same saturating exponential rule.

Conceptually:

```text
memory A --0.50--> target X
memory B --0.50--> target X

A + B -> generalized memory G

G --effective 0.75--> target X
```

If a third independently condensed source also carries `0.50` support for `X`, the generalized effective association becomes `0.875`, not `1.25` and not a simple clamped `1.0`.

The implementation keeps each source's inherited support as its own append-only edge and lets the normal parallel-edge accumulation calculate the effective strength. This preserves provenance, avoids mutating old graph facts, and allows newly discovered independent support to strengthen the generalized association later.

This creates the intended tradeoff:

- **specificity decreases** as multiple detailed memories become one broader representation;
- **shared associative strength increases** because repeated evidence says the broader representation is reliably connected to that neighboring concept.

Unique associations belonging to only one condensed source are not artificially reinforced. They remain recoverable through preserved provenance and `CondensedFrom` traversal. Condensation therefore strengthens overlap without pretending that every source-specific relationship became common.

Further rounds of condensation apply the same curve again. Associative strength can continue increasing with additional independent support while each increment gets smaller.

## Graph-density rule

Programmatic associations must remain bounded.

For large groups, deterministic code should connect memories in stable chains or bounded neighborhoods rather than create an all-to-all clique. This preserves reachability without quadratic graph growth.

Programmatic association refresh is also mutation-bounded per pass. Additional deterministic links can be filled in on later consolidation passes.

Condensation-overlap propagation follows the same bounded-maintenance rule: strongest shared overlaps are emitted first when a refresh limit is reached, while source provenance remains available for deeper traversal.

## Separation from learned association

Deterministic association should run automatically around memory consolidation.

The learned/embedding association layer is reserved for relationships that cannot be known from exact bookkeeping data, especially fuzzy semantic proximity.

The ordering principle is:

```text
exact bookkeeping relationship?
    yes -> programmatic AssociatedWith edge
    no  -> semantic association may evaluate proximity
```

Programmatic association must never emit epistemic relations such as contradiction, truth, correction, preference, or reconciliation.

## Interaction with GRIP and attention

Temporal buckets and deterministic associations enrich the graph behind GRIP; they do not change cue-first recall semantics.

The normal surfacing path remains:

```text
current thought
    -> related entity/action/category cues
    -> recognition
    -> GRIP drill-down only if needed
```

Edge weights determine how strongly a graph neighborhood participates in that surfacing. The Attention Deficit Dial controls how readily sufficiently related cues enter active context; it does not erase the distinction between strong and weak associations.

## Durable rule

> **If a relationship can be established exactly from time, provenance, orchestration metadata, sequence, or identifier identity, derive it in code. Preserve edge strength during recall. Accumulate only independent associative evidence with diminishing returns; correlated re-representations such as temporal rebucketing contribute once through their current representation. Condensation loses specificity while genuinely shared associations become stronger. Save model inference for relationships that actually require semantics.**
