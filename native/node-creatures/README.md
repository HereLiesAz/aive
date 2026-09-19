# Haive Node Creature Engine

This crate owns the visual intelligence of Haive's living workflow graph. Compose is the product/UI host, not the character generator or animation renderer.

## Responsibilities

The engine is responsible for:

- deterministic role + identity seed -> creature genome generation;
- one dominant 3D node/head mass with 3–10 structural antennae distributed around the full body silhouette;
- role-specific faces, antenna terminals, appendages, proportions, surface details, and body plans;
- procedural animation poses driven by workflow activity/state;
- real 3D mesh generation for bodies, limbs, antennae, terminal sockets, role machinery, eyes, pupils, and facial marks;
- orthographic projection into flat vector geometry;
- discrete cel-shade levels and silhouette extraction;
- projected antenna-terminal anchors so graph edges physically plug socket-to-socket.

The rendered look is intentionally a flat graphic reduction of actual animated 3D geometry: silhouettes and planes come from the model rather than being hand-faked as unrelated 2D mascots. The family resemblance to The Haive icon is mandatory: dark rounded body, vivid rim and antennae, compact radial proportions, and simple expressive ring-eye geometry.

## Runtime split

- `native/node-creatures`: generator, geometry, animation, projection, and versioned render packet.
- Compose/KMP: workflow truth, camera/focus controls, drag/drop authoring, labels, inspector bottom sheet, accessibility, and rasterization of Rust-projected geometry.
- Native/WASM bridge: Android, Desktop, JS, and Kotlin/Wasm all consume the same Rust engine so visual behavior remains in parity.

There is no production Compose creature generator. If the Rust renderer is unavailable, the workflow surface reports that renderer failure instead of silently substituting a visually different mascot system.

## Semantic cast

The canonical workflow roles are intentionally different pieces of anatomy, not palette swaps:

- **Orchestrator** — black/red hub organism, dominant eye, dense radial routing sockets.
- **Implementation engineer** — orange builder organism with clamp/tool anatomy and additional working limbs.
- **Crash test dummy** — blue test organism with paired eyes, visible X patch, probes, and coiled sensor antennae.
- **QA engineer** — purple inspection organism with scanning eye treatment and diagnostic display anatomy.
- **Code reviewer** — charcoal/red review organism with heavy-lidded eye, tangled/forked terminals, and blocked-state deformation.

Additional planner/research/generic roles remain deterministic members of the same visual grammar.

## Visual invariants

1. Every creature has 3–10 antennae.
2. Antennae surround the node body rather than occupying only arm-like left/right positions.
3. Every antenna ends in a rendered node/socket; workflow links connect those sockets directly.
4. Role identity is structural, not a color swap or chest icon.
5. Current activity changes pose/behavior, not merely a text badge.
6. Blocked/failed states physically deform or jam the creature and its connections.
7. Labels confirm what the creature already communicates visually.
