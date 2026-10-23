# Puppet rig format (`aive-puppet-rig`, version 1)

A puppet rig describes one 2D node creature as cut-out parts in a sprite atlas, arranged in a
transform hierarchy and animated per workflow state. It is produced by the rig editor PWA
(`tools/puppet-rig/`) and loaded directly by aive (`com.hereliesaz.geministrator.puppet`).

A rig ships as two files:

- `<role>.rig.json`: this document.
- the atlas PNG named by `atlas.image`, placed in the same directory.

## Coordinate system and units

| Quantity | Unit |
|---|---|
| `canvas`, part `rest.x/y`, `size`, keyed/sine `x`/`y` | **artboard pixels** |
| `rect` | **atlas-image pixels** (integers) |
| `pivot`, attachment `x/y` | **normalized** to the part's drawn box: (0,0) = top-left, (1,1) = bottom-right |
| rotation | degrees, **positive = clockwise on screen** (same as Compose `rotate` / Canvas 2D) |
| `scaleX/scaleY/alpha` | unitless factors |
| `rigidity` | normalized 0 (full authored motion) to 1 (no local transform motion) |
| `stretch.length` | **artboard px**, drawn length along the specified local axis |
| keyframe `t`, sine `phase` | phase of the loop in cycles, 0..1 |

The artboard is `canvas.width` x `canvas.height`, origin at the top-left, +x right, +y down. It is
the creature's bounding box. A renderer scales the artboard uniformly to fit its surface and
centers it (`RiggedPuppetSurface` does this), so a rig can be authored at any resolution. The shipped creature rigs use artboards of about 400 to 520 px.

## Document

```json
{
  "format": "aive-puppet-rig",
  "version": 1,
  "role": "ux-designer",
  "canvas": { "width": 200, "height": 200 },
  "atlas": { "image": "ux_designer_rig_atlas.png", "width": 256, "height": 256 },
  "parts": [ ... ],
  "attachments": [ ... ],
  "states": { "Active": { "parts": { ... } }, ... },
  "fallbackState": "Pending"
}
```

| Field | Required | Meaning |
|---|---|---|
| `format` | yes | Always `"aive-puppet-rig"`. |
| `version` | yes | `1`. Readers reject other versions. |
| `role` | no | Creature id, kebab-case (used for the file name). |
| `canvas` | yes | Artboard size in px. |
| `atlas` | yes | `image` file name and its pixel `width`/`height`. |
| `parts` | yes | Parts in declaration order (the draw-order tie-break). |
| `attachments` | no | Named points on parts. |
| `states` | no | Motion per workflow state. |
| `fallbackState` | no | State used when the requested state is absent; `null` = rest pose. |

Readers ignore unknown fields.

## Parts

```json
{
  "id": "tendril.0",
  "parent": "body",
  "z": 0,
  "rect": { "x": 0, "y": 64, "w": 48, "h": 96 },
  "size": { "w": 38, "h": 80 },
  "pivot": { "x": 0.5, "y": 0.88 },
  "rest": { "x": -42, "y": 5.4, "rotation": -55, "scaleX": 1, "scaleY": 1, "alpha": 1 }
}
```

- `id`: unique, non-empty. Dotted names (`lid.upper.left`) are conventional.
- `parent`: another part's id or `null`. Parents must exist and must not form a cycle.
- `z`: draw order. Lower z draws first. Equal z keeps declaration order. z is **not** inherited.
- `rect`: source rectangle in the atlas. If it is missing, the part is an invisible **bone**: a pure transform node that parents other parts.
- `size`: drawn size in artboard px. Defaults to the rect size. The rect is stretched to this size.
- `pivot`: rotation/scale origin, normalized to the drawn box. Default `(0.5, 0.5)`.
- `rest`: bind transform. `(x, y)` is where the pivot sits **in the parent's local frame**: artboard
  px for a root part; for a child, the parent's pivot-origin frame, before the parent's rotation and scale. Defaults: 0, 0, 0°, 1, 1, 1.
- `kind`: `body`, `tail`, `tendril`, or `other` (default, for older rigs).
- `rigidity`: number in [0, 1]. Applies to the part's **own** keyed/sine x/y, rotation,
  scaleX and scaleY motion (not inherited parent motion or opacity). 0 keeps full authored
  motion; 1 suppresses local transform motion; intermediate values scale the deviations
  from the channel identity. This is deterministic stiffness, **not** a physics/spring simulation.
- `stretch`: optional `{ "axis": "x" | "y", "length": positiveNumber }` on a tail or tendril.
  This replaces the drawn size **along that axis only**, stretching pixels from its atlas
  crop. A tail's rotation/pivot/parent are unaffected, and the actual atlas remains unchanged.
  A `stretch` on other part kinds is retained but is not applied.
- `connectTo`: optional **attachment id on the immediate parent**. The parent's attachment
  offset (relative to its pivot, evaluated using its stretched size) is added before the
  child's `rest.x/y`. A child with no `connectTo` uses the parent's pivot as before.
  The editor snaps the child's pivot to the chosen socket (rest x/y = 0); clearing the\nconnection preserves its current placement.

### Transform composition

For each part, with channel values `dx, dy, dθ, kx, ky, ka` from the current state (below):

```
f = 1 − rigidity
effective dx/dy/dθ = (dx/dy/dθ) · f
effective kx/ky = 1 + (kx/ky − 1) · f
anchor = connectTo ? localPoint(parent, attachment) : (0, 0)
local = T(anchor.x + rest.x + effective dx, anchor.y + rest.y + effective dy)
      · R(rest.rotation + effective dθ)
      · S(rest.scaleX · effective kx, rest.scaleY · effective ky)
world = world(parent) · local                 (root: world = local)
alpha = clamp(alpha(parent) · rest.alpha · ka, 0, 1)
```

The part's box is drawn in its local frame at `(-pivot.x · w, -pivot.y · h)` with size `(w, h)` =
`size`. So the pivot is the local origin, and rotation and scale happen around it. Alpha is inherited.

## Attachment points

```json
{ "id": "arm.socket.a", "part": "soma", "x": 0.02, "y": 0.62, "kind": "arm-socket" }
```

An attachment point is a named point on a part, normalized to the part's box like `pivot`. It follows the part's
world transform. Its world position is `world(part) · ((x − pivot.x)·w, (y − pivot.y)·h)`.
`kind` is free-form. Conventional kinds:

- `arm-socket`: where a connection to another node attaches. The terrarium picks the socket that faces the partner node.
- `prop-slot`: where a prop is held.
- `eye`: eye centers, for gaze effects.
- `tail-base` / `tail-tip`: root/tip points of a tail.
- `tendril-base` / `tendril-tip`: root/tip points of a tendril.

These `kind` names are metadata for consumers; the evaluator transforms all points
the same way. To join a tail or tendril to the body, place an attachment on the **body**,
set the appendage's `parent` to the body, then select that attachment as its `connectTo`.
The appendage's own base/tip points stay available to other consumers. Attachment ids
must be unique; missing parent-point references are invalid.

## States and motion

`states` keys are **exactly** the `H2g2WorkflowState` enum names: `Pending`, `Ready`, `Active`,
`Gate`, `Blocked`, `Complete`, `Failed`. Lookup is `states[state]`. If that is missing, `states[fallbackState]` is used. If that is also missing, every channel takes its identity value and the rig shows its rest pose.

```json
"Active": {
  "parts": {
    "lid.upper.left": {
      "keys": { "scaleY": [ { "t": 0, "v": 0.5, "ease": "step" }, { "t": 0.92, "v": 0.91, "ease": "step" } ] },
      "sine": { "rotation": { "amplitude": 8, "frequency": 1, "phase": 0.135 } }
    }
  }
}
```

### Channels

| Channel | Combined with rest | Identity |
|---|---|---|
| `x`, `y` | added (artboard px, parent-local) | 0 |
| `rotation` | added (degrees) | 0 |
| `scaleX`, `scaleY` | multiplied | 1 |
| `alpha` | multiplied | 1 |

A channel's value is `keyed + sine`. `keyed` is the keyframe sample, or the identity when the channel has no
keys. `sine` is 0 when absent. For multiplicative channels the sine **adds to the factor**: for example, `scaleY` keyed 1 + sine
amplitude 0.1 gives a factor between 0.9 and 1.1.

### Phase and looping

The renderer passes a free-running `phase`, and `cycle = phase − floor(phase)` lies in [0, 1). Motion always loops.

### Keyframes

`{ "t": 0..1, "v": number, "ease": "linear" | "step" | "easeIn" | "easeOut" | "easeInOut" }`

Keys are sorted by `t`. One key gives a constant. Between key *i* and key *i+1* the value is
`v_i + (v_{i+1} − v_i) · ease_i(u)`, where `u` is the normalized position within the segment and the
**easing belongs to the segment's starting key**. The last key's segment wraps to the first key at
`t + 1`. A cycle before the first key falls in that wrap segment.

| ease | `ease(u)` |
|---|---|
| `linear` | `u` |
| `step` | `0` (hold the starting value until the next key) |
| `easeIn` | `u²` |
| `easeOut` | `1 − (1 − u)²` |
| `easeInOut` | `2u²` for u < ½, else `1 − 2(1 − u)²` |

### Sine oscillators

`{ "amplitude": a, "frequency": f, "phase": p }` contributes `a · sin(2π · (f · cycle + p))`.
`frequency` defaults to 1 and `phase` (in cycles) defaults to 0. Use an integer frequency for a seamless loop.
A radian offset `o` in a `sin(cycle · 2π + o)` formula becomes `phase = o / 2π`.

## Editor part operations

The editor supports deletion (children are reparented without jumping), duplication
(new part id, shared source crop, cloned animations and attachment points), part renaming,
parent reassignment, and forward/backward layer reordering via `z`. Its Rig inspector
edits `kind`, `rigidity`, tail/tendril `stretch`, and the parent `connectTo` attachment.
Attachment points can be created with an explicit kind, renamed, positioned numerically or
dragged in Rig mode, and deleted. Export/import retain all these fields.

## Atlas

`atlas.image` is a PNG holding every visible part. Each part's pixels sit inside its `rect` (polygon cuts are baked as transparency).
The editor packs parts with a 2 px gap using shelf packing. Any packing is valid as long as the rects lie inside
`atlas.width` x `atlas.height`.

## Loading in aive

- Model + parser: `PuppetRig.parse(text)` (kotlinx.serialization, validates ids, parents and cycles).
- Evaluator: `PuppetRigEvaluator.evaluate(rig, state.name, phase)` returns a `PuppetPose`. It holds per-part world
  affine transforms and alpha in draw order, plus attachment positions in artboard px. It is pure common code.
- Rendering: `RiggedPuppetSurface(rig, atlas: ImageBitmap, state.name, phase, modifier)`.
- Resources: put `<role>.rig.json` and its atlas in `shared/src/commonMain/composeResources/files/rigs/`.
  Then `rememberPuppetRig("<role>")` (composable) or `PuppetRigResources.load("<role>")` (suspend) reads
  `files/rigs/<role>.rig.json` and decodes `files/rigs/<atlas.image>`.
- Node creatures: `CreatureRigMapping.slugFor(label)` picks the rig, `CreatureRigCache` loads each rig once and shares it, and
  `CompiledPuppetRig` evaluates a pose into a reusable buffer each frame (it matches `PuppetRigEvaluator`). `arm-socket`
  attachments are where the terrarium's connection links attach. See `docs/swarm-terrarium/README.md`.

Every node creature in the app is drawn from a rig in this directory; see `docs/swarm-terrarium/README.md` for how a label picks its rig and how to add a creature.

## Validation

`tools/puppet-rig/validate-rig.mjs <file.rig.json>` checks a rig against this spec and compares the atlas PNG's real size with the declared size.
It shares its validator with the editor (`tools/puppet-rig/rig-core.js`). The JS validator is stricter
than the Kotlin parser: it also rejects unknown state names, unknown channels and eases, and out-of-atlas rects.

## Generated creature rigs

`tools/puppet-rig/slice/` slices the node-creature parts sheets in `docs/swarm-terrarium/characters/` into parts.
It then assembles them into starter rigs in `files/rigs/` (one per sheet, for example `simulator.rig.json` and `generic-01.rig.json`), checked against each role's `node_XX.png`.
The core roles (`orchestrator`, `researcher`, `qa-engineer`, ...) have only a composed painting and no parts sheet, so
`CreatureRigs.kt` maps them to creatures rigged for other roles (the UX designer has its own layered rig). Treat all rigs as starting points for the editor. See `tools/puppet-rig/slice/README.md`.
