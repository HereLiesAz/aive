# Puppet rig (PWA)

`tools/puppet-rig` is a small installable web app for cutting, rigging and animating 2D node-creature puppets for aive.
It exports an `aive-puppet-rig` JSON file and a packed sprite atlas. aive loads both directly
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
   - Set z, rest transform, size and alpha in the inspector.
   - **Add attachment**, then tap a part to drop a named point (`arm-socket`, `prop-slot`, ...).
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
val loaded = rememberPuppetRig("ux-designer")
if (loaded != null) RiggedPuppetSurface(loaded.rig, loaded.atlas, state.name, motionPhase, Modifier.size(96.dp))
```

## Files

| File | Purpose |
|---|---|
| `index.html`, `style.css`, `app.js` | Editor UI |
| `rig-core.js` | Format constants, evaluator and validator. It mirrors the Kotlin `PuppetRigEvaluator` and has no DOM access. |
| `sw.js`, `manifest.webmanifest`, `icons/` | PWA shell. Icons come from `branding/haive_monochrome.png`. Bump `VERSION` in `sw.js` whenever a shell file changes. |
| `validate-rig.mjs` | `node tools/puppet-rig/validate-rig.mjs file.rig.json` validates a rig and checks the atlas size. |
| `test/smoke.cjs` | Headless Playwright smoke test (see below). |
| `samples/make_ux_designer_rig.py` | Regenerates the reference `ux-designer.rig.json` from the values hard-coded in the Kotlin UX Designer surface. |

## Smoke test

```sh
python3 -m http.server 8765 --directory tools/puppet-rig &
PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers NODE_PATH="$(npm root -g)" \
  node tools/puppet-rig/test/smoke.cjs http://localhost:8765/ /tmp/puppet-rig.png
```

The test:
- imports a generated sheet, cuts two parts by dragging, and renames them;
- parents one part to the other, rotates it, adds an attachment point, and keys `Active` motion;
- exports through the button and validates the downloaded JSON;
- re-imports the UX Designer reference rig and its atlas, re-validates its re-export, and saves screenshots.

## Deployment

The aive web app is published to GitHub Pages by `.github/workflows/multiplatform.yml`. Its `web` job assembles
`webApp/build/pages/` (JS bundle at the root, Wasm under `wasm/`), uploads that folder with
`actions/upload-pages-artifact`, and `deploy-pages` publishes it.

The editor is not deployed yet. Serving it at `<pages-url>/puppet-rig/` needs one extra step in the `web` job,
after the "assemble pages" step and before the upload:

```sh
mkdir -p webApp/build/pages/puppet-rig
cp -R tools/puppet-rig/{index.html,style.css,app.js,rig-core.js,sw.js,manifest.webmanifest,icons} webApp/build/pages/puppet-rig/
```

All paths in the app are relative, and the service worker is scoped to its own folder, so it does not
interfere with the main app. The workflow change was intentionally left out of this branch so it can be made in a dedicated workflow change.
