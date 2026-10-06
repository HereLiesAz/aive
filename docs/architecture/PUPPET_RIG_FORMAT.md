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
| keyframe `t`, sine `phase` | phase of the loop in cycles, 0..1 |

The artboard is `canvas.width` x `canvas.height`, origin at the top-left, +x right, +y down. It is
the creature's bounding box. A renderer scales the artboard uniformly to fit its surface and
centers it (`RiggedPuppetSurface` does this), so a rig can be authored at any resolution. The existing
hand-written rig (`MascotPuppetRig`) uses a 200 x 200 artboard.

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

### Transform composition

For each part, with channel values `dx, dy, dθ, kx, ky, ka` from the current state (below):

```
local = T(rest.x + dx, rest.y + dy) · R(rest.rotation + dθ) · S(rest.scaleX · kx, rest.scaleY · ky)
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

- `arm-socket`: where a detached `NodeArmAssets` arm attaches (the arm's `socketPivot`).
- `prop-slot`: where a prop is held.
- `eye`: eye centers, for gaze effects.

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
This is the declarative form of the `sin(cycle · 2π + offset)` motion in `MascotPuppetRig`. A radian
offset `o` becomes `phase = o / 2π`.

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

The reference sample `files/rigs/ux-designer.rig.json` is generated by
`tools/puppet-rig/samples/make_ux_designer_rig.py`. That script hand-transcribes the atlas rects, placements and procedural
motion of `UxDesignerSpritePuppetSurface` + `MascotPuppetRig`, because those values exist only in code.
The motion is an approximation (see the script).

## Validation

`tools/puppet-rig/validate-rig.mjs <file.rig.json>` checks a rig against this spec and compares the atlas PNG's real size with the declared size.
It shares its validator with the editor (`tools/puppet-rig/rig-core.js`). The JS validator is stricter
than the Kotlin parser: it also rejects unknown state names, unknown channels and eases, and out-of-atlas rects.
