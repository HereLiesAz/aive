# Legacy Node Creature Engine

This crate contains the former procedural 3D node-creature renderer.

It is retained temporarily for compatibility and migration work, but it is **not the production terrarium character renderer**. The production UI now uses the approved flat 2D workflow-mascot family rendered directly by Compose, with the role-specific detached arm system handling relationship attachment.

## Historical responsibilities

The legacy engine provided deterministic role genomes, procedural animation, 3D mesh generation, orthographic projection, cel shading, silhouette extraction, and projected terminal anchors.

Those projected bodies are no longer shown in the app. Their visual direction was superseded by the approved mascot/animation sheets.

## Production source of truth

- `NodeMascotSurface.kt`: role-specific 2D mascot bodies, faces, props, and workflow-state motion.
- `RustNodeTerrarium.kt`: workflow placement, drag/drop, relationship layout, detached-arm attachment, labels, and status treatment. The filename is historical.
- `NodeArmAssets.kt`: role-specific detached relationship-arm grammar.
- `docs/swarm-terrarium/README.md`: current visual requirements.

The old native/WASM renderer may be removed once remaining packaging references are retired and release compatibility no longer requires the artifact.
