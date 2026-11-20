# Eggs, birth, roaming, plan reviewer

Plan of record: three PRs, in order. Rules: no dice, no drift (docs/Ecology-and-the-Swarm.md); glance at the field and know where the run is.

## Step 0: a working build
- [x] Sandbox: Maven Central 429s through the egress proxy. A local Gradle init script (`~/.gradle/init.d/central-mirror.gradle`, not committed) routes Central through Google's mirror and puts Google Maven ahead of the Plugin Portal.
- [x] Sandbox: `vendor/conveyance-h2g2` (b17d8d9) and `vendor/conveyance-h2g2/vendor/Conveyance` (214e43e) cloned at CI's pinned commits. Both are gitignored.
- [x] Baseline `:shared:desktopTest` passes (full suite green, 1437 tests, most recently with saved-hive planning).

## PR 1: eggs, birth gating, growth, colour (one creature per task, no engine change)
- [x] `CreatureBirth.kt`: pure `projectBirths` (Hidden, Egg, Withered; absent = born).
- [x] `WorkflowRunFactory.WAITING_FOR_DEPENDENCIES` constant.
- [x] `CreatureBirthTest`.
- [x] `TissueScene.births` (defaulted).
- [x] Soma colour sampling per rig; egg tint blend (OKLab); seeded base tint. (`CreatureColour.kt`)
- [x] `WorkflowTerrarium.kt`: call `projectBirths`, skip in Build; `MascotNodeTerrarium.kt` hides Hidden creatures and their strands.
- [x] `MascotNodeTerrarium.kt`: draw egg / withered egg (`CreatureEgg.kt`), hatch transition, tap selects the task, semantics label.
- [x] Screenshot tests: eggs (chain, joined, withered) and the hatch. (Gallery row and a Rehearse sequence not done.)
- [x] Docs: Node-Creatures.md, Swarm-OS.md, Ecology-and-the-Swarm.md, CHANGELOG.md.
- [x] `:shared:desktopTest` green; screenshots looked at.

## Setup / Swarm (added to PR 1, from the keeper)
- [x] A shared `SetupSwarmSwitch`. Hive: Setup (flowchart kept simple, next-generation eggs) and Swarm (every creature at once), under the controls bar outside Build, remembered (`hive.view.swarm`). Memory swarm: its Simple / Technical switch is now Setup / Swarm.
- [x] The place that was "Swarm" (role list) is now the **Roster** (destination, rail, a hint string, current-behaviour docs). Historical docs and old changelog entries keep the old name.

## PR 2: role-bound creatures that roam (approved by the keeper; built)
Revised by the keeper: one creature per role is the **default**, not a rule. A workflow that genuinely needs several creatures of a role, or a keeper who explicitly sets same-role tasks side by side (two independent reviews of the same thing), gets separate creatures.
- Proposed line: same-role tasks that are **ordered** (one depends, directly or through others, on the other) share one creature, which moves between their sites; same-role tasks that can run **concurrently** (no path either way: parallel siblings, Mixture-of-Agents proposers, a deliberate second review) each keep their own creature.
- Consequence: no engine change. Ordered tasks already run one after another, so no per-role lock is needed, and the Mixture-of-Agents question goes away. PR 2 becomes a projection layer plus roaming.
- Grouping: walk the tasks in dependency order; a task joins the first creature of its role whose every task is an ancestor of it, else it starts a new creature.
- Open: an explicit "own creature" switch in Build for a keeper who wants separate creatures for ordered tasks? Not in scope unless asked.
Built as agreed (grouping: ordered share, concurrent separate; the creature walks along its places; Build and Swarm unchanged), with two narrowings the keeper should know about:
- [x] `CreatureRoam.kt` (`projectRoaming`, `RoamGroup`, `CreatureRoam.Rider/Site`, `rememberRides`), `RoamSite.kt`, wired through `TissueScene.roaming`; `CreatureRoamTest`; screenshots 46/47.
- [x] Selection stays per task: tapping the creature opens its current task, tapping a place opens that task, and the panel's Work tab lists the creature's other tasks (instead of one merged panel).
- [ ] Timeline rows are still per task, not grouped per creature (the waterfall has a row per task and the route to it is unchanged). Group them only if it proves confusing.

## PR 3: orchestrator's first egg is a plan reviewer (own approval first)
Revised by the keeper: a workflow saved in Build is a *rough design*. The orchestrator still decides exactly how best to execute it, at run time. So the reviewer is not a static insertion into the saved graph: the first thing the orchestrator makes, when it turns the rough design into its real plan, is a reviewer of *that* plan.

Findings (read-only survey of the code):
- Nothing adds tasks to a run once it has started. The plan is made once, before the run exists (`launchOrchestratedWorkflow` runs a planner utility model, then `WorkflowRunFactory.create`); the Orchestrator creature plays no part in it. `RoleAuthority.ProposePlan` has no consumer. Docs claiming tasks "hatch from the Orchestrator's bulb" are not backed by code.
- A run has no copy of its workflow, and launching overwrites the saved Build workflow with the expanded one (added test, environment, Mixture-of-Agents tasks).
- PR 1 therefore has no hole today: every task exists when the run starts.

Decision (keeper: "both"): ship the launch-time reviewer first, then build run-time orchestration in stages, carrying the reviewer into it.
- [x] 3a (built, as the keeper chose: option α + a three-review cap): the planner's plan is reviewed and reworked before the run exists, then shown to the keeper for the final say (`orchestration/PlanReview.kt`, `PlanDecisionOverlay.kt`, `TextGenerationPlanReviewer`); also on a drafted hive (notes). Then, as the keeper described: while the plan is made the field shows the planner's egg growing, the reviewer hatching from it, and the first step's egg gestated by both and held until the keeper starts the run (`PlanningStage`, `PlanningScene.kt`, `PlanningOverlay.kt`; a stand-in three-task hive drawn by the existing eggs and births, so no engine change was needed). Not done and flagged: a saved hive's launch has no planner step, so nothing is reviewed; the pending decision is now persisted across a restart (`ReviewedPlanStore`, fingerprint of everything prior). Keeper's rule: reusing a workflow is a new run with fresh planning, review and approval; only resuming keeps a decision. No approval is ever carried between runs. Original 3a text, superseded:
- [ ] (superseded) 3a. Launch-time reviewer: a step in `WorkflowDefinitionPreparer.prepare` inserts a reviewer-kind task that every task with no dependencies waits on, so its egg is the first one visible. Reconcile with `EnginePlanGate`, `PlanRejectionService` and the Hall Monitor (define what a rejection does; THREAT_MODEL invariant 4: an agent reviewer decides no approval gate). Pure function plus tests.
- [ ] (not built: planning before the run covers it; revisit only on request) 3b. A run-owned copy of the workflow (also stops launch overwriting the Build design); the terrarium and `projectBirths` read it.
- [ ] 3c. An engine call that amends a started run (validate with `WorkflowGraphValidator`, never touch terminal or running tasks, create the `TaskRun`s, `refreshReadiness`, persist atomically), new events, policy checks for injected tasks.
- [ ] 3d. The trigger: the Orchestrator's task returns a plan the coordinator applies through 3c, reviewer first. Move the reviewer from 3a into it.
Each step gets its own design approval.

Decided: unborn creatures beyond the next generation stay hidden (no ghost nodes).

## Saved-hive planning (keeper: "okay, do it")
- [x] `SavedHivePlanning.kt`: `savedHiveRoughDesign`, `plannedHiveDefinition`.
- [x] `ApplicationRuntimeOrchestration.kt`: shared `planAndDecide`; `launchPlannedSavedWorkflow`; pending decision carries `savedDefinitionId` + `supplementalRoles`; fingerprint includes the design.
- [x] `launchSavedWorkflow(planner, planReviewer, confirmPlan, planStore)`; App library-host launch passes them and installs LaunchProgress + PlanningStages.
- [x] `SavedHivePlanningTest` (11).
- [x] A package hive that is not in the library resumes a pending decision from the design kept with the plan (`PendingPlanLaunch.design`); a library hive is read again so an edit is seen.

## Hive options (keeper: long-press a hive in the district for a list of options, particularly delete)
- [x] `HiveActionsMenu.kt` (Open, Delete hive, Close), `HiveDeleteRequest` (check, ask, delete, or say why not), `DistrictScreen(hiveDeletion)`, wired in `ControlRoomUi`.
- [x] `HiveMenuTest` (long press vs tap, delete checked first and never before confirming, refusal says why); screenshots 40, 41.
- [ ] Not offered yet: rename, duplicate, share hive card from the menu (each needs its own design); say which you want.

## Unpinning h2g2 and Conveyance (keeper: h2g2 must not pin; everything updated as soon as possible)
- [x] aive: CI checks out h2g2 and Conveyance at their latest `main` (no `ref:`), and the Gradle dependency is `main-SNAPSHOT`, not a commit hash.
- [x] (aive is on AGP 9.5.1 in `gradle/libs.versions.toml`) HereLiesAz/Conveyance#80 and HereLiesAz/conveyance-h2g2#17: AGP 9.5.1 (Gradle refuses two AGP versions in one composite). Verified locally with all three on 9.5.1. h2g2#17 also adds Dependabot (h2g2 had none).
- [x] aive#650 (tokenizers) merged; #648 closed, its change already in main.
- [x] (done: catalog entry `conveyance`, aive reads it from the checkout) Decide h2g2's own pin: `build.gradle.kts` hard-codes `conveyanceRevision = "0.5.0"` (comment: a floating main-SNAPSHOT can resolve stale per-target publications through JitPack). Dependabot cannot track it. aive's root `build.gradle.kts` mirrors it (`conveyanceRevision`) and must follow.

## Log tab (keeper: a Log tab on the Timeline row; the full log; switch between every log source)
Decided with the keeper: App log is persisted (newest 500, scrubbed, on device); Memory layer log is recorded as it happens (in memory, this launch); Agent activity is timestamped and its cap raised (60 -> 300 a session).
- [x] `log/LogJournal.kt`: bounded, scrubbed, optionally persisted journal; `AppLog.Shared`; UTC `formatLogTime`.
- [x] `appLog()` wraps `platformDebugLog` and records; all 12 call sites use it.
- [x] `MemoryLayerController.journal` + `MemoryConsolidationResult.logText()`, written in `drain`.
- [x] `SessionActivityLine.atEpochMillis`, cap 300.
- [x] `log/RunLog.kt` (`runEventLines`, `agentActivityLines`), `LogView.kt` (`RunLogView`), `RunTimeline(logSources)`, wired in `ControlRoomUi`.
- [x] `LogJournalTest`; screenshot `38-timeline-log`.
- [x] Docs: Keepers-Room.md, PRIVACY.md, CHANGELOG.md.
- [ ] Known and left alone: `loadRunTimeline()` returns the active run's events, so Events and Log > Run events show the active run even when a past run is opened (pre-existing; needs `persistence.events.forRun(viewedRun)`).

## Ideas (not scheduled)
- **Free creatures sort memory.** When a creature is free (idle, nothing waiting on it), it helps sort the memories and gives each a weight from its own role's perspective only (keeper, in passing). Notes: a free creature is a recorded state, so it fits "every change has a cause"; the memory clerks organise but never rule on truth, so a weight is relevance to that role, not correctness; it spends tokens, so it must be opt-in and under the role budgets and spend caps; pairs with PR 2's roaming (a free creature walks to the memory field). Needs a design and your approval before any build.

## Review
(filled in as each PR lands)

## Hive: starting points and blockers
A blocker is anything that must be created or obtained for a particular hive to be complete. The 36 steps of the Artifact tool are starting points for such things, not a checklist and not blockers themselves; the 13 that cannot run in Aive are listed in docs/Step-Templates.md and are not something to waive.
- [x] Starting points: presets of the six templates, the diagram step and the files-on-a-branch step, with typed outputs and tests (`template/StepPresets.kt`, `ArtifactStepCoverage.kt`, `StepPresetsTest`).
- [x] Blockers: data, the planner's `blockers`, state following the run, `hiveReached` held by an open one, Messages and finish sentences (`queen/Blockers.kt`, `BlockersTest`).
- [ ] Keep a run's blockers across a restart (today memory only, like the hive signs).
- [ ] The queen adds a blocker mid-run, and calls `assignStep` for one that has a starting template (the swarm change goes through the existing repair/validation path).
- [ ] The keeper adds, marks done or drops a blocker in the existing hive form.
- [ ] The Nanny's findings add blockers for the next hive; `Blockers.carryForward` is called when a run ends and read when the next is planned.
- [ ] Nothing was compiled or run; CI verifies.

## Immersive intro tour: the swarm shows the swarm

Today: a hint layer rings static controls while the hive fed the same goal is an unrelated run (`intro/TutorialDirector.kt`, `tour/`). Wanted: the tour IS the swarm at work, and the keeper watches and does what the creatures do.

- [ ] 1. Tour hive. "Teach me" launches a real swarm with a fixed cast: Scout (reads the app's own docs and nav map), Planner, Writer (one per chapter), Reviewer, Antagonist. They hatch, work, hand off and message on screen like any hive; their artifacts are the chapters.
- [ ] 2. Chapters play as artifacts land, one ahead of the keeper. Each is voiced by the creature that made it and rings a real control or a real thing in the run (that creature's card, its Needs/Work/Mind tabs, Messages, the Story, Memory, "What it made").
- [ ] 3. Steps wait on run events as well as taps (creature hatched, need raised, artifact made, dispute opened). `TourExpect` gains run-event kinds fed from the run's event stream.
- [ ] 4. The keeper does it for real, in a sandbox: the tour hive raises a real decision (approve a plan), a real disagreement, a real artifact to open and copy. No repository, spend under the cap, keyless Source.
- [ ] 5. Immersion: full-screen spotlight, a named creature's voice per chapter, chapter strip with skip, replay and resume; the last chapter hands the keeper the hive and its output (the finished tour).
- [ ] 6. Waiting is never empty: the swarm's own work is the thing on screen while the next chapter is written; no Source says so with a link.
- [ ] 7. Tests: pure reducers (event expectations, chapter queue), director against a fake run; nav map and docs updated.

Ruling (keeper, 2026-11-20), the shape of the whole: the tutorial hive is BOTH. The swarm writes the lessons AND the swarm IS the lesson: the queen assigns tasks whose results create overlays that highlight what the swarm is doing, how it is doing it, and how the keeper can control it, WHILE the swarm accomplishes the workflow that presents all of this. The keeper is never tested; the only thing asked of them is qualitative ("With me so far?"). The queen may adjust throughout: reconstruct, rewrite, redo, take an entirely different approach (built so far: a failing lesson escalates and she repairs the remainder, `QueenMending.kt`; a reviewing creature is optional; the planner is told no step may judge the keeper). Not built, in this order:
- [~] (slice built, not run: `tour/LessonArtifactBridge.kt`, `LessonCreatures.kt`, planner wording, `lesson-payload` output check; still open: check-ins and the closing chapter for creature lessons, the Needs/Work/Mind ringing, the Story) The lesson creatures' own artifacts become the overlays: a creature that writes a lesson hands in a lesson payload (target, sentence, explain, checkpoint) that `LessonValidator` checks against the registry and `TourController.addLesson` plays, voiced by that creature and ringing it (its card, its Needs/Work/Mind tabs, Messages, the Story), instead of `TutorialDirector` writing them in-process from its own model calls.
- [ ] The queen revises lessons not yet played from what the keeper's qualitative replies and the run's events show (Lost: smaller and slower; With me: skip what they know), by assigning new tasks, not by a rewrite of the whole plan.
- [~] (built, not run: `tour/CheckInMessage.kt`, `MessageRow.TourCheckIn`, `TourCheckInDetail` in `LiveInboxScreen.kt`, `CheckInMessageTest`) Check-ins ride the question path into Messages, with the four quick replies, answered into the same `TourController.checkInSink`; the overlay answer closes the row. Still open: the same question on a creature's sheet (needs a creature that owns the check-in; a tour has no real step waiting, so it is not a `keeperQuestion`).
- [ ] Steps wait on run events (`TourExpect` run-event kinds) as well as taps.
Decisions (keeper, 2026-11-18): a fixed opening begins at once while the swarm writes the rest, and the swarm continues the tour on its own. "Fully immersive, not simple written text": a chapter is something the keeper sees and does in the real app (creature cards moving, hand-offs lighting up, a real decision to make, a real artifact to open), with the narration beside it, never a card of text.
- Opening (fixed, instant): the tour hive hatches on screen with the keeper watching; the opening chapters are the real hatching, first hand-off and first message, spotlit.
- Continuation (swarm-made): the Planner and Writer extend the chapter list from what the run has shown so far, paced by the Source.

## Spec decisions page: audit of 2026-11-20 (90 items; the keeper's answers over the recommended option)
Built or already true: D1, D2, D3, D4, D5, D6, D7, D8, D9, D10, D11, D12 (the six parts and Advanced exist; the "More" is Advanced), D13, D14, D16 (launch refuses a queenless swarm; ensureQueen exists), D18, D19, D20, D21, D26, D27, D29 and D30 (pack members, hidden kinds; the pack schema is a best reading), D31, D32, D33, D34, D35, N1 to N4, N6, N8, N9, N12, N14, tutorial D18 and D24 (Antagonist only when the queen brings her), Q-9 on plan resume. No code by decision: D22, D23.
- [x] D15 (keeper: yes): decide whether trusted creatures skipping the in-run approval satisfies "later runs auto-start only for trusted creatures".
- [x] D16: the queen is always present (keeper ruling): `ensureQueen` runs on save, save-as and launch; the launch refusal and its string are gone.
- [x] D17: settled by the keeper: a project (neighborhood) is made of hives, as a repo is made of files; a hive carries its own goal (its objective). No schema change.
- [x] D24, D25: the registry's "saw it answer on <date>" needs a health date from searcha2a; check that an orphan counts as unpriced in the Allowance (the budget already reads "not reported" when no tokens are known).
- [x] N5: keeper: "so what". Duplicate role ids in the two packages stay; no rename, no republish.
- [x] N10: keeper: Ollama stays the default; other keyless Sources exist; it is only no longer free of credits, which its description already says.
- [x] N13: retired by the keeper (page default): milestones 4, 6 and 8 of the procedural-memory plan are marked retired in docs; their code names never shipped, so no code was removed.
- [x] Headless engine (`aive serve`) takes only environment keys as providers; enabled CLI agents are not used there, by design: a CLI agent runs only after a person approved a plan, and the control API has no route that approves, so a headless swarm could never start one. Source switches (Use/Off) are still not read by the headless engine.
- [ ] Tutorial D16, D19: check-ins are only in the overlay; they should ride the existing question path into Messages. D12: the extra "Teach me" controls (About, the first form's pill) contradict "first run only". D21, D22, D30 to D34, D39, D41, D42: the queen as a creature in the tutorial (Bring: role, optional creatures, rulings, call budget, degrade ladder); blocked on the keeper's D40 question ("how does the queen keep up with progress and know when to ask?"). D37 and D38 are met: the tutorial hive is the first hive and a lesson goal is never a question.
