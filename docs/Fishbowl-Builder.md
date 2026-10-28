# Fish Bowl Builder (design draft)

Status: approved design; its decisions carry into `Swarm-OS.md`, which replaces the bowl framing.

## The idea

Building a workflow is keeping a fish bowl. You drop node creatures into a setting, make them interact, and tend each one until the bowl does the job. The bowl you build in is the same bowl you watch during a run. No separate form-based editor.

## What already exists

The run view is already a bowl. `docs/swarm-terrarium/README.md` describes it.

- **Creatures:** puppet rigs for roles (`CreatureRigs.kt`, `CreatureRigMapping.slugFor`), animated by run state.
- **Bowl:** `GeministratorWorkflowTerrarium` (`WorkflowTerrarium.kt`). Overview's mind map shows it, and `projectWorkflowTerrarium` lays it out from a `WorkflowDefinition` plus a run.
- **Interaction:** `addTerrariumDependency` / `removeTerrariumDependency` (`WorkflowTerrariumAuthoring.kt`). Dropping one creature on another makes it depend on that one, and validation rejects a cycle.
- **Layout memory:** `TerrariumLayoutStore.kt`.
- **Gallery:** `CreatureRigGallery.kt` shows every rig in every state.

What's missing is a way into the bowl without a run, a way to add and remove creatures, and a place to tune one.

## The model, unchanged

| Bowl | Domain |
|---|---|
| Bowl | `WorkflowDefinition` (draft until saved) |
| Creature | `TaskDefinition`; its species is the `RoleDefinition` (`roleId`) |
| Interaction | `dependsOn`, plus `condition` for a gated interaction |
| Setting | Workflow-level policies: integration, concurrency, test design, prompt reuse, redaction |
| Tending a creature | Editing that task and its role |

There's no new persisted format. A bowl saves as a `WorkflowDefinition`, so runs, export, setup files and Store packages keep working.

## Interactions

1. **Empty bowl.** Workflows sub-tab → **New bowl**, or **Open in bowl** on any workflow. The bowl renders the definition with every creature at rest (Queued), and there's no run.
2. **Add a creature.** A species tray along the bottom edge lists the built-in roles, your custom roles from Swarm, and installed Store personas. Drag one into the water to create a task, named after the species until you rename it.
3. **Make them interact.** Drag a creature onto another to make it depend on that one, using the existing drop semantics. A rejected drop wobbles the creature and shows the reason. Long-pressing the link between two creatures sets a condition (Always, or only on success or failure) or removes the link.
4. **Tend one.** Tap a creature to open its care panel:
   - **Name and objective**, the task's job in one line.
   - **Species**: change the role and the rig changes with it.
   - **Temperament**: the role's instructions and preferred provider. This edits the role, so a warning shows when other bowls share it.
   - **Diet**: required artifacts and acceptance criteria.
   - **Leash**: approval policy and compute placement.
   - **Release**: remove the creature from the bowl.
5. **The setting.** Tapping the water opens bowl-wide settings, which are the workflow policies.
6. **Health.** `WorkflowGraphValidator` runs live. A creature with a problem (missing role, unreachable, no objective) sags, the same motion as a blocked run, with the reason in its panel. **Save** stays off until the bowl is healthy.
7. **Release into the wild.** **Run** launches the bowl through the existing launch path. The same bowl then animates the run.

## Where it lives

Overview → **Workflows**. The library list stays, and every entry gets **Open in bowl**, alongside **New bowl**. Swarm keeps managing species (roles), which is also where the tray's custom species come from.

## Phases

1. **Read-only bowl:** open any saved workflow in the bowl with no run. *Built: Workflows → pick a workflow → **Bowl** tab.*
2. **Interactions:** drag a creature to add or remove a dependency, with live health. It reuses the authoring functions.
3. **Species tray and Release:** add and remove creatures.
4. **Care panel:** task fields, then role fields.
5. **Setting panel, Save and Run.**

Each phase can ship on its own.

## Decisions

- **Roles fork.** Tuning a creature whose role other bowls use copies the role into a bowl-local one; the original stays untouched.
- **Duplicates allowed.** Several creatures of one species can share a bowl (parallel tasks, one role each).
- **Care panel slides over the bowl** on every screen size; the bowl stays visible and live behind it.
- **Dry run.** A **Rehearse** action plays the bowl without calling providers: creatures act out the order, gates and gestations so the shape can be checked before **Run**.
- **Gestation.** Every block and bottleneck is shown as a gestation: a creature that holds work back (a gate, an approval, a dependency many tasks wait on, a blocked or slow task) swells as the work behind it accumulates, and when it clears, it gives birth to the next phase: the downstream creatures emerge from it. The projection already records a `birthParentId` per creature (`WorkflowTerrariumProjection.kt`), which is the hook for whom each creature is born from. In **Rehearse** the same births play out in order.
- **Eggs and tails.** The tail bulb is the egg, and gestation grows the whole tail: a creature with nothing to gestate has little or no tail, and as a block forms the tail extends and its bulb swells into the egg. Every creature whose work contributes to completing that block has its tail connected to the same egg and gestates it together with the others, so a shared bottleneck reads as one egg fed by several tails. The egg swells as their contributions land and hatches into the next phase when the block clears. A contributor that finishes detaches its tail; a contributor that fails leaves its tail limp on the egg.
- **Adversaries are mean.** Adversarial roles (Adversarial Reviewer, Antagonist, Crash Test Dummy, Code Reviewer, Hall Monitor, and any role the classifier files as adversarial) don't inspect politely: when one checks another creature's work it swims over and attacks, biting, prodding and shoving the creature it is reviewing. The same applies to an egg that creature is gestating, which gets cracked at, poked and rolled around. The attack lasts as long as the check. A failed check leaves the target wounded (the existing failed sag and tremble) and the egg damaged; a passed check makes the attacker back off, sulking. Who attacks whom comes from the review relationship in the workflow: the reviewing task and the tasks it depends on.
