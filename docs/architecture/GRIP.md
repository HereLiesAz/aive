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

## Tag-first GRIP

Normal recall begins with semantic noun/entity and verb/action tags.

An ambient memory pass should normally request `MemoryResolution.Tag`. The result should therefore look like a small set of related cues rather than remembered prose.

Conceptually:

```text
current thought
    -> GRIP
related tags
    -> recognition inside the active agent
    -> usually stop
```

Only when those cues are insufficient should the active agent request phrases, summaries, context, or source evidence.

## CoTR-addressed recall

An agent does not need to synthesize a prose memory query in order to dig into memory.

Semantic tags already present in the agent's CoTR are valid memory addresses. The agent may pass those tags directly to the tag-addressed `grip(...)` overload and choose the resolution it needs.

Example:

```text
CoTR tags: [Web Wasm, test, router]

GRIP(tags, resolution = Tag)
    -> nearby memory tags

GRIP(tags, resolution = Context)
    -> relevant retained context when deeper recall is actually needed
```

The tag query begins from memory tags and traverses the existing graph. It is not a second retrieval system.

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
    -> tags such as Web Wasm, test, router, return

later thought about a successful Wasm test

    -> related tags enter attention
    -> the agent often recollects the intended follow-up without reading the stored reminder
```

There is no programmatic reminder engine for this behavior.

## Attention

The Attention Deficit Dial controls how readily related tags are allowed into active context. It does not alter memory, and it does not create a separate reminder channel.

Lowering the dial is temporary. Token use restores the effective setting toward the agent's baseline so a highly focused agent cannot permanently silence associative recall by forgetting to turn it back on.

The attention gate may deterministically vary:

- minimum association score
- minimum token interval between ambient cues
- number of tags surfaced at once

Even at high attention, tags remain the default payload. Increased attention should increase the frequency or breadth of cues, not automatically dump full remembered passages.

## Durable rule

> **BANK deposits experience. GRIP cues recollection. Tags are the normal address and the normal first result. Deliberate banks jump next in line, then become ordinary associative memory.**
