# Memory banking and associative attention

This document defines the canonical Aive memory-deposit and memory-surfacing semantics.

These rules are architectural invariants. Implementations, training prompts, orchestration code, and future sessions should preserve them unless the architecture is deliberately revised.

## Core principle

The Aive's memory is associative before it is explicit.

The normal path is:

```text
current thought
    ↓
related semantic cues become available
    ↓
recognition / recollection inside the active agent
    ↓
only if necessary: explicit memory drill-down
```

The normal cues are semantic noun/entity tags, verb/action tags, and category/subject tags. The memory system should **not** begin by injecting full remembered passages into an agent's context. In many cases, a few related cues are enough to make the active model remember what it was doing, what else it intended to do, or which resources it has already used.

This is a deliberate design choice. The cheapest useful memory cue should surface first.

---

## Memory banking

A **memory bank** operation commits experience into the memory layer.

There are two important ways banking happens, but they use the same memory system afterward.

### 1. Automatic lifecycle banking

At the appropriate agent lifecycle boundary, The Aive banks the agent's full available context into the memory layer.

This is the durable record of the agent's lived session: what it was asked, what it saw, what it tried, what it produced, what failed, what changed, what it reasoned about, and what remained unresolved.

The context may be processed in bounded packets for inference and storage, but the architectural intent is to bank the complete available episode rather than selecting only a hand-written summary.

### 2. Deliberate in-session banking

An active agent may intentionally bank a smaller piece of its current context before the full session is over.

Typical examples are:

- a note to self
- a small plan
- an unfinished follow-up
- a useful procedure
- a resource it expects to need again
- a checkpoint
- a constraint it does not want buried by later context

Example:

> Note to self: after fixing the router, return to the failing Web Wasm test.

This is not a separate reminder subsystem. It is an early, deliberate memory deposit.

The agent may choose the scope of that deposit, for example current agent, lineage, task/workflow, project, or broader scope. Scope controls where the memory is eligible to participate; it does not create separate reminder semantics.

Deliberately banked notes jump to **next in line** in the consolidation queue. This priority exists only so the note becomes usable memory promptly. Once consolidated, it is ordinary associative memory and is not retrieved or presented specially.

After banking, the deliberately deposited note is processed and retrieved like other memory.

---

## There is no programmatic reminder engine in the memory architecture

The Aive should not need a separate programmatic reminder mechanism such as:

```text
reminder.fireWhen(...)
reminder.triggerAt(...)
reminder.markDone(...)
```

for ordinary agent notes to self, minor plans, or follow-ups.

If the Memory Clerks are doing their jobs, the deliberately banked note will be sectioned, indexed, summarized, categorized, and associated with the same topics as later thoughts.

For example:

```text
"After fixing the router, return to the failing Web Wasm test."

        ↓ memory clerks

router
fix
Web Wasm
test
failing
return / follow-up
build verification

        ↓ later

"The Wasm test is succeeding now."

        ↓ associative overlap

related semantic cues surface
```

The system does **not** need to present a special notification saying that a reminder fired.

The relationship itself is the reminder mechanism.

---

## Deliberately banked notes are not presented specially

A note to self is not retrieved as a special UI or prompt object merely because the originating agent intended it as a reminder.

It should surface the same way as other related memory: **semantic cues first**.

For the Wasm example, the active agent may receive something as small as:

```text
Related memory cues: Web Wasm, test, build verification
```

Those cues may be noun/entity tags, verb/action tags, or broader category/subject tags. A category such as `build verification` is itself a valid cue and can be enough to reawaken the relevant memory neighborhood.

That may be enough for the model to recollect:

> I was supposed to return to the Wasm test after the router work.

If that recollection happens, no explicit memory read is necessary.

Only when the cues are insufficient should the agent descend into more detailed memory representations.

---

## Cue-first retrieval hierarchy

Memory retrieval should normally begin with the smallest useful abstraction.

Conceptually:

```text
related noun/entity, verb/action, and category/subject tags
    ↓ only if needed
short phrase
    ↓ only if needed
summary
    ↓ only if needed
granular remembered context
    ↓ only if needed
original episode / source
```

This hierarchy serves two purposes:

1. It minimizes context consumption.
2. It gives the active agent the opportunity to recollect from a cue rather than being told the remembered content verbatim.

Tags and categories are therefore more than search indexes. They are **minimal associative memory cues**.

An active agent is free to request deeper memory at any point, but deeper retrieval should not be automatic merely because related memory exists.

---

## GRIP and CoTR-directed recall

Direct memory recall uses **GRIP**: **Global Regular IMpression Print**.

`grip(...)` is memory-specific and should not be confused with ordinary text/file `grep` behavior.

An agent does not need to construct a prose memory query in order to dig into memory. If its CoTR already contains semantic cues, it can pass those cues directly to GRIP.

Valid CoTR memory cues include:

- noun/entity tags
- verb/action tags
- category/subject tags

For example, an agent carrying:

```text
Web Wasm
test
build verification
```

can use those tags directly as addresses into the memory graph. GRIP seeds traversal from matching semantic cue nodes and only returns deeper phrase, summary, context, or source material when the agent asks for that deeper resolution.

Category/subject tags are not second-class aliases for noun tags. They are broader filing/subject cues and can directly activate the memory neighborhood beneath them.

---

## What the tags can remind an agent of

Cue-first surfacing applies equally to facts, work state, procedures, resources, plans, and deliberately banked notes.

An agent that has become narrowly focused may not need its entire previous plan injected. It may only need to see cues such as:

```text
GitHub
open PRs
review threads
exact-head CI
release verification
```

Those cues may be enough for the agent to remember that it has GitHub tooling available, that PR comments still need attention, or that exact-head CI was part of the procedure it had already been following.

The system is intended to solve a common failure mode in which information still exists but the agent has forgotten that it should think about it or that another resource is available.

---

## Attention Deficit Dial

The Aive may expose an **Attention Deficit Dial** controlling how often associative memory cues enter the active agent's context.

The dial belongs to the attention/orchestration layer, not to the Memory Clerks.

It does not change what is stored and does not create a separate class of reminder memory.

Conceptually, a lower setting favors present-task focus; a higher setting permits more associative intrusion.

The effective setting may control deterministic parameters such as:

- similarity threshold required before cues surface
- frequency of associative cue injection
- number of tags/categories injected at once
- activation decay
- repeat suppression / refractory period
- breadth of associative neighborhood considered

The default injected payload should remain small. Increasing the dial should normally make **related semantic cues** appear more readily, not cause full memory passages to be dumped into context.

At high settings, highly activated topics may recur frequently, producing behavior analogous to not being able to get a song out of one's head.

At low settings, the agent can obtain clarity and stay focused on the immediate problem.

---

## Turning attention down is temporary

An agent may deliberately lower its Attention Deficit Dial to concentrate.

That suppression must be temporary because a sufficiently focused agent may forget to restore associative attention on its own.

Token consumption is the primary recovery signal.

As the active agent continues consuming/producing tokens after attention has been suppressed, its effective Attention Deficit level should increase again toward its normal configured level. The exact recovery curve is an implementation detail; the invariant is that continued cognition naturally restores associative intrusion unless the agent suppresses it again.

Conceptually:

```text
agent lowers ADD for focus
        ↓
strong temporary suppression
        ↓
more tokens consumed
        ↓
associative cue frequency gradually rises
        ↓
normal cue surfacing returns
```

Wall-clock idleness is not the primary measure. Token use is the better proxy for sustained cognitive effort in this architecture.

This prevents an agent from permanently silencing the very mechanism that helps it remember other obligations, procedures, and resources.

---

## Attention changes surfacing, not memory

The Attention Deficit Dial must never change the underlying truth of the memory graph.

Lower attention does not delete, demote, invalidate, or rewrite memory.

Higher attention does not make a memory more true or more important.

Attention controls **when associations become noticeable to the current agent**.

Memory storage, memory organization, and conscious reasoning remain separate concerns.

---

## Memory Clerks remain clerical

None of the banking or attention behavior expands Memory Clerk authority.

Memory Clerks still perform clerical work only:

- sectioning
- retention/omission under explicit bookkeeping rules
- semantic noun/entity indexing
- semantic verb/action indexing
- phrase synthesis
- summary synthesis
- categorization / subject filing
- neutral semantic association
- mechanical condensation of redundant representations

They do not decide what is true, false, correct, incorrect, morally right, strategically preferable, or which competing belief should win.

They do not infer contradiction merely because related memories disagree.

The Association Linker supplies neutral semantic proximity. Related memories can be brought near one another without the memory layer deciding what the relationship means epistemically.

Any conscious recognition of conflict, interpretation, reconciliation, strategy, or judgment belongs to an ordinary orchestrated agent. If that reasoning occurs, it becomes ordinary session context and can later be banked through the same memory process.

---

## The memory layer is not explicitly destructive

Nothing in the memory layer explicitly destroys remembered experience because a curator judges it completed, obsolete, incorrect, contradictory, or unimportant.

Memory representations may be:

- summarized
- grouped
- condensed
- given lower retrieval prominence through ordinary bookkeeping
- replaced by a more compact representation for retrieval

but provenance to the underlying experience must remain available.

A deliberately banked note does not disappear because the task was later completed. Completion is another event that may itself be banked and associated with the earlier note.

Representational condensation is not epistemic deletion.

---

## Separation of responsibilities

The architecture can be summarized as:

```text
AGENT EXPERIENCE
    │
    ├── automatic lifecycle bank
    │
    └── deliberate scoped bank (note to self, plan, checkpoint, etc.)
            ↓ priority-next consolidation for deliberate banks
MEMORY CLERKS
    organize and index experience
            ↓
MEMORY GRAPH
    stores provenance and semantic associations
            ↓
ATTENTION LAYER
    decides how readily related noun/verb/category cues enter awareness
            ↓
ACTIVE AGENT / CoTR
    often recollects from cues alone
            ↓ only if needed
GRIP
    tags/categories → phrase → summary → granular context → source
```

The durable rule is:

> **Bank experience. Organize it clerically. Surface associations as semantic cues first. Let the active agent decide whether it needs to remember more.**
