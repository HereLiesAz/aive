# Swarm Terrarium Design Brief

The terrarium turns workflow execution into a living, teachable artificial-life system. The workflow nodes are **node creatures**: soft, glossy, expressive 2D creatures drawn from puppet rigs. Each creature tells you the role by its palette, its tendril silhouette and one carried prop, and tells you the state by how it moves.

The creatures are not decoration placed on top of a flowchart. **They are the flowchart.** Role anatomy and props communicate responsibility, live motion communicates current activity/state, and labels confirm rather than establish meaning.

## Anatomy

Every creature shares one body plan: a glossy round soma with two large dark eyes, branching bead-tipped tendrils (the "dendrites"), and a long S-curved tail ending in a bulb. The design vocabulary per role is its colour family, the shape of its tendrils and tips, and a prop held in a tendril.

## Core roles

The fifteen core roles (character directories `001`-`015` in `characters/`) and their look:

- Orchestrator: amber and gold with maroon berries on the tendrils, a conductor's baton and a music note.
- UX Designer: coral pink with a heart-shaped tendril and a heart mark on the soma.
- Researcher: cyan and blue with a magnifying glass.
- Architect: lavender and violet, faceted like cut crystal, with a drafting compass and a blueprint.
- Implementation Engineer: coral and orange around a deep violet soma, holding a wrench.
- EPA Representative: leaf green with sprouting leaves on the tendrils.
- Adversarial Reviewer: crimson and spiky, with a red "X" sign.
- QA Engineer: blue with a magnifier carrying a check mark.
- Release Engineer: violet and gold with a golden key.
- Antagonist: dark red and orange, thorned, carrying a flame.
- Product Manager: rosy pink with a clipboard checklist and a pencil.
- Crash Test Dummy: yellow and orange with hazard stripes, crash-test markings and goggles.
- Recovery Engineer: mint and teal with leaves and medical crosses.
- Code Reviewer: bright blue with round glasses and a code tablet.
- Hall Monitor: lime green with a radar sweep.

The other characters (`016`-`071`: data miner, knowledge keeper, simulator, guardian, ... legal counsel) and the six generic bodies (`000_generic`) follow the same anatomy in their own palettes and props.

## State motion

- Queued/ready states stay restrained: a slow breath.
- Active nodes move: bobbing, tendril sway, and a faster wobble. They state what operation they are performing and show the real task name.
- A gated node holds nearly still and alert.
- Blocked nodes visibly sag; failed nodes sag further, dim and tremble.
- Complete nodes hop once and settle.
- Connections between nodes start and end at the creatures' `arm-socket` attachment facing the partner node, drawn as a link line with a pulse when it is active. If no socket faces that way the silhouette edge is used.
- Drag/drop workflow authoring, dependency adornments, persisted layout, and task selection are part of the production interaction model. GitHub Actions and external services are neurons in the flow, not visitors.

## Rigs

Every node creature is drawn from a puppet rig in `shared/src/commonMain/composeResources/files/rigs/` (format: `docs/architecture/PUPPET_RIG_FORMAT.md`). There is no other renderer. `CreatureRigMapping.slugFor` (`CreatureRigs.kt`) always names a rig for a label, in this order:

1. A label that is exactly a creature's name ("Orchestrator", "ML Engineer") gets that creature.
2. A label that is a rig's own file name (`creature`, `idea-catalyst-1`) gets that rig.
3. A Store persona (the 94 entries of `MascotCharacterCatalog`) wears the creature of its catalog `rigKind`, the closest creature by role: Film Showrunner and Event Producer wear the orchestrator, Brand Strategist the content strategist, Contract Reviewer the adversarial reviewer, and so on.
4. Any other label is classified by `classifyNodeCreatureRole`; the classified role's creature is used.
5. Roles with no creature of their own use an explicit table: Data Engineer -> database administrator, Content Creator -> creative synthesizer, Technical Author -> documentation specialist, Bug Reporter -> test explorer, World Builder -> architect, Storyteller -> communicator, Pathfinder -> strategy scout. The deterministic executors map as Python/JavaScript -> process mapper, Test Runner -> test automation engineer, Deployment -> deployment pilot, Repository Operation -> build engineer, Nested Workflow -> flow director.
6. Unknown or custom roles get one of `generic-01..06`, chosen by a stable hash of the label, so the same custom role always looks the same.

Where a role has several sheets the newest wins. The rig is driven by the node's workflow state and the shared 2.4 s terrarium clock plus a stable per-node phase offset. Rigs load once per slug (`CreatureRigCache`, atlas decoded off the main thread) and are evaluated per frame without allocation (`CompiledPuppetRig`). While a rig loads the node shows a faint neutral blob; if it fails to load, the same blob outlined. The web build shows every creature in every state at `?creatureRigs=1`.

How the rigs are made:

- Characters with a parts sheet (`016`-`071`, `000_generic`) are sliced and assembled by `tools/puppet-rig/slice/` (`slice_parts.py`, `build_rigs.py`).
- The core roles `001`-`004` and `006`-`015` have no parts sheet, only one composed painting each, so they reuse creatures already rigged for other roles, matched to the role's colour. The unused variants go first, then rigs from rarer roles (so a few creatures are shared by two roles):

  | Role | Creature |
  |---|---|
  | Orchestrator | `creature` |
  | Researcher | `data-scientist` |
  | Implementation Engineer | `data-miner-sheet-1-variant-2` |
  | EPA Representative | `optimizer-sheet-2-variant` |
  | Adversarial Reviewer | `idea-catalyst-1` |
  | QA Engineer | `ml-engineer` |
  | Release Engineer | `compliance-officer` |
  | Antagonist | `threat-modeler` |
  | Product Manager | `knowledge-keeper-sheet-1-variant` |
  | Crash Test Dummy | `scheduler-sheet-2-variant` |
  | Recovery Engineer | `sustainability-advocate` |
  | Code Reviewer | `migration-engineer` |
  | Hall Monitor | `disassembled-glossy-slime` |

  The UX Designer is layered from its registered hand-cut layers (`002_ux_designer/rig/bodyparts/`). The Architect (`005`) is sliced from its parts sheet.
- To give a reused core role its own creature, supply a parts sheet (the soma, eyes, eyelids, tendrils and tail separated, on a transparent background) and cut it with the editor in `tools/puppet-rig/`.

## How to add a creature

1. Put the creature's composed painting (transparent background) in `characters/<NNN_role>/`, with its `prompt.txt` and `metadata.json`.
2. Make the rig. For a parts sheet, run `slice_parts.py` then `build_rigs.py`. Or cut and rig it by hand in `tools/puppet-rig/` and export `<role>.rig.json` plus its atlas into `files/rigs/`. Rig and atlas names are `<slug>.rig.json` and `<slug_underscored>_rig_atlas.png`.
3. Check it: `node tools/puppet-rig/validate-rig.mjs <file>`, and look at it in the gallery.
4. Map it: add the role kind to `bySlugKind` in `CreatureRigs.kt` (a new role kind also needs a classifier rule in `NodeCreatureRoleClassifier.kt`), or point a Store persona at it through its `rigKind` in `MascotCharacterCatalog.kt`. Add the expected row to `CreatureRigMappingTest`.
