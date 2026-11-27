# The hive is the product: a living hive, not a flowchart

The keeper's founding picture (restated 2026-11-19, after I had drifted from it): **a living flowchart that does not look like a flowchart.** It must not be cards and lines. It must look like a living hive.

My drift: I spent the 1.0 audit on installers, CI and error handling, and put the keeper's picture in a panel with a form under it. The tests passed and the screens did not look like a hive.

## What the screens show now (from `shared/build/hive-screens`, desktop, 2026-11-19)
- The swarm view already has the right ingredients: drawn creatures, capillary tissue, flow pulses, a provider "blood" ring (`1-hive-live.png`, `25-shell-hive.png`).
- But it is a dark rectangle in the middle of a page: a row of coloured pill buttons above it, a Live/Edit switch and a Setup/Swarm switch inside it, zoom buttons in the corner, then a long form below.
- A creature's settings (`2-hive-build.png`) are a long form of pills and fields under the screen: objective, approval, retries, time allowed, runs on, feed it from.
- Setup is the flowchart view. I have not looked at it in this pass.
- Labels such as "Auto", "Implement", "Contract" sit on dark chips over the creatures and collide.

## The keeper's decisions
1. **Hive dominant, thin chrome.** The hive fills most of the page. The rail and a slim status strip stay. Everything else is secondary.
2. **Setup is reshaped as the hive's structure.** The same information (what depends on what) drawn as organic tissue and growth, not cards and lines, in the one hive view.
3. **A creature's settings live inside the creature.** Tap it and it swells open in place; its settings are parts of its body you touch. No form below the screen.

## Constraint: ALL functionality stays
The keeper: the redesign MUST have ALL functionality available. Every control and view that exists today stays reachable. The look changes; nothing is lost. Each step lists what it moved and where it now lives (step 1: the Setup/Swarm switch is now the round "show every creature at once" button on the hive controls bar).

## Proposed approach (needs the keeper's approval; nothing is built)
Work in this order, one PR per step, each looked at in the running desktop app, with before and after screenshots in the PR.

1. **One hive view.** Remove the Setup/Swarm and Live/Edit switches as navigation. One organism. Edit is a state of the organism (a hand-reach, a different light), not a separate screen. Move Pause, Stop, Story, Share and More editing into a thin strip or into the organism's own controls.
2. **Structure as tissue.** Replace the flowchart with growth: a dependency is a strand or vessel that grows between creatures, thick where work flows, thin where it rarely does. No boxes, no arrows.
3. **The creature opens.** One animation: it swells, its parts come forward. Objective, approval, retries, time allowed and source become parts you tap and nudge in place, using the containment grammar already in `docs/Containment.md` (a thing that belongs to something opens inside it).
4. **Labels.** Names and roles sit on the creature or fade in near it, never collide, never sit on a chip over the creature.
5. **Fit.** Phones and wide screens use the same organism, sized to the space.

## Unknowns to settle first
- Does the "Setup" screen the keeper means look like cards and lines in the build they use? I need a current screenshot or the keeper's description.
- Which settings are essential on a creature, and which can live behind a deeper touch.
- How much of the existing creature art and tissue code can carry this, and how much is replaced (`TissueProjection`, `MascotNodeTerrarium`, `WorkflowTerrarium`).
- Feature budget: this is a feature. The version is 0.91 and the keeper wants 1.0 before 0.99. It costs one minor, or one per step if each step is a feature.

## How I will check it
A new rule in `tasks/lessons.md`: I look at the running app, on the screen size the keeper uses, and ask "does this look like a hive?" before I say it is done. Passing tests and a clean compile do not answer that.

## Keeper ruling: spatial frontend replacement (2026-11-26)

Approved product direction, not implemented. This supersedes the earlier screen-preservation constraint and conflicting navigation proposals. The original reason remains: the product must be a living hive, not a flowchart.

- Replace the entire frontend using Compose Multiplatform, including PWA. One persistent 3D orbital environment, not a dashboard or an optional terrarium. No parallel UI or planar alternative. Contextual controls open within the world.
- The Queen is the central Nucleus. The camera normally orbits its equator, focuses on interacting or selected organisms, and yields to user orbit, pan and zoom.
- Memory is a mycelial spore network in that same space. Entering memory swings the camera upward and outward over the north pole. Tardigrade memory clerks collect spores released by neuron creatures, transport them and maintain the hyphae. Actual memories and associations determine meaningful memory activity.
- Neuron creatures vary substantially in anatomy, eyes, tendrils, pigmentation and surface. Fine hairs vary in width and length and move as if underwater. Organisms act independently on a synchronized clock, not under coordinated movement. Flexible attachments remain connected. Macro optics: shallow depth of field, wide aperture, short focus-distance threshold.
- Persistent randomized organism arrangement identifies the workflow. Connective-tissue growth identifies the run. Each new run has distinct growth; reloading adds to existing growth. Persist growth events, topology and scars. Neural guidance, vascular girth and species-specific fungal branching, fusion, pruning and repair are local processes. Procedural morphology is not evidence of execution, memory activity or logical dependencies.
- Existing screens and infrastructure may be rewritten or retired. Wire the complete new UI before cleanup. Ask the Keeper about unsettled interaction placement or removal decisions.

### Antagonist implementation and animation contract

The antagonist uses the same underlying prompt as the GLEE Agent. It is a distinct nightmarish segmented tapeworm, with a radial, multi-ringed predatory maw and muscular peristaltic locomotion. Role, acting agent, organism/body, host and task remain separate identities.

It independently selects reachable tissue and a host, attaches to the tissue, burrows through its actual geometry, enters the neuron creature and wears it like a biological suit. Successful agentic takeover means the target surrenders its assigned work and the antagonist becomes the acting agent for that role, without duplicate execution. Physical occupancy alone does not establish success. Preserve actual runtime authority, provenance and approval boundaries; animation cannot authorize reassignment.

Antagonist stages: Dormant, Searching, Targeting, Approaching, Burrowing, Traversing, BreachingHost, InfiltratingHost, OccupyingHost, UsurpingRole, OperatingAsHost. Host stages: Healthy, Threatened, UnderAttack, Penetrated, Infected, Occupied, PuppetActive. Tissue stages: Healthy, Disturbed, UnderAttack, Burrowed, Infected, Severed, Regrowing. These are design vocabulary, not claims about existing symbols.

Animation: searching head sweeps and probing; targeting alignment and maw opening; attachment and progressive penetration; continuous segmented contractions along the tissue; local bulging, compression, splits and interrupted pulses; host breach and progressive disappearance inside; recognizable host anatomy with abnormal eyes, posture, appendage timing and internal glow; parasite-driven puppet movement after confirmed takeover. Persist damage, scars and repair. No teleporting, scripted target order, or forced camera cuts. Keep dark wet translucent parasite materials and localized changes rather than recoloring the colony. The contextual host inspector distinguishes role, acting agent, occupation, tissue integrity and work.

### Acceptance gates (outstanding)

- [ ] Live Compose frontend and one persistent 3D world, Queen/nucleus, equatorial and north-pole camera modes.
- [ ] Independent species behaviors, flexible tissue, persistent workflow placement and unique cumulative run growth.
- [ ] Real memory spores, hyphae and tardigrade collection/maintenance.
- [ ] Verified reuse of the GLEE prompt, continuous antagonist infiltration and actual role takeover with no duplicate work or authority bypass.
- [ ] Contextual controls wired to actual runtime; phone and wide visual verification; build, regression and cold independent GLEE review.
- [ ] Legacy cleanup only after the new UI is completely wired.

This is a documentation checkpoint. The new spatial and infestation invariants are not yet enforced. Existing runtime and security invariants are unchanged. No implementation checklist item is being marked complete.

### Spatial implementation checkpoint

The shared Kotlin spatial core now contains world coordinates, a definition-keyed persistent site store, and a read-only projection of existing runtime subjects, births, roaming sites, transfers and Memory Web associations. The nucleus is at the origin; Y is north. Layout retains previously allocated sites and does not change dependencies. The new world model does not yet drive a visible surface. No renderer, fake backend, iframe integration, role takeover or run growth journal has been installed by this increment. All acceptance gates remain open. Tests accompany the geometry and memory mapping; GitHub owns execution of checks. Legacy UI remains until the replacement is wired.

The next shared increment adds the equatorial/north-pole camera state, direct-input interruption, and a run-keyed append-only tissue journal. The recovered directional growth-cone styles use stored centerlines, actual organism sites, prior tissue samples and local clearance; an opening id makes repeated delivery idempotent. Distinct openings can append additional branches. This is morphological geometry, not execution telemetry. Existing chunked persistence handles desktop limits. The controller and journal are not yet connected to a visible renderer or live opening events; actual camera input, run history lifecycle, species animation, damage/repair and takeover remain open.

### Direct browser GPU integration checkpoint

The live, non-editing browser workflow field now mounts a Compose-owned WebGL2 canvas in the same document, not an iframe or a second frontend. Shared Kotlin projects the real run, persists tissue on actual field openings/newly visible sites, reads run-scoped memory without changing the selected bank, and owns camera transitions and selection. The GPU draws actual triangle meshes with depth testing; input requests are validated against current bodies and spores. No work or approval actions cross the renderer boundary. The memory note inspector is the existing Compose component. Geometry and input tests accompany this checkpoint.

This is a migration increment, not complete frontend replacement or prototype visual parity. Native GPU backends, richer organism meshes and movement, touch pinch, accessible organism traversal, remaining legacy controls/navigation, full tissue lifecycle, tardigrades, infestation and GLEE prompt reuse remain outstanding. Existing editing, rehearsal, native and renderer-failure paths remain intact until replacement is wired. The initial direct renderer uses simplified procedural meshes rather than importing the archived React/Three.js application.

### Shared mesh and Android GPU checkpoint

The browser and Android now consume one shared Kotlin triangle stream and ES3/WebGL2 shader contract. Tube rings are transported through corners and shared by adjoining segments. Shared ray selection is constrained to the current geometry and memory camera context. The browser transports packed little-endian float vertices, not memory bodies or task objectives. Android mounts a native GLSurfaceView, uploads triangles on its GL thread and sends touch selection, orbit and pinch requests back to Compose. It checks ES3 support; unsupported devices and reported shader failure retain the migration fallback. The Android implementation has not yet been built or visually verified. Desktop GPU, two-finger pan, accessible organism traversal, detailed anatomy and material parity, independent movement, memory clerks and antagonist remain open. No execution authority or legacy cleanup changes.

### Desktop GPU checkpoint

Desktop live runs now mount a JOGL GL3 drawable through Compose SwingPanel. The native drawable consumes the same immutable triangle stream, camera and ray-selection contract as Android and browser. The desktop adapter changes only the GLSL dialect header/precision declaration, uploads geometry when its snapshot changes, and deletes buffers/programs on drawable disposal. Mouse drag orbits, Shift/right/middle drag pans, wheel zooms and a click selects the nearest eligible body. No extra application window or web runtime. JOGL and GlueGen 2.6.0 are pinned to Maven Central's platform-native main artifacts. Headless sessions and reported initialization failures retain the migration fallback. Shader dialect regression tests accompany this increment; actual hardware startup, installer packaging and full visual parity remain unverified. No runtime/approval changes and no legacy cleanup.

### Organic morphology checkpoint

The Keeper's November 26 mobile and wide mockups and the original "Generate Aive Interface Images" request define the visual contract: the world is the interface, not a viewport inset in a dashboard. Deep teal surroundings, macro optics, wet cyan/violet membranes, amber organelles, fine sensory filaments, restrained ivory callouts and circular selection traces. Mobile favors edge controls; wide layouts can support a translucent inspector. Example labels, percentages and memory sizes are not runtime facts. The ten original images remain Keeper-supplied references; this checkpoint records the visual contract, not binary copies or the saved browser HTML.

Ported the archived prototype's compact, elongated, knotted, canopy, swept, lobed and slender families, neurite profile and asymmetric eyelids into shared Kotlin. The live triangle builder uses shape-specific folded membranes, variable eyes, curved irises, sensory stalks, tapered roots, fine multicolored hairs and one lobed nucleus. Cord girth varies along the recorded centerline without changing persisted geometry or logical relationships. The wet specular and warm backlight shader is an approximation, not physical translucency or actual depth of field. Visual morphology does not replace breed/genome or runtime state. Geometry tests accompany this increment. Independent motion and flexible tissue, actual macro depth of field, picking for extended anatomy, detailed materials, complete contextual controls, tardigrades and antagonist remain outstanding. Hardware rendering and performance are unverified. No acceptance gate is closed and no legacy cleanup has occurred.
