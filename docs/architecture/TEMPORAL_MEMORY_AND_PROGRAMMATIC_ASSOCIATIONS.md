# Temporal memory and programmatic associations

This document defines Haive's deterministic time-bucketing and non-model association rules. It is normative for memory-backend work.

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

Different saved/cloned projects use different memory-store locations. If two project IDs occur inside the same `MemoryStore`, Haive should assume that they are intentionally part of the same remembered working universe and permit associations between them.

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

This is identity matching, not semantic inference.

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

## Graph-density rule

Programmatic associations must remain bounded.

For large groups, deterministic code should connect memories in stable chains or bounded neighborhoods rather than create an all-to-all clique. This preserves reachability without quadratic graph growth.

Programmatic association refresh is also mutation-bounded per pass. Additional deterministic links can be filled in on later consolidation passes.

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

The Attention Deficit Dial controls how readily those related cues enter active context. It does not alter temporal roll-up rules or deterministic graph facts.

## Durable rule

> **If a relationship can be established exactly from time, provenance, orchestration metadata, sequence, or identifier identity, derive it in code. The memory store is the association universe; project IDs are context, not walls. Save model inference for relationships that actually require semantics.**
