# Haive Node Creature Engine

This crate owns the visual intelligence of Haive's living workflow graph. Compose is the product/UI host, not the character generator or animation renderer.

## Responsibilities

The engine is responsible for:

- deterministic role + identity seed -> creature genome generation;
- one dominant 3D node/head mass with 3–10 structural antennae;
- role-specific antenna terminals, limb proportions, eye arrangement, and body silhouette;
- procedural animation poses driven by workflow activity/state;
- real low-poly 3D mesh generation for bodies, limbs, antennae, terminals, and eyes;
- orthographic projection into flat vector geometry;
- discrete cel-shade levels and silhouette extraction;
- projected antenna-terminal anchors so graph edges connect creature-to-creature rather than node-center-to-node-center.

The rendered look is intentionally a flat graphic reduction of actual animated 3D geometry: silhouettes and planes come from the model rather than being hand-faked as unrelated 2D mascots.

## Runtime split

- `native/node-creatures`: generator, geometry, animation, projection/render packet.
- Compose/KMP: workflow truth, task/role state, selection, drag/drop authoring, labels, inspector, accessibility, and platform surface hosting.
- Native/WASM bridge: the next integration layer. Android/Desktop use the same Rust core as the Web/WASM target so visual behavior remains in parity.

The current Compose-drawn node creature on the feature branch is transitional scaffolding. It is not the intended production renderer and must be reduced to a host adapter as the Rust bridge lands.

## Visual invariants

1. Every creature has 3–10 antennae.
2. Antennae are silhouette-defining connection anatomy, not decorative stalks.
3. Role identity is structural, not a color swap or chest icon.
4. Current activity changes pose/behavior, not merely a text badge.
5. Blocked/failed states physically deform or jam the creature and its connections.
6. Workflow edges choose real projected antenna terminals.
7. Labels confirm what the creature already communicates visually.
