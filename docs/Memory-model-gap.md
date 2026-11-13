# Memory model: audit and gap plan

The owner's model (authoritative, verbatim):

> Agent is supposed to reason through the contradiction out loud and that resolution will be condensed into the memory layer like everything else. The memory layer is not a tool that can be called on with a command. Keyword clouds are posted to the agents thought process as they are triggered by the thought process itself, or by what the agent says in chat. The more that the agent lingers on thoughts involving those keywords and the key phrases and the summaries that the memory layer will be injecting into the chat log where the agent can see it, the more and more precise and complete injections will come from the memory layer. Because of compounding and how memories condense around commonalities, actual precision isn't possible, just like with human memories.

Owner addition (simplest form): the only memory tool an agent can call is `/remindme`, and all it does is send the agent's chat session so far to the memory layer. That normally happens only at the end of a session (succeeded or failed); `/remindme` does it early. It takes no note or argument, adds no salience, no cap, no label and no special storage or injection. The layer condenses it like any session, and what comes back later comes back by ordinary association.

The owner described the model, not the mechanism. Every mechanism below (the resolution reader, the dwell counter and ladder, caps, numbers, field names) is a proposal for review, not something the owner specified.

Reduced to tests: (1) no retrieval commands (the only command banks the session so far, early); (2) passive and associative, triggered by the agent's own thinking and chat; (3) injects keyword clouds, key phrases and summaries into the chat log; (4) lingering on a topic (the agent's thinking and chat keep hitting its keywords) brings further, deeper injections: gist and keyword cloud first, then key phrases, then finer summaries and the detail the memories were condensed from; surfacing never changes a stored memory, and "compounding" belongs to storage (memories condense around commonalities over time, so precision is impossible), not to recall; (5) memories condense around commonalities, so recall is gist-like, never exact; (6) contradictions are reasoned out loud and the resolution is condensed like everything else.

Paths below are under `shared/src/commonMain/kotlin/com/hereliesaz/geministrator/memory/` unless stated. Nothing was compiled or run; this is a source read.

## Audit

| Part | Verdict | Where | Why, and what would have to change |
|---|---|---|---|
| Memory package as a whole | PARTLY | `memory/` | Passive banking and cue machinery exist and fit. A command surface, exact-text recall and no depth ladder per topic do not. See rows below. |
| `MemoryLayerController` (observer) | MATCHES | `MemoryLayerController.observer` | Hooks session start, every Message/Thinking/PlanGenerated event (`respondToThought`), user messages (`annotateUserMessage`) and finish. Nothing is called by the agent. Wired for all sessions at `ProviderBackedManagedSessionGateway` (`memoryObserver.onSessionEvent(handle, effectiveEvent) { message(...) }`). |
| Session observer / banking | MATCHES | `MemorySessionObserver.kt`, `QueuedMemorySessionObserver` | Banks the whole session after the step, scrubbed. Memory's own lines carry `MEMORY_MESSAGE_MARKER` and are not re-banked. This is how reasoning "gets condensed like everything else". |
| `recallForPrompt` and its seven blocks | PARTLY | `MemoryLayerController.recallForPrompt` | Runs only when the Query Composer plan says memory is needed (`recallMemory = !enoughEvidence && queries.isNotEmpty()`), so the prompt's cloud is skipped with it. `Memory protocol` teaches `/deliberate` (contradicts). `Relevant memory` and `Memory timeline` print verbatim text with numeric scores and node ids (not gist). `Role lessons` is unconditional and verbatim. `Memory`, `Recalled on request` and `Habits` fit. |
| Keyword / tag clouds | MATCHES | `cueCloud`, `keywordCueHits`, `MemoryTagKeywords` | Triggered by the thought's own uncommon words via an inverted-index lookup; posted as `#tags`; paced by the dial. Gap: the cloud is also lost if the prompt-time path is gated off. |
| Key phrases | PARTLY | `MemoryCueWatcher` (`Phrase`), `MemoryResolution.Phrase` | The cloud carries tags only. Phrases appear only as a trigger the agent writes ("Let me see what I remember about X"), not as something memory injects. |
| Triggers from thinking and chat | PARTLY | `MemoryCueWatcher.read` | Echo and doubling are associative and fire without any syntax. `#tag` and the "what I remember about" phrase are command-like, and they bypass the dial and the frequency filter. Providers that cannot take a mid-session message get recalls as `pending` on the next prompt only. |
| Summaries, consolidation, condensation | PARTLY | `MemoryConsolidation.kt`, `MemoryRewrite.kt`, `MemorySummaryTree.kt` | Memories fold into one shorter current version; size shrinks along an S-curve and never grows. But condensation "must restate its sources' values unchanged", so it is lossless on claimed values, not gist-like. Summaries are fine; what is shown is the stored text. |
| Attention-gated recall dial | PARTLY | `AttentionGatedRecall.kt`, `MemoryAttention.kt` | A rate limiter (threshold, token-bucket interval, hysteresis, novelty window). It is passive, which fits. It does not implement "linger, go deeper": novelty (`noveltyCueIntervals`) suppresses repeats of a memory whatever the depth, and thresholds do not move with dwelling. |
| Lingering brings deeper injections | CONTRADICTS | `MemoryCueWatcher.kt`, `AttentionGatedRecall.kt`, `MemoryCoRecall.kt`, `recordAccess` | Judged against the model (lingering = further, deeper injections; stored memories unchanged by surfacing). There is no per-topic dwell notion that escalates depth: echo and doubling only detect that a word recurred and fire one fixed-depth recall (`MAX_TRIGGERS_PER_THOUGHT` 3, `MAX_TRIGGER_SUMMARIES` 3, `MAX_SUMMARY_CHARS` 400; the cloud is always tags). The opposite is true: the novelty window (`noveltyCueIntervals`) holds back re-surfacing, and the token-bucket interval throttles it. And surfacing changes stored memory, which the model does not call for: `MemoryCoRecall` adds an `AssociatedWith` edge (weight 0.3) every 3rd co-delivery (max 4 per pair, counted in process), a strengthening-like side effect, and `recordAccess` adds `Recalled` edges for resolved memories to fade their contradiction note. |
| Gist-level recall | PARTLY | `recallSummaries`, `MemoryRecallHit.divergenceLines` | Summary resolution and outline levels exist. Output is still verbatim text (400 chars), with `[0.87]` scores and ids, which presents recall as exact. |
| `MemoryDeliberationCommand` | CONTRADICTS | `MemoryDeliberationCommand.kt`, `respondToThought`, `MEMORY_PROTOCOL`, `divergenceLines` | A `/deliberate` command, taught in the protocol and templated under divergent hits (`⚖ to deliberate:`). The model has no commands. |
| `MemoryDeliberationAbsorber` | PARTLY | `MemoryDeliberationAbsorber.kt` | The absorption fits: a resolution becomes the current version, losers become history, size fitted by `MemoryRewrite`. The input does not: it needs a structured `Deliberation` node with `chosen` and cited ids, which only the command creates. |
| `RoleLessonBank` | PARTLY | `RoleLessonBank.kt`, `bankRoleLessons`, `roleLessons` | A passive per-role bank (fits) filled by keeper approval, not by experience, and injected unconditionally and verbatim every prompt rather than by association. Keeper-approved training is a separate deliberate path; it can stay, but delivery should follow the cue path. |
| `/remindme` (bank the session so far, early) | CONTRADICTS (absence) | `QueuedMemorySessionObserver.onSessionFinished`, `MemoryConsolidationQueue.enqueueSession`, `MemoryLayerController.respondToThought` | No `/remindme` parser exists; the only command parser is `/deliberate` (`MemoryDeliberationCommand.parse`). The banking it needs exists, but runs only at session end. |
| Queen's memory | CONTRADICTS (absence) | `orchestration/OrchestrationRuntime.kt` `retrievedMemory` | Field exists, nothing fills it. Not a model conflict, but the queen does not benefit from the background. |

## Direct answers

- **Per-turn from the agent's own thinking/chat?** Mostly yes mid-session (every Message, Thinking, PlanGenerated event and user message). Prompt-time recall is gated by a query plan, and `#tag`/phrase are explicit syntax. PARTLY.
- **Does lingering bring further, deeper injections?** No. Lingering is detected (doubling, echo) but never escalates depth; the novelty window holds repeats back. Separately, co-recall edges strengthen memories on surfacing, which this model does not call for.
- **Gist-level rather than exact?** Condensed storage, exact rendering and value-preserving condensation. PARTLY.
- **Resolution from out-loud reasoning rather than a command?** No. Only `/deliberate`.

- **A command that banks the session early?** Not present. Only `/deliberate` is parsed today.

## Three biggest gaps

1. **The `/deliberate` command is the only way a resolution is condensed.** Prose reasoning is never read as a resolution.
2. **No depth ladder.** Nothing makes injections go deeper as an agent dwells on a topic; novelty suppression does the opposite. And surfacing writes to stored memory (co-recall and access edges), which the model does not call for.
3. **Recall renders as exact.** Verbatim text, scores and ids, and value-preserving condensation, where the model wants gist. Also: prompt-time recall is skipped when the query plan says no.

## Status: what is built

Built after the audit above (the audit table and plan below describe the state before; nothing was compiled or run, CI verifies):

| Item | State |
|---|---|
| A2 `/remindme` | Built: `MemoryRemindMeCommand`, `onRemindMe`, per-capture cursor, incremental end-of-session send, `MemorySessionEnvelope.sequence` for unique episode ids. Wired in the controller where the agent's messages are read. |
| A (retire `/deliberate`) | Built for agents: parser deleted, protocol sentence and `⚖` template removed. The resolution reader (A3 to A5) is **not built**; `MemoryTool.deliberate` and the absorber remain as an unused typed seam because tests and the tool API still use them. |
| B depth ladder | Built: `MemoryDwell` (session only), depth levels cloud and gist, key phrases, finer summaries, condensed sources; novelty keyed by memory and depth (`AttentionGatedRecall.select(depth)`, `fresh`); `recordCoRecall` and `recordAccess` removed (their edges are no longer written; `MemoryCoRecall` is kept for its own test). |
| C gist | Built for injected recall (`MemoryGist.kt`). Stored condensation still restates values (open owner question). `divergenceLines` is no longer used by an injection. |
| D1 prompt cloud outside the plan gate | Built (gateway always asks; the grip inside `recallForPrompt` is gated). D2 to D4 not built. |

## Minimal change plan (original; see Status above for what is done)

### A. Replace `/deliberate` with absorbing a resolution from reasoning text

1. Remove the command from the agent surface: drop the `/deliberate` sentence from `MEMORY_PROTOCOL` and the `⚖ to deliberate:` template line from `divergenceLines`. Keep the marker explanation.
2. Detect an open contradiction in the agent's own text without a command. Memory already knows which hits it delivered as divergent (`MemoryRecallHit.conflicts`). In `respondToThought`, keep per-agent a short list of delivered divergent pairs (node ids plus their key terms), new field on `CueSession`.
3. On each later thought or message, a deterministic detector (new small `MemoryResolutionReader`, pure function, test first) checks whether the text mentions both sides (by key terms, via the same tag keywords used for cues) and contains a resolution statement (a short sentence with cue words such as "so", "therefore", "in fact", "turns out", "the right one is", "out of date", "instead"). If so, take that sentence plus its context as the conclusion.
4. Decide `chosen` deterministically: the side whose key terms the conclusion repeats and the other side's does not. If neither or both, record `chosen = null` (unresolved, which absorbs nothing, the existing rule).
5. Call the existing `MemoryTool.deliberate(MemoryDeliberationRequest(...))` with `sourceSessionId` the agent, `citedNodeIds` the pair, `conclusion` the sentence. `MemoryDeliberationAbsorber` needs no change; it condenses the resolution as today. Delete `MemoryDeliberationCommand` and its test; update `MemoryLayerControllerTest`.
6. Feedback stays passive and quiet: no "deliberation recorded" message. If recall later shows the resolved memory it is just the condensed current version.
7. Optional, later and gated by price/allowance: a model-backed reader behind the same interface for resolutions the keyword pass misses. Do not add a model call by default.
8. Update `docs/Memory-layer.md` (the "Agents record deliberations" paragraph and the protocol text) and `MemoryHabitsSection`/`deliberateHabit` wording if it names the command.

### A2. `/remindme` (bank the session so far, early)

It replaces `/deliberate` as the only command. It does nothing but run the existing end-of-session banking early.

What happens at session end today: `MemoryLayerController.observer.onSessionFinished` calls the workflow layer's `QueuedMemorySessionObserver.onSessionFinished` (memory/MemorySessionObserver.kt). For a Completed or Failed status it builds one `MemorySessionEnvelope` (ids, task prompt, and the captured parts: plans, agent messages, produced artifacts, failures, plus a `Terminal status` part; thinking is not captured and memory's own issued lines are skipped), scrubs every text with `ControlRedaction.scrub`, and calls `MemoryConsolidationQueue.enqueueSession`. The episode id is `episode-<closedAt>-<hash of sourceSessionId>`; the capture is then dropped.

1. **Parser.** A pure parser for a bare `/remindme` line (test first), no argument. Called from `respondToThought` on the agent's output.
2. **Call.** Add a method to the observer that builds the same envelope from the capture so far (parts after the cursor), without the `Terminal status` part and without removing the capture, enqueues it and advances the cursor. Nothing else is stored, labelled, seeded, capped or injected specially. It sends nothing back.
3. **Each part reaches memory exactly once (the design).** Keep a per-capture cursor: how many parts have been sent. `/remindme` sends the parts up to the end of the capture so far and advances the cursor; the end-of-session send sends only the parts after the cursor, plus the terminal status. **Today's code does not do this yet:** it sends the whole capture only at session end, and the cursor does not exist. The change is to make the end-of-session send incremental.
4. **Tests.** Parser; the early envelope is the capture so far; early plus final contain each part once; the capture survives the early send.
5. **Docs.** Update `docs/Memory-layer.md` and the memory protocol text (replace the `/deliberate` sentence with the `/remindme` sentence).

### B. A depth ladder (lingering goes deeper; nothing stored changes)

Model: the longer the agent dwells on a topic (its thinking and chat keep hitting the topic's keywords), the more injections arrive and each goes deeper down the condensation. Surfacing never changes a stored memory.

1. **Dwell counter, session only.** In `CueSession` keep a map from normalized topic (tag) to a small state: a dwell count and the depth last delivered. It is not persisted and never written to the bank. Increment it whenever a trigger (explicit, echo, doubled) or a cloud cue fires for the topic; let it fall back as tokens pass (`consumeTokens` already runs), so a topic left alone resets to the top of the ladder. Pure function, test first.
2. **The ladder.** Depth level from dwell: 0 keyword cloud and gist (today's cloud and one summary), 1 key phrases (`MemoryResolution.Phrase`), 2 finer summaries (`MemoryResolution.Summary` with the outline levels, more of them), 3 the detail the memories were condensed from (follow `CondensedFrom` one step down). Repeated cue hits raise the level by one; the number of injections may also rise with it (`MAX_TRIGGER_SUMMARIES` as a function of level, with a hard cap). The existing per-thought and pending caps stay.
3. **Novelty window: keep it, key it by depth.** Under this model the same text must not be shown twice, but going deeper must not be blocked. Change the key of `surfacedAt` from the memory id to (memory id, depth level), so a memory shown as gist can later be followed by its phrases and its detail, while a repeat at the same depth is still held back. Nothing else in the gate changes (threshold, interval and hysteresis stay: they are rate limits, not storage).
4. **Co-recall edges: stop writing them.** Decide: remove the call to `recordCoRecall` (and leave `MemoryCoRecall` unused, to be deleted later). Adding `AssociatedWith` links as a side effect of surfacing is a strengthening-like change to stored memory that this model does not call for. Existing edges stay as stored (they are history); no migration. Likewise remove the call to `recordAccess` (the `Recalled` edges and the access-count fade of a resolved memory's note); the resolved note itself goes away under A and C, because a resolved memory is just the condensed current version.
5. **Tests.** Pure tests for the dwell function (rise, fall, reset), for the novelty key (same depth held back, deeper allowed), and a test that a recall leaves the store's snapshot unchanged.

### C. Gist, not exact

1. In `recallForPrompt` and `respondToThought`, stop printing `[score]` and node ids; show time range and lineage only. Render summary-level text and trim to the first sentence or the outline level; deeper levels come from the depth ladder (B).
2. Rename the blocks' framing to say recalled gist, not facts. Condensation that must "restate values unchanged" can stay as the stored invariant; the rendering is what loosens. Decide with the owner whether storage should also stop promising value fidelity (this would be a larger change; see open questions).

### D. Triggers and the prompt path

1. Run the cue cloud for the task prompt whenever memory is active, outside the query-plan gate (only the heavy `Relevant memory` grip stays gated).
2. Inject key phrases with the cloud: add `MemoryResolution.Phrase` hits for the strongest cue and print them after the tags.
3. Treat `#tag` and "what I remember about" as ordinary echoes: remove their dial bypass, and stop listing them in the protocol. They may keep firing if the agent happens to write them.
4. Role lessons: deliver through the cue path (when the thought touches a lesson's keywords), plus a one-line standing note, rather than an unconditional block.

### E. Order and tests

A first (removes the contradiction), then B (depth ladder), then C and D. Each step: pure-function tests first (`MemoryResolutionReader`, dwell counter, renderer), then wiring. CI compiles; nothing was compiled for this plan.

## Could not determine

- Whether any provider shows no Thinking events at all, so that only chat messages feed triggers. Not checked per provider.
- Whether the owner wants stored condensation to stop preserving claimed values (a storage change), or only the rendering to be gist-like.
- How well a keyword-based resolution reader will do against real agent prose; it needs real transcripts to tune.
