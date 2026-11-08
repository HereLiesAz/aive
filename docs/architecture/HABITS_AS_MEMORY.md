# Habits as memory: formation, correction, and overcoming

Status: **design; nothing here is built.** It amends [PROCEDURAL_MEMORY.md](PROCEDURAL_MEMORY.md) (§1, §5, §6) and the order of [the implementation plan](PROCEDURAL_MEMORY_IMPLEMENTATION_PLAN.md) (milestones 3, 4 and 7). Where this note and those documents disagree, this note is the direction; the rest stands. Memory rules it relies on are in [Memory-layer](../Memory-layer.md): condensation, contrast, deliberation, lineage banks, the size rule.

## 1. The principle

A habit is a memory. It forms the way memories form, it is corrected the way memories are corrected, and a bad one is overcome the way a person overcomes one. It is not a separate object that something proposes, validates and promotes.

Two things stay as they are:

- **Memory organizes; it does not judge.** The clerks fold repeated episodes together and mark where they differ. Which version is right is decided by a deliberation written by the conscious layer (an agent, or the keeper).
- **Strength decides what is offered. Policy decides what runs.** A habit that has become strong is recalled first. Whether any step may run is still decided by the task and executor layer: mutating and privileged steps keep their approval gates however strong the habit is. Recall never executes.

## 2. Formation

A habit forms by repetition, with no promotion event.

~~~text
run 1 receipt ─┐
run 2 receipt ─┼─ episodes ─ condense (similar) ─▶ one habit memory ─▶ stronger with each use
run 3 receipt ─┘                                    skeleton + slots,    fading when unused
                                                    body in the sidecar
~~~

- **Episodes already exist.** Each finished system-executor task banks a redacted receipt as a contextual episode (opt-in, `proceduralTraceCaptureEnabled`).
- **Similar episodes condense** under the existing rules: same frame, same or synonymous filler. The condensed memory keeps the occurrence count, the time range and the weight, and the sources become history linked by `CondensedFrom`.
- **The habit memory is a skeleton plus an address.** The skeleton is the ordered steps (each a symbolic executor operation). The executable body stays in the procedure sidecar, content-addressed, because executable text never goes into a memory node. The memory holds a summary and the address.
- **Slots come from the variant register.** What differs across the repeated runs (a path, a branch, a target) is the filler. Each frame's register already lists its fillers with age, context and occurrence count. A filler that varies becomes a typed slot of the habit, and its register is the set of values the slot has taken. This replaces hand-declared parameter schemas.
- **Strength comes from use and from verified outcomes.** Using a habit strengthens it as any memory is strengthened by access. Weight counts runs whose outcome was independently verified more than runs that were merely reported complete. A repeated mistake is a pattern for the reasoner, never a stronger default (the existing rule for repetition).
- **Disuse fades it from default recall.** Nothing is deleted. A faded habit is still reachable by lineage.
- **Scope is the lineage bank, not the run.** Today a procedure bank is one workflow run, so nothing can repeat across runs. Habits live in the workflow's lineage bank (its own records plus its ancestors'), with other project workflows read-only, exactly as memories do.

## 3. Cues

A habit comes up when its **cue** appears: the context in which it has fired before. A cue is derived, not declared, from the same material memory already indexes: task cues, executor kinds, code symbols, the objective's terms, and the slot values seen. Recall matches cues against the current task. The cue is part of the habit memory and is shared by every version of it.

## 4. Correction

A habit is never edited in place.

1. **Noticing.** An execution of the habit's frame with a different outcome, a different step, or a failure is an episode that the divergence marker pairs with the habit: `Diverges`, same question, different answer. Both sides are recalled together.
2. **Deliberation.** The conscious layer reads both sides plus the verification evidence and writes a deliberation naming the version it judges correct (or that the habit is wrong here). No deliberation, no consolidation of a contrast.
3. **Absorption.** Consolidation absorbs the deliberation into the chosen memory as a **new version**. The old version becomes history, linked to the reason, and fades from default recall as the dispute fades with use.

This replaces the plan's validate-and-promote state machine for what a habit *is*. Version numbers stop being the identity: the lineage (`Supersedes`) is, which also removes the reuse of a number after a version is deleted.

## 5. Overcoming a habit

Correction after the fact is how a habit is first noticed to be wrong, and it is not yet how it is overcome. A habit that fires automatically is not stopped by learning it was wrong. It is stopped the way a person stops one: it happens by accident, enough times that each time has to be corrected; then the person begins to **anticipate** when it will come up, takes **deliberate control of that moment**, and does so again the next time, and the next, until the other behaviour is the habit.

~~~text
accident ─▶ correction ─▶ anticipation ─▶ controlled occurrence ─▶ repeat ─▶ new habit
 (habit fires,    (Diverges +    (its cue is     (the conscious layer      (each controlled   (the alternative
  outcome wrong)   deliberation)  watched for)     decides this time)        pass is an episode) consolidates)
~~~

| Stage | What exists | Rule |
| --- | --- | --- |
| Accident | The habit was offered or run and the outcome was wrong. | Recorded as an episode; paired with the habit by the divergence marker. |
| Correction | A deliberation: this habit is wrong in this cue. | Written by an agent or the keeper. A keeper's "stop doing that" is a deliberation the same way. |
| Anticipation | An **override**: the deliberation scoped to the habit and its cue, with the alternative (do X, or ask me). | The cue is checked at **planning and recall time, before dispatch**, so the moment is met ahead of time, not after the habit has fired. |
| Controlled occurrence | At the cue, the habit is not offered as a default. The offer is replaced by a flag: the habit would fire here, and you decided to control it, with the deliberation text. | Control passes to the conscious layer for that occurrence. The habit does not run. |
| Repetition | Each controlled occurrence is an episode: what was decided and how it turned out. | Counted. A lapse (the habit ran anyway, or the controlled pass failed) is a new accident and starts the loop again, with the count kept. |
| New habit | After enough controlled passes without a lapse, the alternative condenses into a habit of its own (§2) and the override can retire. The old habit fades from default recall and stays as history. | The threshold is a setting, not a constant of the design. |

An override can only **restrict or redirect**. It never grants authority, never makes a step runnable, and never lifts an approval gate. It is memory and cannot run anything.

## 6. Boundaries

- **Clerks never judge.** Folding, marking, summarizing and cue extraction are clerk work. Choosing a version, writing an override, and deciding that a controlled pass succeeded are the conscious layer's.
- **Sidecar for bodies.** Executable content is content-addressed in the procedure sidecar. A memory node holds the skeleton summary, the cue and the address. Nothing executable enters `MemorySnapshot`, and `MODEL_WRITABLE_RELATIONS` does not widen.
- **Privacy and erasure are unchanged.** Capture stays opt-in and structural. Forgetting a source episode removes habits that depended on it, as procedures are removed now.
- **Popularity risk.** Condensation lets the most common behaviour become the strongest. The guards are the divergence marker, outcome-weighted strength, and the rule that repetition is evidence, not truth.
- **Remote peers.** A procedure or override from a peer is an untrusted proposal until locally accepted. Duplex stays a transport.

## 7. What changes in the plan and the code

| Area | Today | Change |
| --- | --- | --- |
| Trace | Executor kind, phase, status, artifact kinds, ids. | Add symbolic operation identity, typed slot values (allowlisted, bounded, no free text) and a verified-or-merely-reported outcome. |
| Bank | One workflow run. | Lineage bank, read through ancestors. |
| Extractor | One reflex per task definition; one chronological habit per bank, 2 to 8 tasks. | Cross-run condensation of receipts into habit memories; cues and slots from the register. No inferred causal order from timestamps. |
| Lifecycle | Candidate → Validated → Active (proposed, absent). | Formation, contrast, deliberation, absorption, fade. The only gate is policy at run time. |
| Parameter merge | `generalize` merges slots across different steps and makes a parameter optional if any step lacks it. | Slots are per step. This is a bug to fix regardless. |
| Version identity | `max + 1`, reusable after a delete. | `Supersedes` lineage plus digest. |
| Import | Digest and source checks only. | Check the definition against its artifact; validate operation ids and bindings; scan free text. |
| Recall | None. | A procedural lane offering a few habits by strength, and anticipation checking overrides before dispatch. |
| Duplex | None. | Unchanged in this note. |

## 8. Order of work

1. This note, and the trace additions (operation identity, slots, outcome). Nothing forms without them.
2. Lineage-scoped formation: condense receipts into habit memories; per-step slots. Fix the parameter-merge and version-identity bugs here.
3. Recall of habits by strength, offer only. No execution.
4. Correction: `Diverges` pairing of habits with differing executions, deliberation records scoped to a habit, absorption into a new version.
5. Overcoming: overrides, anticipation at planning and recall time, controlled-pass counting, consolidation of the alternative.
6. Governed reflex invocation, then the habit runner, behind the existing gates.

Each step is testable without the next. Acceptance for the whole: a habit forms from repeated runs without any promotion step; a differing execution surfaces beside it; a deliberation produces a new version and keeps the old as history; after an override, the cue yields a flag instead of the habit, and enough controlled passes consolidate the alternative; no step ever runs without the task layer's own approval.

## 9. Open decisions for the keeper

- The recurrence and strength levels at which a habit is offered, and the number of controlled passes that consolidates an alternative.
- Whether read-only reflexes may ever be offered by strength alone, without a person choosing.
- Where anticipation runs first: the planner, the Context Packer, or both.
- Whether a keeper's override carries more weight than an agent's, and how long an override lives with no cue match.
- How fast an unused habit fades, and whether fading differs for mutating habits.
