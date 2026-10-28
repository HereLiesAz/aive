# Bowl OS: The Aive, rethought from the bowl out

Status: superseded by `Swarm-OS.md`, which keeps these decisions and drops the water. Original proposal follows. This replaces the tabbed app with one place: the bowl. `Fishbowl-Builder.md` stays as the record of decisions already made (gestation, eggs on tails, mean adversaries, forked roles, duplicates, slide-over care panel, rehearsal). This document moves the ground under them.

## First principle

**Touching a creature never changes the workflow. Touching what connects creatures does.**

Bodies are for arranging. Tails, tethers and eggs are for meaning. A creature can be dragged, flung, bumped, piled on another and pulled back out a hundred times without editing anything. The workflow changes only when you handle the living connections between creatures, and every change can be undone.

That alone fixes the accident. Today, dropping one creature on another means "depends on." In Bowl OS, two creatures that collide just bump, nuzzle or snap at each other (temperament decides), then drift apart.

## The connection vocabulary

A dependency is a **feeding tether**: the upstream creature feeds the downstream one. It is drawn as a living strand from the feeder's mouth to the fed creature's tail, the same tail that later grows the gestation egg. Hand-made tethers stay distinct from the hand-free drift of the habitat.

- **Make one: draw it.** Press the tip of a creature's tail and pull a strand to another creature; let go on it. The strand sticks, the fed creature turns to face its feeder, and that's a dependency. Let go in open water and the strand dissolves; nothing changes.
- **Cut one: slice it.** Swipe across a tether. It parts with a snap, both ends recoil, and the dependency is gone. A swipe that misses every tether is a camera pan, so a slice can't be fired by accident.
- **Stretch-snap.** Pinch two creatures and pull them apart. The tether stretches, glows thin, and past its breaking point it snaps. That's the "move it out of the way" gesture done on purpose: you feel the tension before you commit.
- **Shape one: press and hold.** Hold a tether to bring up its knot: **always**, **only if the feeder succeeds**, **only if it fails**. The strand changes texture to match: smooth, beaded, barbed. That's `TaskCondition` without a form.
- **Gated feeding.** A tether can carry a valve: an approval gate. A valve shows as a closed knot the strand has to push through; the fed creature waits at it.

There's no hidden state. Everything the workflow knows about order is a strand you can see, grab and cut.

## Undo is a tide

Every connection edit is written to the bowl's tide line, a faint mark on the glass. **Drag the tide back** (a two-finger swipe down the glass, or the tide dial on the rim) and the bowl rewinds: cut tethers knit back together, new ones withdraw, released creatures swim back in. Drag it forward to redo. The tide only covers edits, never run history.

## Semantic zoom replaces the tabs

There are no tabs. **How far you are from the water decides what you see.**

| Distance | You see | Replaces |
|---|---|---|
| The shelf (zoomed all the way out) | Every bowl on a shelf, each a live miniature: workflows and their state at a glance | Workflows library, Overview |
| The bowl | Creatures, tethers, eggs, the run in motion | Overview mind map, the Bowl tab |
| A creature (zoom into one) | The care panel slides over: its job, species, temperament, diet, leash | Task editing, role editing |
| Inside a creature (zoom through it) | Its mind: the role's instructions, provider, surfaces, what it is thinking right now | Swarm role editor, technical inspector |

Pinch to move between distances; double-tap to dive one level, two-finger tap to surface. On a phone it's one continuous zoom; on a wide screen the shelf can sit beside the bowl.

## Everything else is part of the bowl

- **Runs are sediment.** Each finished run settles as a layer of gravel at the bottom of its bowl. Tap a layer to replay it in the same bowl, ghosted. The live run is the water moving now. *(Replaces Runs.)*
- **Starting a run is feeding.** Type an objective and sprinkle it on the surface. The first creatures rise to eat; the run has begun. **Rehearse** is the same gesture with bubbles instead of food: creatures act it out, no providers called. *(Replaces Run buttons.)*
- **The inbox is the glass.** A creature that needs you (a gate, an approval, an escalation) swims to the front and taps the glass. From any distance you see a tap mark on that bowl. Tap back to answer. *(Replaces Inbox.)*
- **Artifacts are pearls.** What a creature produces drops as a pearl on the gravel near it, tinted by kind. Tap to open; drag a pearl onto a creature to feed it as input. *(Replaces Artifacts.)*
- **The hatchery is the swarm.** Beside the shelf sits a hatchery tank of species: built-in roles, your forked roles, Store personas. Drag a species into a bowl to drop in a new creature; dive into a species to edit the role itself. *(Replaces Swarm.)*
- **The Store is a trading post** at the edge of the hatchery: new species and ready-made bowls arrive in bags you float in the water until they acclimate (install).
- **Memory is the water.** Its clarity is how much a bowl remembers. Dive into the water itself to browse it. *(Replaces Memory.)*
- **The stand under the bowl holds the machinery:** providers are the filter, compute is the pump, the repository is the substrate the bowl sits on. Tilt the bowl (or swipe up from the stand) to reach it. *(Replaces Settings, Repositories, Compute.)*

## Arranging, grouping, releasing

- **Drag a body** moves it, never edits. **Fling** it and it glides and settles.
- **Lasso** several creatures to make a **school**: they swim together and fold into one creature when you zoom out. A school is a nested workflow; dive into it to edit inside.
- **Release** a creature by flinging it off the rim. It arcs out and the tide records it, so pulling the tide back brings it home. Its tethers go with it and reattach on undo.
- **Duplicate** by holding a creature and pulling a second one out of it, budding. Same species, its own task.

## The decisions, restated in this frame

- **Gestation:** a creature that is a block or bottleneck grows a tail; the bulb is the egg. Every contributor's tail joins the same egg, and when the block clears the egg hatches the next phase.
- **Adversaries:** they ride tethers backwards. When a reviewer checks a feeder's work it goes after the feeder and its egg, biting and rolling it; a failed check wounds, a passed one leaves the attacker sulking.
- **Rehearse:** bubbles, not food, as above.

## Accessibility is not optional

Every gesture has a plain equivalent, because TalkBack users and anyone with a tremor still deserve the bowl. Each creature's care panel has a **Connections** list (fed by, feeds, with knot type), where each row can be changed or removed, and **Add connection** picks from a list. The tide has **Undo / Redo** buttons. Every creature, tether, egg and pearl is a labelled node in the semantics tree, and the zoom levels can be reached with buttons on the rim.

## Build order

1. **Tethers replace drop-to-depend.** Collisions stop editing. Tethers drawn from tail tips, sliced, stretch-snapped and held for knots. The tide line, with undo and redo. The Connections list in the care panel.
2. **The care panel** (slide-over), with forked roles.
3. **Semantic zoom:** shelf, bowl, creature, mind. The tabs retire one by one as each distance takes over its job.
4. **Feeding to run, rehearsal, sediment runs.**
5. **The glass as inbox, pearls as artifacts, the hatchery, the stand.**
6. **Gestation eggs and adversary attacks** in the renderer.

Each step ships on its own. Step 1 fixes the accident you found.
