# Swarm OS: The Aive as a living swarm

Status: proposal. This supersedes `Bowl-OS.md`, keeping every decision it made (tethers, the tide, semantic zoom, gestation eggs, mean adversaries, accessibility) and dropping the water. The genre is the **fishbowl game**: The Sims and SimCity crossed with a virtual pet. You keep a world you can't fully control, read it from its creatures' needs, and change it through a few direct acts. Nothing about it is water, fish or glass.

The rules of that world are `Node-Creature-Genome.md`. The binding rule is `Ecology-and-the-Swarm.md`: nothing happens to a creature unless something happened in a run. Swarm OS is that world made into the app.

## First principles

1. **The view is the swarm.** One top-down field of neural tissue: every agent you keep is a creature in it, every dependency a synapse, every run an egg, every provider a capillary. There's no screen of the app that isn't a distance from this field.
2. **Touching a body never edits the work.** Bodies arrange; connections mean. Dragging a creature moves it (and, near another capillary, moves it to that provider: the one body gesture with a consequence, and the tide records it). Tethers, eggs and knots are the only things that change a workflow.
3. **Needs, not dashboards.** Like a Sim, each creature shows how it's doing in its body first and in four needs second: **Echo**, **Integrity**, **Budget**, **Output used**. Every number in the app is one of those, or a run's progress.
4. **The person keeps; the swarm works.** You never order a creature to act. You build the circumstances (workflows, wiring, budget, drifters) and the work happens inside them.

## Two modes, like a life sim

| | **Live** | **Build** |
|---|---|---|
| What runs | real runs, now | nothing; the field holds still |
| What touch does | inspect, care, answer | draw tethers, cut them, knot them, place eggs, add and release creatures |
| What the creatures do | the run's events, as life | rest, arranged as the draft workflow |
| Heroic act | **Launch** (an objective and a workflow) | **Save** (only when the draft is healthy) |

**Rehearse** sits between them: build-mode wiring, played as a run with no providers called. Creatures act out the order, gates and gestations so you can see the shape before it costs anything.

## Distances (semantic zoom)

The Aive is a hive. All the way out is **the hive**: every workflow is a **comb** in it. A run in flight is a **swarm**, fed by the creatures working on it; when it clears it **emerges** as a new agent, and a finished run is a **comb** you can open to replay. New agents from the Store are **drifters** (bees that join a hive not their own). Creatures folded together are a **cluster**.

There are no tabs. How close you are decides what you see.

| Distance | You see | Replaces |
|---|---|---|
| **The hive** (all the way out) | every workflow as a comb of the tissue, live; drifters arriving; the Store at the edge | Overview, Workflows library, Store |
| **The comb** | one workflow: its creatures, synapses, the eggs in flight | the mind map, the Bowl tab, Runs |
| **A creature** | its needs panel slides over: job, species, kin, provider, what it's working on | task and role editing |
| **Inside a creature** | its mind: instructions, tools, provider settings, what it's thinking now | Swarm role editor, technical inspector |

Pinch moves between distances; double-tap dives one level, two-finger tap surfaces. Rim buttons do the same for anyone who can't pinch.

## Everything has a place in the tissue

- **Runs are eggs.** A launched run grows one egg fed by the tail of every creature working on it, and it swells as tasks finish. It hatches a new agent when the run clears; it withers when the run fails. Finished runs leave a **comb** where the egg was: tap it to replay the run, ghosted, in place. *(Replaces Runs.)*
- **The inbox is a creature at the surface.** A creature that needs you (a gate, an approval, an escalation) rises toward the viewer and pulses. From any distance its comb shows the pulse. Tap to answer, in place. *(Replaces Inbox.)*
- **Artifacts are what a creature leaves along its synapses**: tap a synapse to see what crossed it. Contaminated work shows as the stain it already is. *(Replaces Artifacts.)*
- **The lineage is the family tree** of everyone who lived: kin, rarity, how hard each line is to breed. *(Replaces the Swarm roster.)*
- **The Store is where drifters come from.** Workflows from the Store arrive as new combs; agents arrive as drifters at the hive's edge. **Import** is the cure for the clone trap, and the Store should say so. *(Replaces the Store tab.)*
- **Memory is the tissue's tone.** Its clarity is how much the swarm remembers; its tint is whose voice fills it. Consolidation is sleep. *(Replaces Memory.)*
- **The machinery is the vasculature.** Providers are capillaries, budget is perfusion, compute is the heartbeat. Swipe up from a capillary to reach its settings. *(Replaces Settings, Repositories, Compute.)*

## Care: the keeper's tools

Each tool is a real control (see the ledger). One sits in the creature's needs panel; the rest act on the field.

| Tool | Does | Where |
|---|---|---|
| **Review** | you look at its work; Echo falls | needs panel, Heroic act when Echo is high |
| **Bring in a drifter** | a drifter from the Store | hive edge |
| **Raise budget** | perfusion for a while | capillary |
| **Consolidate** | memory consolidation now | the tissue, long-press |
| **Move** | another provider | drag the body |
| **Detach** | cut its dependencies | needs panel |
| **Hold output** | quarantine its artifacts | needs panel |

## Building, carried over from Bowl OS

- **Tethers.** Draw from a creature's tail tip to another to make a dependency; slice across one to cut it; pinch two apart until it stretch-snaps; press and hold for its knot (always, on success, on failure) or a gate. Collisions only bump.
- **The tide.** Every build edit is undoable by dragging the tide back (or Undo/Redo on the rim). It covers edits, never run history.
- **Clusters.** Lasso creatures into a cluster; it folds into one creature from farther out, and is a nested workflow inside.
- **Duplicate** by pulling a second creature out of one: same config, its own task. That's a clone, and the needs panel says so.
- **Gestation and adversaries,** as decided: every block is an egg fed by every contributor's tail; reviewers go after the work they check, and a failed check wounds.

## Accessibility

Every gesture has a plain equivalent. Each needs panel has a **Connections** list (fed by, feeds, knot) with add, change and remove; the tide has Undo and Redo; every creature, synapse, egg and comb is a labelled semantics node; every distance is reachable from the rim.

## Build order

1. **The field.** The Overview mind map becomes the tissue: top-down, capillaries for providers, synapses for dependencies, eggs for runs in flight, driven only by run events (the ledger).
2. **Needs panel** (slide-over) with the four needs and the per-creature tools.
3. **Build mode:** tethers, the tide, Connections list. Collisions stop editing.
4. **Semantic zoom:** hive, comb, creature, mind. Tabs retire one by one.
5. **Inbox at the surface, combs for runs, lineage, the Store as drifters.**
6. **Breeding:** cleared runs hatch configs; the planner's clones; import; Echo and sepsis live.

Each step ships on its own.
