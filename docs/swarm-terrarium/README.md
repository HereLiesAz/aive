# Swarm Terrarium Design Brief

The original formatted brief is retained beside this index as `Haive_Swarm_Terrarium_Design_Brief.docx` for historical reference. The implementation direction below supersedes its earlier requirement to use the canonical Haive logo artwork directly as the orchestrator.

The terrarium turns workflow execution into a living, teachable artificial-life system. The workflow nodes are the approved **2D workflow mascots** shown in the current character and animation sheets. They are soft, flat, expressive creatures with role-specific color, appendage shape, props, facial treatment, and state motion.

The earlier native/Rust projected-3D creature direction is retired for production presentation. It produced the wrong visual language and is no longer the source of truth for terrarium bodies. The app renders the mascot family directly in Compose; detached role-specific arm assets remain responsible for relationship attachment and connection motion.

The creatures are not decoration placed on top of a flowchart. **They are the flowchart.** Role anatomy and props communicate responsibility, live motion communicates current activity/state, and labels confirm rather than establish meaning.

Current visual grammar:

- Orchestrator: yellow radial crown, baton, bow tie, calm conductor expression.
- Product/UX: pink organic tendrils, clipboard/heart motifs, friendly expressive face.
- Research/QA/Hall Monitor: blue/cyan inspection species with eye/probe terminals and analytical props.
- Architect/Release: purple structured species with blueprint/key motifs.
- EPA/Recovery: green organic species with leaf/plug terminals, wrench/bandage motifs.
- Implementation/Code Review: orange/blue geometric species with square terminals and tool/tablet props.
- Crash Test Dummy: yellow elastic/wavy species with goggles and energetic motion.
- Adversarial Reviewer/Antagonist: red or black sharp species with visibly hostile review motifs.
- Queued/ready states stay restrained; active states move; blocked/failed states visibly sag or signal; complete states visibly resolve.
- Detached arm sprites connect mascot silhouette edges without stretching, rotating, or deforming the mascot body.
- Active nodes state what operation they are performing and show the real task name.
- Drag/drop workflow authoring, external service visits, dependency adornments, persisted layout, and task selection remain part of the production interaction model.
