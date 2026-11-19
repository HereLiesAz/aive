# The hive is the product: a living hive, not a flowchart

The keeper's founding picture (restated 2026-11-19, after I had drifted from it): **a living flowchart that does not look like a flowchart.** It must not be cards and lines. It must look like a living hive.

My drift: I spent the 1.0 audit on installers, CI and error handling, and put the keeper's picture in a panel with a form under it. The tests passed and the screens did not look like a hive.

## What the screens show now (from `shared/build/hive-screens`, desktop, 2026-11-19)
- The swarm view already has the right ingredients: drawn creatures, capillary tissue, flow pulses, a provider "blood" ring (`1-hive-live.png`, `25-shell-hive.png`).
- But it is a dark rectangle in the middle of a page: a row of coloured pill buttons above it, a Live/Edit switch and a Setup/Swarm switch inside it, zoom buttons in the corner, then a long form below.
- A creature's settings (`2-hive-build.png`) are a long form of pills and fields under the screen: objective, approval, retries, time allowed, runs on, feed it from.
- Setup is the flowchart view. I have not looked at it in this pass.
- Labels such as "Auto", "Implement", "Contract" sit on dark chips over the creatures and collide.

## The keeper's decisions
1. **Hive dominant, thin chrome.** The hive fills most of the page. The rail and a slim status strip stay. Everything else is secondary.
2. **Setup is reshaped as the hive's structure.** The same information (what depends on what) drawn as organic tissue and growth, not cards and lines, in the one hive view.
3. **A creature's settings live inside the creature.** Tap it and it swells open in place; its settings are parts of its body you touch. No form below the screen.

## Proposed approach (needs the keeper's approval; nothing is built)
Work in this order, one PR per step, each looked at in the running desktop app, with before and after screenshots in the PR.

1. **One hive view.** Remove the Setup/Swarm and Live/Edit switches as navigation. One organism. Edit is a state of the organism (a hand-reach, a different light), not a separate screen. Move Pause, Stop, Story, Share and More editing into a thin strip or into the organism's own controls.
2. **Structure as tissue.** Replace the flowchart with growth: a dependency is a strand or vessel that grows between creatures, thick where work flows, thin where it rarely does. No boxes, no arrows.
3. **The creature opens.** One animation: it swells, its parts come forward. Objective, approval, retries, time allowed and source become parts you tap and nudge in place, using the containment grammar already in `docs/Containment.md` (a thing that belongs to something opens inside it).
4. **Labels.** Names and roles sit on the creature or fade in near it, never collide, never sit on a chip over the creature.
5. **Fit.** Phones and wide screens use the same organism, sized to the space.

## Unknowns to settle first
- Does the "Setup" screen the keeper means look like cards and lines in the build they use? I need a current screenshot or the keeper's description.
- Which settings are essential on a creature, and which can live behind a deeper touch.
- How much of the existing creature art and tissue code can carry this, and how much is replaced (`TissueProjection`, `MascotNodeTerrarium`, `WorkflowTerrarium`).
- Feature budget: this is a feature. The version is 0.91 and the keeper wants 1.0 before 0.99. It costs one minor, or one per step if each step is a feature.

## How I will check it
A new rule in `tasks/lessons.md`: I look at the running app, on the screen size the keeper uses, and ask "does this look like a hive?" before I say it is done. Passing tests and a clean compile do not answer that.
