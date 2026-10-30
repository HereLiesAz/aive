# Puppet rig (PWA)

`tools/puppet-rig` is a small installable web app for cutting, rigging and animating 2D node-creature puppets for aive.
It exports an `aive-puppet-rig` JSON file and a packed atlas. aive loads both directly
(`docs/architecture/PUPPET_RIG_FORMAT.md`).

It is built with vanilla JS and Canvas 2D, and has no dependencies or build step. It works offline once loaded (service worker), with mouse or touch.

## Run locally

```sh
python3 -m http.server 8765 --directory tools/puppet-rig
# open http://localhost:8765/
```

Opening `index.html` from `file://` does not work. ES modules and the service worker need http(s), and `localhost` counts.

## Workflow

1. **Import.** Import one character-sheet PNG, or several part PNGs at once (each becomes a part). To keep editing an earlier export,
   import its `<role>.rig.json` **together with** its atlas PNG.
2. **Cut tab.**
   - Drag a rectangle on the sheet to cut a part. The **Polygon** tool cuts a lasso shape: tap points, then tap the first point or press Enter to close it.
   - **Select / move** nudges an existing cut. Name parts in the panel.
3. **Rig tab.**
   - Set the role and the artboard size (the creature's bounding box).
   - Drag a part to move it. Drag the ring handle to rotate it (Shift snaps to 15°). Drag the center dot to move the pivot; the part stays in place.
   - Pick the parent in the inspector. Re-parenting keeps the on-screen placement.
   - Rename, delete, duplicate, or reorder layers with **Send backward** / **Bring forward**.
     Deleting a part keeps its children in place; duplicates inherit the part's motion and
     copy its attachment points. Reassign any part to another parent without moving it.
   - Set z, rest transform, size, alpha, and a **rigidity** value (0 = fully animated,
     1 = no local position/rotation/scale motion; parent motion still applies).
   - Mark a part as **tail** or **tendril**, choose its longitudinal axis, and edit its
     drawn **length** in artboard pixels. This stretches the sprite without editing the atlas.
   - Choose a **connect** point on its parent to anchor the part to a body/tail/tendril
     socket; `x/y` remain adjustable offsets. Use **Add point**, select
     `tail-base`/`tail-tip` or `tendril-base`/`tendril-tip`, and tap the part.
     Points can be dragged, renamed, moved numerically, or deleted.
4. **Animate tab.**
   - Pick a workflow state (`Pending`, `Ready`, `Active`, `Gate`, `Blocked`, `Complete`, `Failed`; the exact `H2g2WorkflowState` names) and use play/pause/scrub.
   - For the selected part, add keyframes per channel (`x y rotation scaleX scaleY alpha`) at the current phase, with an ease, and/or a sine oscillator (amplitude, frequency, phase).
   - **Blink keys** writes a blink curve on `scaleY`. **Copy state** seeds empty states from the current one.
   - Set the fallback state for states the rig does not define.
5. **Export.** This downloads `<role>.rig.json` and `<role>_rig_atlas.png`. The exported JSON is validated before download.

Work is autosaved to IndexedDB. Use **New** to start over. Pan with Shift-drag, right-drag, or by dragging empty space. Zoom with the wheel or a pinch.

## Using an export in aive

Copy both files into `shared/src/commonMain/composeResources/files/rigs/` and use them as follows:

```kotlin
val loaded = rememberCreatureRig("ux-designer")
if (loaded != null) RiggedCreatureSurface(loaded, state, { motionPhase }, Modifier.size(96.dp))
```

## Files

| File | Purpose |
|---|---|
| `index.html`, `style.css`, `app.js` | Editor UI |
| `rig-core.js` | Format constants, evaluator and validator. It mirrors the Kotlin `PuppetRigEvaluator` and has no DOM access. |
| `sw.js`, `manifest.webmanifest`, `icons/` | PWA shell. Icons come from `branding/haive_monochrome.png`. Bump `VERSION` in `sw.js` whenever a shell file changes. |
| `validate-rig.mjs` | `node tools/puppet-rig/validate-rig.mjs file.rig.json` validates a rig and checks the atlas size. |
| `test/smoke.cjs` | Headless Playwright smoke test (see below). |
| `slice/` | Builds starter rigs from the character art: parts sheets (`slice_parts.py`, `build_rigs.py`). See `slice/README.md`. |

## Validation and smoke tests

The dependency-free core regression is run by the GitHub Pages deployment job,
alongside JavaScript syntax checks. The browser smoke test additionally exercises
the UI, exports and importing a shipped rig.

```sh
node tools/puppet-rig/test/rig-core.mjs
```

```sh
python3 -m http.server 8765 --directory tools/puppet-rig &
PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers NODE_PATH="$(npm root -g)" \
  node tools/puppet-rig/test/smoke.cjs http://localhost:8765/ /tmp/puppet-rig.png
```

The test:
- imports a generated sheet, cuts two parts by dragging, and renames them;
- parents one part to the other, rotates it, adds an attachment point, and keys `Active` motion;
- exports through the button and validates the downloaded JSON;
- exercises kind reassignment, rigidity and stretched length, attachment connection, layer
  ordering, duplication (with motion and points), deletion, and re-validates the edited rig;
- re-imports the UX Designer reference rig and its atlas, re-validates its re-export, and saves screenshots.

## Deployment

The puppet-rig editor is the standalone GitHub Pages application for this repository:

https://hereliesaz.github.io/aive/

`.github/workflows/multiplatform.yml` deploys the static files from `tools/puppet-rig/`
directly to the Pages root via the independent `deploy-pages` job. The editor does not
require the Android, Desktop, JavaScript, or Wasm builds to finish. Its service worker and
manifest use paths relative to the Pages project root (`/aive/`), so the editor remains
installable and works offline once its shell is cached.

The published artifact also retains `/aive/privacy/`, which the Android application
manifest references. The full Aive JS/Wasm web app continues to build in CI but is **not**
included in the Pages artifact until it is connected later.
