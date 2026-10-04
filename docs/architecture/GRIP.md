# GRIP memory recall

GRIP is The Aive's memory-specific direct-recall operation.

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

## Lexical seeding

Free-text GRIP seeds from BM25F over active nodes (k1 1.2). Fields: node text (weight 1, b 0.75), tag aliases (0.8, b 0.3) and other content metadata (0.3, b 0.5); ids, provenance and scores are not indexed. Rare terms outweigh common ones, query stopwords are dropped, and only nodes sharing a query term (or containing the whole query) are scored. Expansion terms from `LexicalMemoryTool` arrive as `MemoryQuery.expansionTerms` at weight 0.4, so they never dilute the caller's own words. The seed score is relevance alone: 0.8 × normalized BM25F + 0.2 for an exact phrase, so it stays in 0..1 without clamping.

## Ranking

Hits are ordered by weighted reciprocal rank fusion (k = 10) over relevance (weight 2), scope affinity (0.4, scoped queries only), salience (0.15), confidence (0.1) and recency (0.15): each signal ranks the candidates and contributes w / (k + rank). Nothing is added to a score and clamped; a memory's returned score is its relevance (lexical match carried along the graph), so the attention gate's threshold means how strongly it matched. Scope, salience and recency can reorder near-equal matches but cannot outrank a clearly stronger one, and cannot create a match.

## Weighted graph traversal

GRIP treats association weights as retrieval evidence. `MemoryEdge.weight` must not be discarded when the graph is projected from a cue to another memory resolution.

Path scoring combines cumulative edge strength with the existing hop-distance attenuation. A weak temporal association therefore remains weaker than an exact cue/provenance association at the same graph distance.

When several independent traversable edges connect the same pair of nodes, GRIP first accumulates their strength with the canonical complementary-exponential curve:

```text
combined = 1 - Π(1 - wi)
```

Then path traversal multiplies the accumulated pair strengths across hops and applies the hop-distance penalty. This means repeated evidence can make a relationship easier to recall, while weak or indirect paths naturally fade.

For example, two independent `0.50` associations between the same nodes produce effective pair strength `0.75`, not `1.0`. A single week-level `0.50` temporal edge remains materially weaker than a `1.0` exact-cue edge.

Correlated evidence counts once: lexical features of one noun, of one verb, or one structural signature are each a family, as are the nested orchestration scopes (session ⊂ task run ⊂ workflow run …). The strongest edge of a family is its contribution, so one verb read as lemma, class and sense is 0.92, not 0.993. Families and other edges then combine as above. Lexical edges are also discounted by how many memories share the feature, and a feature shared by more than max(50, 5% of memory) is skipped.

Spreading out of a node with more than eight links is damped by ln(e + 8) / ln(e + links) (ACT-R's fan effect, softened), so a hub linked to everything does not flood recall.

Edges that record a shared feature (`group` metadata: a session, run or task scope, a time bucket or event, an exact cue, identifier or source section) are read as membership of that feature, not as a chain: each member reaches the others through one hub in a single step (the nearest 32 on each side, in time order), damped by the hub's size, and the hub counts as one link for the walker's fan. Correlated hubs (nested scopes; time buckets and events) count once per pair, as their edges did. Older edges without `group` stay pairwise.

Memories that keep being delivered together get linked (Hebbian): every third time the same two memories surface together, in a prompt's recall or a recall the agent asked for, one `AssociatedWith` edge of weight 0.3 (`basis: co-recall`) is added between them, up to four per pair (together about 0.76). It only adds links; nothing stored is changed, and what the two memories say is never compared. Counts are kept in memory, so they restart with the app.

Sequence links between consecutive episodes fade with the time between them (halved every six hours, floor 0.25 of the base weight), and a run of episodes with no gap over thirty minutes is one event whose members link through it, however the run falls across clock buckets.

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
- novelty: a memory surfaced within `noveltyCueIntervals` cue intervals (2 by default, scaled by the dial like the interval itself) is not surfaced again
- distinctness: the gate reads the strongest hit plus half its lead over the mean of the rest (`distinctnessWeight`), so one memory that stands out opens it more readily than many equally close ones
- hysteresis: once open, the gate stays open down to the threshold less `hysteresis` (0.05) and closes only below it, so recall does not flicker around the threshold
- bursts: the interval is a token bucket; up to `cueBurst` (2) cues may surface back to back, then one more per interval of tokens

Even at high attention, semantic cues remain the default payload. Increased attention should increase the frequency or breadth of cues, not automatically dump full remembered passages.

## Recall triggers in thought

Memory watches an agent's own output and plans (and its reasoning, from providers that emit `AgentEvent.Thinking`) and answers inside the session. Every message memory sends starts with `⟦memory⟧`; those are never banked or re-read as the agent's own. Nothing here needs to be taught: cue clouds, echoes and doubled words fire on what an agent thinks and writes anyway, and how it works is easy to discover from the marked lines. An agent's first prompt carries one line saying what those lines are; cue clouds and recalls after that are bare (`⟦memory⟧ #a #b`).

- **Cue clouds.** Each uncommon word of a thought seeds its own Tag-resolution GRIP; cues that pass the dial (threshold, interval, novelty) are sent as a line of `#tags`. User prompts draw clouds too: the task prompt's cloud joins its starting context (sharing that moment's interval with the prompt's recall), and a message typed into a running session carries its cloud appended.
- **Following a cue.** Summary-resolution recall, scoped to the session, fires on:
  - an explicit `#tag` (`#gradle-cache`),
  - "Let me see what I remember about …" (for agents whose thinking is not visible),
  - an echo: a just-offered cue's word used within `echoWindowTokens` (40),
  - doubling: a word used twice within `doublingWindowTokens` (60).
  Explicit tags, the phrase and echoes are deliberate and bypass the dial; doubling is gated by it.
- **Frequency filter.** A word found in more than `commonWordShare` (5%) of memories, and more than `commonWordMinimumMemories` (20) of them, cannot fire doubling or an echo, nor seed a cue cloud ("build", "test" in a codebase that says them everywhere). Explicit tags and the phrase are never filtered.
- **Delivery.** Through the session gateway's message channel, mid-thought where the provider accepts messages; otherwise the recall waits and arrives with the agent's next turn as "Recalled on request".

### Which providers think out loud

The hosted text providers (OpenAI, xAI, Claude, Gemini, OpenAI-compatible services) and the GitLab and desktop local workspace providers stream their generations and emit the model's visible reasoning as `AgentEvent.Thinking`, in readable pieces (a paragraph or sentence end, or about 400 characters):

- **Reasoning is requested** everywhere it can be: Claude adaptive thinking with summarized display (a fixed 8,000-token budget for models that predate adaptive thinking), an OpenAI or xAI reasoning summary, Gemini `includeThoughts`. A model or account that refuses the option (an older model, an OpenAI organization that is not verified) is retried once without it, and not asked again. OpenAI-compatible services have no common switch, so their reasoning is read when it arrives (`reasoning_content`, `reasoning`, or inline `<think>` spans). Requested reasoning is billed as output tokens.
- **Memory interrupts.** A `⟦memory⟧` reply sent while a generation streams is taken by the provider (any other message is still declined). At the next streamed piece the generation stops and starts again with the original prompt, the reasoning and answer so far, and what memory brought back, and the model continues with the memory in view. At most three times per run; only the last attempt's answer is kept, and usage covers that attempt only.
- **Not streamed:** plan drafts, on-device models (their reasoning, if any, arrives with the answer), and OpenCode on GitHub Actions, which reports only its run summary.

## Durable rule

> **BANK deposits experience. GRIP cues recollection. Entity, action, and category/subject tags are normal memory addresses and normal first results. GRIP preserves associative weight, repeated evidence accumulates with diminishing returns, and deliberate banks jump next in line before becoming ordinary associative memory.**
