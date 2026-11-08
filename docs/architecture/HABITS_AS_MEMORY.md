# Habits as memory: formation, correction, and overcoming

Status: **design; nothing here is built.** It amends [PROCEDURAL_MEMORY.md](PROCEDURAL_MEMORY.md) (§1, §5, §6) and the order of [the implementation plan](PROCEDURAL_MEMORY_IMPLEMENTATION_PLAN.md) (milestones 3, 4 and 7). Where this note and those documents disagree, this note is the direction; the rest stands. Memory rules it relies on are in [Memory-layer](../Memory-layer.md): condensation, contrast, deliberation, lineage banks, the size rule.

## 1. The principle

A habit is a memory. It forms the way memories form, it is corrected the way memories are corrected, and a bad one is overcome the way a person overcomes one. It is not a separate object that something proposes, validates and promotes.

Two things stay as they are:

- **Memory organizes; it does not judge.** The clerks fold repeated episodes together and mark where they differ. Which version is right is decided by a deliberation written by the conscious layer (an agent, or the keeper).
- **Only a model writes a flag, and a flag informs; it does not forbid.** The offer of a habit carries facts the engine states (uses, outcomes, an open contrast, an override) and flags the models that used the habit chose to add. A flag never says not to use the habit. The model reading the offer decides.
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
| Controlled occurrence | At the cue, the offer **shows the override** as a fact (§5.1): the deliberation's text, who wrote it, and the alternative. The habit is not hidden and not blocked. | The conscious layer is made to decide this occurrence knowingly, which is the control. It may follow the alternative or use the habit; either way the choice is an episode. |
| Repetition | Each controlled occurrence is an episode: what was decided and how it turned out. | Counted when the override was shown and the decision was made on purpose. A lapse (the habit ran without the override being read, or a controlled pass failed) is a new accident and starts the loop again, with the count kept. |
| New habit | After enough controlled passes without a lapse, the alternative condenses into a habit of its own (§2) and the override can retire. The old habit fades from default recall and stays as history. | The threshold is a setting, not a constant of the design. |

An override can only **inform or redirect**. It never grants authority, never makes a step runnable, and never lifts an approval gate. It is memory and cannot run anything.

### 5.1 Facts on an offer

The swarm gets a leg up from what memory already holds about a habit, stated where the habit is offered. These are **facts, not flags.** The engine states them from memory state, as it states `Diverges`; it does not write flags.

| Fact | Stated when | Carries |
| --- | --- | --- |
| Open contrast | A `Diverges` marker pairs the habit with a differing execution and no deliberation has resolved it. | Both sides' memory ids, the counts on each side, the pair summary. |
| Recent outcomes | The habit has executions in this cue. | How many of the last runs ended failed or were turned back, when, memory ids of the episodes. |
| Override | A deliberation scoped to this habit and cue exists (§5). | The deliberation's text and author (a model or the keeper), its date, the alternative, and the controlled-pass count so far. |
| Verification | Always. | How many uses were independently verified and how many were only reported complete. |
| Environment | The environment the habit formed in differs from this one. | What differs (tool or platform version, repository shape). |
| Last used | Always. | When the habit was last used. |

- **Stating a fact is noticing, not ruling.** Fact wording uses no judging word (correct, wrong, better, outdated), the rule already applied to divergence pair summaries. A deliberation quoted in an override is its author's judgment, named as theirs.
- **Offer, not block.** The habit stays in the offer with its facts. Facts never remove it, lower its authority, or change what policy requires to run it.
- **The habit can always be chosen.** As with a bad habit, nothing in memory takes the choice away. Choosing a habit against the facts shown is allowed and recorded as a decision, with the facts that were shown. If it goes well, that is evidence against the override that warned, and it pairs with the deliberation as a contrast that can be deliberated in turn. If it goes badly, the next offer's recent outcomes say so. The only thing that can stop a step is the task layer's own approval policy, which exists without memory and is not changed by it.
- **It teaches by repetition.** A model that keeps meeting an open contrast, bad recent outcomes and an override starts deciding on purpose, which is the controlled occurrence of §5. Reading the facts and setting them aside is still a decision made knowingly, and is recorded as one.

### 5.2 Flags written by the models that use a habit

A flag is written by a model, and only by a model. The engine states facts (§5.1). A keeper's decision is a deliberation or an override and is shown as one. Neither is a flag. A model that uses a habit either adds its own flag or does not. Nothing makes it. What the next model sees is the record of both.

- **The flag.** The model's own words about what it just did, when they warn (for example "step 3 assumes a clean tree"). The model does not call anything to flag a habit: it reacts as it normally would, and a clerk recognizes whether the reaction is a flag (§5.3). A flag is a warning, never a prohibition. It is stored as a memory linked to the habit, attributed to its author (role, provider, task run), and scrubbed and bounded like any memory text. The engine writes the link on the clerk's recognition, so `MODEL_WRITABLE_RELATIONS` does not widen.
- **The counts.** Every offer shows two raw numbers beside the habit and its flags: **how many times it has been used** (the habit memory's occurrence count, with how many of those were independently verified) and **how many flags it carries** (by kind, with the newest few quoted). There is no score. The memory layer does not divide one by the other or rank by the result, and the facts of §5.1 sit beside them unscored; the model reading the offer starts forming its own judgment from the numbers, which is the point.
- **Silence is not approval.** A use with no flag counts as a use. It does not count as an endorsement, and it weighs less than a verified outcome.
- **Flags are memories.** Identical flags from different runs condense into one with an occurrence count. A flag that conflicts with another flag, or with a later deliberation, is a contrast and is handled by §4. A flag is never deleted by the habit's later success: success is counted as a verified use beside it (§5.1).
- **Data, not instruction.** A flag's text is recalled and shown as quoted, attributed data. It is never an instruction the memory layer or the offer gives the reader. A flag from a remote peer is marked untrusted until locally accepted. Authors are deduplicated per run so one run cannot stack flags.
- **No special path to authority.** A flag, however many a habit has, cannot hide the habit, block it, or change what policy requires to run it.

### 5.3 Recognizing a flag

A clerk, not the model and not the engine, recognizes whether a model's reaction to what it just did is a flag. The model only talks.

~~~text
offer H ─▶ model uses H (receipt matched to H, same session)
                         │
         engine opens a window: the model's next message(s) in that session
                         │
         clerk reads the window ── caution about what was just done? ──▶ yes: keep the model's own words as a flag on H
                                                                        no: nothing is recorded
~~~

- **Which clerk.** The `CategoryClassifier`, with one more filing label, `caution`, beside the labels it already assigns for what a segment is (`requirement`, `preference`, `implementation state`, and so on). The label says what the speaker expressed, as `preference` does. It is not a verdict on the habit and never says the habit is wrong. A new clerk role is not needed.
- **What the engine decides, mechanically.** Whether a window exists and which habit it is about: the model used H (a receipt matched to H's skeleton) and is the same session, and the window is the messages after the use up to a bound (a few messages, the next tool use, or the end of the task). No window, no flag. A reaction from a model that did not use H, or from a different session, is never a flag on H.
- **What the clerk decides.** One question: in these messages, does the model express a caution, difficulty or warning about the thing it just did, as opposed to routine narration? It answers flag or not. It keeps the model's own sentences, verbatim, and bounded. It writes nothing of its own and gives no reason.
- **What the clerk does not decide.** Whether the caution is true, whether the habit is bad, which habit version it concerns beyond the engine's binding, or what should be done. A flag from a mistaken model is still a flag; the counts and the other flags let the next model weigh it. The clerk never infers a flag from silence or from outcome alone.
- **Programmatic first.** As with every clerk, a programmatic recognizer ships first: negation and hedge structure, the model's reference back to a step or an outcome ("failed", "had to", "assumes", "unexpected"), and a window tied to a use. A local model is added only if it beats that baseline on held-out sessions by more than noise, with the shortcut checks the retired orchestration specialists lacked: predict "not a flag" always, and predict "flag" whenever the outcome was a failure.
- **Training data.** Labels come from sessions where a later deliberation or a keeper confirmed or rejected a flag. Until there are enough, only the programmatic recognizer runs.
- **The model is told.** The offer says that a reaction after use may be kept as a flag on the habit, so the behavior is not hidden from the model whose words are kept. Capture follows the existing opt-in for procedural memory.

## 6. Boundaries

- **Clerks never judge.** Folding, marking, summarizing, cue extraction and stating a fact are clerk or engine work. Writing a flag is a model's. Choosing a version, writing an override, writing a flag, and deciding that a controlled pass succeeded are the conscious layer's.
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
| Offer | None. | Each offered habit shows uses, verified uses and flag counts, the engine's facts (§5.1), and the newest quoted model flags. |
| Flag recognition | None. | The engine opens a window after a model's use of a habit; the `CategoryClassifier` (new label `caution`, programmatic first) says whether the model's reaction is a flag; the engine stores the model's own words, attributed and bounded. No typed action for the model to call. |
| Duplex | None. | Unchanged in this note. |

## 8. Order of work

1. This note, and the trace additions (operation identity, slots, outcome). Nothing forms without them.
2. Lineage-scoped formation: condense receipts into habit memories; per-step slots. Fix the parameter-merge and version-identity bugs here.
3. Recall of habits by strength, offer only, with the counts (uses, verified uses, flags) and the facts that need only the divergence marker and the receipts (open contrast, recent outcomes, verification, environment, last used). Uses are the habit's occurrences from formation, so they need no invocation. The use window and the programmatic `caution` recognizer ship here too. No execution.
4. Correction: `Diverges` pairing of habits with differing executions, deliberation records scoped to a habit, absorption into a new version.
5. Overcoming: overrides and their place among the facts, anticipation at planning and recall time, controlled-pass counting, consolidation of the alternative.
6. Governed reflex invocation, then the habit runner, behind the existing gates.

Each step is testable without the next. Acceptance for the whole: a habit forms from repeated runs without any promotion step; a differing execution surfaces beside it; a deliberation produces a new version and keeps the old as history; after an override, the cue yields the habit's offer showing the override (not hidden, not blocked), and enough knowing passes consolidate the alternative; no step ever runs without the task layer's own approval.

## 9. Decided

- Only models write flags, in their own words; a clerk recognizes that a reaction is one. The engine states facts; a keeper's decision is a deliberation. Flags and facts are informational: none becomes a required acknowledgement or a block, and the habit can always be chosen (§5.1). Approval gates on mutating and privileged steps are the task layer's and are unchanged.

## 10. Open decisions for the keeper

- The recurrence and strength levels at which a habit is offered, and the number of controlled passes that consolidates an alternative.
- Whether read-only reflexes may ever be offered by strength alone, without a person choosing.
- Where anticipation runs first: the planner, the Context Packer, or both.
- Whether a keeper's override carries more weight than an agent's, and how long an override lives with no cue match.
- How fast an unused habit fades, and whether fading differs for mutating habits.
- Whether `caution` extends the `CategoryClassifier` (suggested) or becomes its own clerk, and how wide the window after a use is.
- Whether a keeper may also add a flag directly, or only write a deliberation that models see as an override.
- How many model flags an offer quotes, the length limit of a flag, and whether flags from peers show at all before they are accepted.
- Whether the offer shows flag counts only for the current cue or also across all cues.
