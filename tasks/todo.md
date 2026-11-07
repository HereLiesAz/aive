# Eggs, birth, roaming, plan reviewer

Plan of record: three PRs, in order. Rules: no dice, no drift (docs/Ecology-and-the-Swarm.md); glance at the field and know where the run is.

## Step 0: a working build
- [x] Sandbox: Maven Central 429s through the egress proxy. A local Gradle init script (`~/.gradle/init.d/central-mirror.gradle`, not committed) routes Central through Google's mirror and puts Google Maven ahead of the Plugin Portal.
- [x] Sandbox: `vendor/conveyance-h2g2` (b17d8d9) and `vendor/conveyance-h2g2/vendor/Conveyance` (214e43e) cloned at CI's pinned commits. Both are gitignored.
- [ ] Baseline `:shared:desktopTest --tests '*TissueProjectionTest'` passes.

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
- [ ] `:shared:desktopTest` green; look at the screenshots.

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
- [ ] 3b. A run-owned copy of the workflow (also stops launch overwriting the Build design); the terrarium and `projectBirths` read it.
- [ ] 3c. An engine call that amends a started run (validate with `WorkflowGraphValidator`, never touch terminal or running tasks, create the `TaskRun`s, `refreshReadiness`, persist atomically), new events, policy checks for injected tasks.
- [ ] 3d. The trigger: the Orchestrator's task returns a plan the coordinator applies through 3c, reviewer first. Move the reviewer from 3a into it.
Each step gets its own design approval.

Decided: unborn creatures beyond the next generation stay hidden (no ghost nodes).

## Saved-hive planning (keeper: "okay, do it")
- [x] `SavedHivePlanning.kt`: `savedHiveRoughDesign`, `plannedHiveDefinition`.
- [x] `ApplicationRuntimeOrchestration.kt`: shared `planAndDecide`; `launchPlannedSavedWorkflow`; pending decision carries `savedDefinitionId` + `supplementalRoles`; fingerprint includes the design.
- [x] `launchSavedWorkflow(planner, planReviewer, confirmPlan, planStore)`; App library-host launch passes them and installs LaunchProgress + PlanningStages.
- [x] `SavedHivePlanningTest` (11).
- [ ] Known: a package hive whose definition is not in persistence cannot resume a pending decision after restart (dropped, asked afresh).

## Unpinning h2g2 and Conveyance (keeper: h2g2 must not pin; everything updated as soon as possible)
- [x] aive: CI checks out h2g2 and Conveyance at their latest `main` (no `ref:`), and the Gradle dependency is `main-SNAPSHOT`, not a commit hash.
- [ ] HereLiesAz/Conveyance#80 and HereLiesAz/conveyance-h2g2#17: AGP 9.5.1 (Gradle refuses two AGP versions in one composite). Verified locally with all three on 9.5.1. h2g2#17 also adds Dependabot (h2g2 had none).
- [ ] Merge those two, then rebase and merge aive#648 (AGP) and aive#650 (tokenizers, awaiting Dependabot's rebase).
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
