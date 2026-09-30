# Google Play listing assets

The release-ready Google Play asset set is generated locally by `tools/play_store_assets.py`; no GitHub Actions workflow in this repository generates or uploads it. Pass exactly four captured screenshots:

~~~bash
python3 tools/play_store_assets.py --output-dir <dir> \
  --screenshot <shot-1.png> --screenshot <shot-2.png> \
  --screenshot <shot-3.png> --screenshot <shot-4.png>
~~~

CI's `publish-play` job uploads only the release bundle and release notes, so listing assets are uploaded to the Play Console manually.

The generator validates the Android adaptive icon structure and centered 66×66dp safe-zone geometry, enforces a single-color Android 13+ monochrome layer, produces a 512×512 RGBA Play icon, a 1024×500 24-bit feature graphic, three themed-icon previews, and normalizes four captured production-UI screenshots to 24-bit PNG.

Current Google Play requirements verified by the generator:

- app icon: 512×512, 32-bit PNG, at most 1 MB;
- feature graphic: 1024×500, JPEG or 24-bit PNG without alpha;
- screenshots: at least two, 320–3840 px per dimension, longest side no more than twice the shortest;
- four screenshots at at least 1080 px are produced to meet Google's current recommendation-surface guidance;
- adaptive foreground and monochrome path geometry is constrained to Android's centered 66×66dp critical safe zone;
- both adaptive launcher resources resolve the foreground, background, and monochrome layers used by the manifest.

The output directory also includes `validation-report.json`, which records the validated adaptive-icon contract, themed preview dimensions, Play icon/feature graphic dimensions, and the exact dimensions and color mode of all four production screenshots.

Screenshot captures use the real Compose Web application and the production `TerrariumVisualProofScreen`, not mock marketing UI.

Suggested alt text:
- Overview: "The Aive workflow control room showing project and orchestration controls."
- Terrarium: "The Aive live workflow terrarium visualizing active agent nodes and execution state."
- Overview landscape: "The Aive control room in a wide layout with workflow navigation and controls."
- Terrarium landscape: "The Aive workflow terrarium in a wide layout with live agent-node visualization."
- Feature graphic: "The Aive network mark beside the words The Aive and Agentic workflow orchestration."
