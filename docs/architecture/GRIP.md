# GRIP memory recall

GRIP is Haive's memory-specific direct-recall operation.

**GRIP = Global Regular IMpression Print.**

This document supplements [`MEMORY_BANKING_AND_ATTENTION.md`](MEMORY_BANKING_AND_ATTENTION.md) and is normative for the memory-tool API.

## Public memory verbs

The memory-facing backend uses three verbs:

- `bank(...)` deposits experience into the memory layer.
- `grip(...)` recalls memory through semantic cues.
- `expand(...)` explicitly traverses from a returned memory node to another resolution when direct cueing is not enough.

Do not use `grep(...)` as the memory API verb. `grep` already describes a different general search operation. GRIP is deliberately memory-specific.

## Cue-first GRIP

Normal recall begins with the cheapest useful semantic cues:

- noun/entity tags
- verb/action tags
- category/subject tags

An ambient memory pass should normally request `MemoryResolution.Tag`. In the memory backend, `Tag` resolution intentionally includes all three cue families, including `Category` nodes used as broader subject tags. The result should therefore look like a small set of related cues rather than remembered prose.

Conceptually:

```text
current thought
    -> GRIP
related entity/action/category-subject cues
    -> recognition inside the active agent
    -> usually stop
```

Only when those cues are insufficient should the active agent request phrases, summaries, context, or source evidence.

## CoTR-addressed recall

An agent does not need to synthesize a prose memory query in order to dig into memory.

Semantic cues already present in the agent's CoTR are valid memory addresses. The agent may pass noun/entity tags, verb/action tags, category/subject tags, or any combination of them directly to the tag-addressed `grip(...)` overload and choose the resolution it needs.

Example:

```text
CoTR tags: [Web Wasm, test, build verification]

GRIP(tags, resolution = Tag)
    -> nearby memory cues

GRIP(tags, resolution = Context)
    -> relevant retained context when deeper recall is actually needed
```

The tag query begins from actual matching `NounTag`, `VerbTag`, and `Category` nodes and traverses the existing graph. It is not a second retrieval system and does not reduce category/subject cues to a prose search first.

## Weighted graph traversal

GRIP treats association weights as retrieval evidence. `MemoryEdge.weight` must not be discarded when the graph is projected from a cue to another memory resolution.

Path scoring combines cumulative edge strength with the existing hop-distance attenuation. A weak temporal association therefore remains weaker than an exact cue/provenance association at the same graph distance.

When several independent traversable edges connect the same pair of nodes, GRIP first accumulates their strength with the canonical complementary-exponential curve:

```text
combined = 1 - Π(1 - wi)
```

Then path traversal multiplies the accumulated pair strengths across hops and applies the hop-distance penalty. This means repeated evidence can make a relationship easier to recall, while weak or indirect paths naturally fade.

For example, two independent `0.50` associations between the same nodes produce effective pair strength `0.75`, not `1.0`. A single week-level `0.50` temporal edge remains materially weaker than a `1.0` exact-cue edge.

The full accumulation/condensation semantics are normative in [`TEMPORAL_MEMORY_AND_PROGRAMMATIC_ASSOCIATIONS.md`](TEMPORAL_MEMORY_AND_PROGRAMMATIC_ASSOCIATIONS.md).

## Deliberate banks and reminders

A note to self, small plan, checkpoint, unfinished follow-up, or similar reminder is an ordinary deliberate memory bank.

It receives one special ingestion behavior only:

> A deliberate bank jumps to **next in line** in the consolidation queue.

This makes the note available to association quickly instead of leaving it behind a long lifecycle-memory backlog.

Priority affects consolidation order only. Once the note has been processed by the Memory Clerks, it is ordinary memory. It has no reminder flag, no timer, no trigger rule, and no special retrieval presentation.

Multiple priority-next banks remain FIFO relative to one another.

## Reminder surfacing

A deliberately banked reminder resurfaces through the same associations as every other memory.

For example:

```text
bank("After fixing the router, return to the failing Web Wasm test.")

    -> priority-next consolidation
    -> sectioning/indexing/phrasing/association
    -> cues such as Web Wasm, test, router, build verification

later thought about a successful Wasm test

    -> related cues enter attention
    -> the agent often recollects the intended follow-up without reading the stored reminder
```

There is no programmatic reminder engine for this behavior.

## Attention

The Attention Deficit Dial controls how readily related semantic cues are allowed into active context. It does not alter memory, and it does not create a separate reminder channel.

Lowering the dial is temporary. Token use restores the effective setting toward the agent's baseline so a highly focused agent cannot permanently silence associative recall by forgetting to turn it back on.

The attention gate may deterministically vary:

- minimum association score
- minimum token interval between ambient cues
- number of cues surfaced at once

Even at high attention, semantic cues remain the default payload. Increased attention should increase the frequency or breadth of cues, not automatically dump full remembered passages.

## Durable rule

> **BANK deposits experience. GRIP cues recollection. Entity, action, and category/subject tags are normal memory addresses and normal first results. GRIP preserves associative weight, repeated evidence accumulates with diminishing returns, and deliberate banks jump next in line before becoming ordinary associative memory.**
