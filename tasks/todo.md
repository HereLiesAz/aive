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
- [ ] `TissueScene.births` (defaulted).
- [ ] Soma colour sampling per rig; egg tint blend; seeded base tint.
- [ ] `WorkflowTerrarium.kt`: call `projectBirths`, skip in Build, filter Hidden subjects and their relationships.
- [ ] `MascotNodeTerrarium.kt`: draw egg / withered egg, hatch transition, tap selects the task, semantics label.
- [ ] Gallery row, screenshot test (Rehearse sequence).
- [ ] Docs: Node-Creatures.md, Swarm-OS.md, Ecology-and-the-Swarm.md, CHANGELOG.md.
- [ ] `:shared:desktopTest` green; look at the screenshots.

## PR 2: role-bound creatures that roam (own approval first)
## PR 3: orchestrator's first egg is a plan reviewer (own approval first)

## Review
(filled in as each PR lands)
