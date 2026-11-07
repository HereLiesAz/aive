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

## PR 2: role-bound creatures that roam (own approval first)
Revised by the keeper: one creature per role is the **default**, not a rule. A workflow that genuinely needs several creatures of a role, or a keeper who explicitly sets same-role tasks side by side (two independent reviews of the same thing), gets separate creatures.
- Proposed line: same-role tasks that are **ordered** (one depends, directly or through others, on the other) share one creature, which moves between their sites; same-role tasks that can run **concurrently** (no path either way: parallel siblings, Mixture-of-Agents proposers, a deliberate second review) each keep their own creature.
- Consequence: no engine change. Ordered tasks already run one after another, so no per-role lock is needed, and the Mixture-of-Agents question goes away. PR 2 becomes a projection layer plus roaming.
- Grouping: walk the tasks in dependency order; a task joins the first creature of its role whose every task is an ancestor of it, else it starts a new creature.
- Open: an explicit "own creature" switch in Build for a keeper who wants separate creatures for ordered tasks? Not in scope unless asked.
## PR 3: orchestrator's first egg is a plan reviewer (own approval first)
Revised by the keeper: a workflow saved in Build is a *rough design*. The orchestrator still decides exactly how best to execute it, at run time. So the reviewer is not a static insertion into the saved graph: the first thing the orchestrator makes, when it turns the rough design into its real plan, is a reviewer of *that* plan.

Findings (read-only survey of the code):
- Nothing adds tasks to a run once it has started. The plan is made once, before the run exists (`launchOrchestratedWorkflow` runs a planner utility model, then `WorkflowRunFactory.create`); the Orchestrator creature plays no part in it. `RoleAuthority.ProposePlan` has no consumer. Docs claiming tasks "hatch from the Orchestrator's bulb" are not backed by code.
- A run has no copy of its workflow, and launching overwrites the saved Build workflow with the expanded one (added test, environment, Mixture-of-Agents tasks).
- PR 1 therefore has no hole today: every task exists when the run starts.

Decision (keeper: "both"): ship the launch-time reviewer first, then build run-time orchestration in stages, carrying the reviewer into it.
- [ ] 3a. Launch-time reviewer: a step in `WorkflowDefinitionPreparer.prepare` inserts a reviewer-kind task that every task with no dependencies waits on, so its egg is the first one visible. Reconcile with `EnginePlanGate`, `PlanRejectionService` and the Hall Monitor (define what a rejection does; THREAT_MODEL invariant 4: an agent reviewer decides no approval gate). Pure function plus tests.
- [ ] 3b. A run-owned copy of the workflow (also stops launch overwriting the Build design); the terrarium and `projectBirths` read it.
- [ ] 3c. An engine call that amends a started run (validate with `WorkflowGraphValidator`, never touch terminal or running tasks, create the `TaskRun`s, `refreshReadiness`, persist atomically), new events, policy checks for injected tasks.
- [ ] 3d. The trigger: the Orchestrator's task returns a plan the coordinator applies through 3c, reviewer first. Move the reviewer from 3a into it.
Each step gets its own design approval.

Decided: unborn creatures beyond the next generation stay hidden (no ghost nodes).

## Review
(filled in as each PR lands)
