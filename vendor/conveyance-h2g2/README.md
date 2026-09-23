# conveyance-h2g2

A composable-set library for [Conveyance](https://github.com/HereLiesAz/Conveyance): The h2g2 style system -- hues, ground-rotation surfaces, and the 8-step Jost type scale -- ported from HG2Gui.

## What this is

Per [azphalt's `spec/composable.md`](https://github.com/HereLiesAz/azphalt/blob/main/spec/composable.md),
a `kind: "composable"` `.azp` package is a **pure header**: it names this artifact's Gradle
coordinates (`library.group` / `library.artifact`) and selects a `templateId`, `hue`,
`surface`, `scale`, and `act` from it. It carries no code of its own. This repository *is* the
artifact a composable package's `library` block points at -- the `.azp` package itself is
authored and published separately, wherever its author chooses; this repo does not need to hold
one.

Example composable manifest referencing this library:

```jsonc
{
  "azphalt": "0.1",
  "id": "com.hereliesaz.azphalt.example",
  "name": "Example",
  "version": "1.0.0",
  "kind": "composable",
  "license": "MIT",
  "compat": ">=0.1",
  "composable": {
    "library": { "group": "com.hereliesaz.conveyance", "artifact": "conveyance-h2g2", "version": "0.1.0" },
    "elements": [
      { "id": "confirm-record", "templateId": "h2g2.tile.record", "hue": "confirm-record", "surface": "recordTile", "scale": "lead", "act": "create", "jobs": ["confirms a destructive action"] }
    ]
  },
  "files": {}
}
```

## What's here

Ported from HG2Gui's own Azphalt style system (h2g2's origin), values unchanged unless noted:

- **`H2g2`** (`H2g2.kt`) -- the 14-entry hue/cap palette and `hueOf(id)`, a deterministic hash
  from any identifier to a hue. This is [`Job.Identify`](https://github.com/HereLiesAz/Conveyance)
  territory, not `Channel.Hue` -- it tells elements apart, it doesn't rank them.
- **`Ground`/`GroundState`** (`Grounds.kt`) -- the six weighted background themes and their
  fold-crease gradient. Adapted from a process-wide singleton (h2g2's own app-level pattern) to a
  `GroundState` a host instantiates and scopes itself, since a library shouldn't impose global
  mutable state on its consumer.
- **`H2g2Type`/`h2g2Type()`** (`Type.kt`) -- the eight-step type scale (`hero` through `micro`).
  Defaults to `FontFamily.Default`; the original uses Jost specifically, but bundling that
  typeface's font files is a per-host asset decision this library doesn't make for you -- pass
  your own Jost `FontFamily` to `h2g2Type()` for the exact original look.
- **`H2g2Surface`** (`Surfaces.kt`) -- **four** shapes: `recordTile`/`note`, from the source-code
  `AzphaltSurface` object, plus `well` (18dp) -- a shape that object doesn't name, but the actual
  design mockups (`docs/HG2Gui Surfaces.dc.html`) use consistently: an ink-background container
  for raw/monospace output, nested inside a `recordTile` or standing on its own. Everything else
  is the fully-rounded `capsule`. `note` is not a lesser shape despite one design doc
  (`docs/DESIGN.md`, which is scoped to the pill menu specifically) suggesting otherwise -- the
  file explorer's own `folderTileShape(isOpen, width)` picks between all three of
  `recordTile`/`note`/`capsule` by state and size, treating them as equally deliberate.
- **`H2g2Page`** (`Page.kt`) -- the one-line convenience for painting a `Ground` behind a whole
  screen. Deliberately **not** a `Templates.registry` entry: every composable manifest element
  requires a non-empty `act` (azphalt `spec/composable.md`), and a page background isn't an
  actionable control -- `Ground`/`GroundState`/`Modifier.ground` (`Grounds.kt`) are already a
  complete, directly callable API; this just wraps the common case.
- **`Templates`** (`Templates.kt`) -- the `templateId` registry. Six templates:
  `h2g2.tile.record` (a `recordTile`-shaped element, one line or a title+`subtitle` two-line
  form), `h2g2.tile.record.detail` (a `recordTile` with a nested `well` holding
  `detailLines` -- the raw-output-panel pattern from the mockups), `h2g2.tile.record.capped` (a
  `recordTile` with `endCapText` riding the right end), `h2g2.tile.note` (a `note`-shaped
  element, an `eyebrow`-step `subtitle` label *above* the body line -- a note is labeled before
  it's read, not captioned after), `h2g2.pill.action` (a plain `capsule`-shaped element), and
  `h2g2.pill.capped` (a `capsule` with `endCapText`) -- all `Offer`-backed, hue-colored via
  `indexOf`, type-set via `h2g2Type().step(scale)`. The two `.capped` templates are the first to
  use `H2g2.caps` and the `endCap` type step, both previously ported but unused -- `docs/DESIGN.md`
  §2's own "label and end-cap sit together at the right end," read for a natural-width tile/pill
  rather than the pill menu's own anchored, overhung ones, with the "darker mate on the end-cap"
  coloring from §7 ("Unchanged from Azphalt") layered on top. Every one of them clips to
  `request.surfaceShape(<its own default shape>)`, so a manifest element's `surface` string really
  does select the shape (`H2g2Surface.byName`); a `surface` this vocabulary doesn't have leaves
  that template on the shape its name implies rather than collapsing everything to `capsule`.

### The workflow/terrarium subsystem

The same package also holds a considerably larger body of work than the style tokens above: a
renderer for a process graph, and an artificial-life reading of that same graph. None of it is
`Templates.registry` material -- these are directly-called composables and plain state classes, not
`.azp` manifest templates -- but it is most of what is actually in `commonMain`:

- **`Workflow.kt`** -- the workflow vocabulary and its default renderer. `H2g2WorkflowNode`/
  `H2g2WorkflowEdge`/`H2g2WorkflowBand` (a band is one topological rank; several nodes in a band
  render as a visible fork) plus `H2g2WorkflowState` (Pending/Ready/Active/Gate/Blocked/Complete/
  Failed) and `H2g2WorkflowMotion`, fourteen named motion personalities a host may assign
  explicitly or let `motionSeed` pick deterministically. `H2g2WorkflowMap` renders subjects as
  identity-hued vector lozenges floating on the `Ground`, connected by thick cubic routes, with
  forks/joins/gates spatial rather than iconographic; a node with `progress` fills from the bottom
  in its own identity color instead of growing a detached progress indicator, a selected subject
  transforms in place and reveals its detail beneath it instead of opening an inspector, and a
  branch inherits a damped 22%-per-generation share of its superior's motion so relatedness is
  visible without an extra highlight.
- **`SemanticWorkflowMap.kt`** -- `H2g2SemanticWorkflowMap`, the same graph under deliberately
  discrete navigation. There is no free camera: `H2g2WorkflowZoomLevel` is exactly
  Overview/Band/Node, `H2g2WorkflowViewportState` (via `rememberH2g2WorkflowViewportState`) owns
  which one is current, and a pinch or a selection moves exactly one semantic level -- one gesture
  can never skip two. Overview renders one summary subject per band; system Back retreats a level.
- **`SwarmSimulation.kt`** -- `H2g2SwarmWorld`, a cheap artificial-life simulation with no
  rendering in it at all. One shared world step, but each creature owns an independent decision
  cadence (~4-9 Hz), personality (`H2g2SwarmPersonality`), RNG stream and behavior state machine
  (`H2g2SwarmBehavior`: Wander/Rest/Investigate/Avoid/Approach/Confer/Transfer/Follow/
  PrepareSpawn/Birth/Working/Blocked/Startled/Recover), plus pairwise `H2g2SwarmContact`s.
  Callers drive it from one render clock and interpolate `H2g2SwarmAgentSnapshot`s at frame rate;
  large stalls are clamped so a backgrounded tab doesn't explode the bowl.
- **`SwarmCharacter.kt`** -- the procedural creatures themselves. `h2g2SwarmGenome(identitySeed)`
  derives a stable body plan (sides, proportions, eye/limb counts, antennae, one of eight
  `H2g2SwarmLocomotion` gaits) from an identity string, identically on every target; color is
  deliberately *not* part of the genome, so two agents stay distinguishable in monochrome.
  `H2g2SwarmCharacter` draws it as low-poly 3D projected into a Compose `Canvas` with real
  discrete light/mid/shadow cel shading (no gradients), hued via `H2g2.indexOf`.
  `structuralSignature()` is the genome's regression-test/cache key.
- **`SwarmAdornment.kt`** -- how a dependency is taught visually.
  `H2g2DependencyManifestation` is Synapse (keep the graph edge visible), Tool (carried) or
  Clothing (worn); `h2g2SwarmAdornment` picks a deterministic `H2g2SwarmAdornmentKind`
  (Probe/Wrench/Clipboard/Lantern/Satchel/Vest/Goggles/Helmet/Cape/Belt) for the latter two, so a
  dense workflow turns into things creatures carry rather than edge spaghetti.
  `H2g2SwarmAdornmentLayer` is the cel-shaded overlay, sharing the creature's own canvas -- no
  meshes, images or physics objects.
- **`Terrarium.kt`** -- `H2g2SwarmTerrarium`, the composable that puts the two halves together: a
  workflow rendered as a living habitat. `H2g2TerrariumSubject` pairs a workflow node with a
  normalized `H2g2TerrariumPosition` (persist that, never pixels), `H2g2TerrariumRelationship`
  carries Dependency/Spawn/Transfer/Confer. Compose supplies one display-frame clock and renders
  `H2g2SwarmWorld` snapshots rather than running AI or physics per frame. Dragging is presentation
  only until the drop lands: `onNodeDroppedOn` is the semantic boundary where a host rewrites,
  stages, or rejects. The orchestrator is host-supplied artwork by contract -- `orchestratorContent`
  is *required* when any subject is an `Orchestrator`, and the composable throws rather than
  procedurally approximating it.
- **`TerrariumServiceActor.kt`** -- `H2g2TerrariumServiceVisit`/`H2g2TerrariumServiceLayer`: a
  transient non-agent visitor. Every external service, whichever one it is, arrives as the same
  fictional USPR mail truck with the same enter/stop/leave choreography -- no per-service skin, no
  RNG, no persistent identity, no simulation loop. The truck is a visual verb ("something external
  arrived"), deliberately not another entity a person has to learn to recognize.

## Status

Covers h2g2's full surface vocabulary (all four shapes have a template) and, per shape, more than
one act/layout pairing where a real pattern from the source app justified it: a record tile can
carry a nested well, an end-cap, or neither. What's still not here: Jost as the actual default
rather than an opt-in -- bundling that typeface's font files remains a per-host asset decision
this library doesn't make for you -- and a `.capped` variant of `h2g2.tile.note`, which the
source app's own vocabulary doesn't obviously call for (a note is small and self-contained; an
end-cap is a pill-menu/record-tile pattern for a "further detail rides the right end" reading, and
forcing it onto every shape regardless of whether the source app actually uses it there is exactly
the kind of unreconciled addition this port has tried to avoid).

An adversarial audit found and this repo has since fixed two real defects: every template's
`Offer` was missing `Modifier.tell(owesTell, weight).clickable { engage() }` -- every element
rendered but was inert, since nothing ever engaged the act on tap (matches
`conveyance-demo/.../Gallery.kt`'s own wiring now); and `hueSeed` resolution went straight to
`H2g2.hueOf`'s hash, so a manifest author naming a real hue (`"hue": "violet"`) never actually got
violet -- `H2g2.indexOf` now checks `hueNames` for an exact match first, falling back to the hash
for anything else.

A later audit found a third: `ComposableRequest.surface` was documented as selecting the shape via
`H2g2Surface.byName()`, but every template hardcoded its own shape literal and never read the field,
leaving `byName` with zero callers -- a manifest field that did nothing. Every template now resolves
through `request.surfaceShape(default)`, with the template's previous hardcoded literal as that
default so no existing caller's rendering changes. All three fixes are pinned by
`src/commonTest/.../TemplatesTest.kt`, which composes each registry template for real and injects a
click to prove the act is actually engaged -- the inert-template defect is invisible to any test
that only inspects values, since a template missing `.clickable { engage() }` still renders
perfectly.

## Using it

```kotlin
repositories {
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.HereLiesAz:conveyance-h2g2:main-SNAPSHOT")
}
```

Resolved via [JitPack](https://jitpack.io) directly from this repository -- `conveyance-core` and
`conveyance-compose` both apply `maven-publish`, which is all JitPack needs, so there is no
separate publish step to configure.

Conveyance itself has no tagged release yet. This artifact's own dependency on it is therefore
**pinned to a fixed commit SHA**, not to `main-SNAPSHOT`: a floating snapshot makes KMP metadata
non-reproducible and can resolve stale per-target publications through JitPack. See the comment on
that pin in [`build.gradle.kts`](build.gradle.kts) for which revision it names and why that one.
Consumers of *this* library can still take `main-SNAPSHOT` as above, or pin this repo's own commit
the same way; switch both to a real tag once one exists.
