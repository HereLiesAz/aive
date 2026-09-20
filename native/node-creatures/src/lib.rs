mod engine;
mod genome;
#[cfg(not(target_arch = "wasm32"))]
mod jni_bridge;
mod math;
mod protocol;

pub use engine::{
    build_mesh, project_mesh, terminal_anchor_toward, Camera, MaterialClass, Mesh, RenderEdge,
    RenderFrame, RenderTriangle,
};
pub use genome::{
    activity_verb, animate, generate_genome, role_from_label, Activity, AntennaGenome,
    CreatureGenome, CreaturePose, RoleArchetype, TerminalKind,
};
pub use math::{Vec2, Vec3};
pub use protocol::{encode_render_frame, HaiveBuffer, PACKET_MAGIC, PACKET_VERSION};

/// Full deterministic render pass for one node creature.
///
/// This is intentionally UI-toolkit agnostic. Platform adapters may rasterize the returned vector
/// packet with Skia, Canvas2D, WebGL/WebGPU, or a native GPU surface without changing creature
/// generation or animation semantics.
pub fn render_creature(
    role_label: &str,
    identity_seed: &str,
    activity: Activity,
    time_seconds: f32,
    camera: Camera,
) -> RenderFrame {
    let role = role_from_label(role_label);
    let genome = generate_genome(role, identity_seed);
    let pose = animate(&genome, activity, time_seconds);
    let mesh = build_mesh(&genome, &pose);
    project_mesh(&mesh, camera)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn built_in_roles() -> [(&'static str, RoleArchetype); 15] {
        [
            ("Orchestrator", RoleArchetype::Orchestrator),
            ("Product Manager", RoleArchetype::ProductManager),
            ("Researcher", RoleArchetype::Researcher),
            ("Architect", RoleArchetype::Architect),
            ("EPA Representative", RoleArchetype::EpaRepresentative),
            ("UX Designer", RoleArchetype::UxDesigner),
            ("Implementation Engineer", RoleArchetype::ImplementationEngineer),
            ("Crash Test Dummy", RoleArchetype::CrashTestDummy),
            ("QA Engineer", RoleArchetype::QaEngineer),
            ("Adversarial Reviewer", RoleArchetype::AdversarialReviewer),
            ("Code Reviewer", RoleArchetype::CodeReviewer),
            ("Recovery Engineer", RoleArchetype::RecoveryEngineer),
            ("Release Engineer", RoleArchetype::ReleaseEngineer),
            ("Antagonist", RoleArchetype::Antagonist),
            ("Hall Monitor", RoleArchetype::HallMonitor),
        ]
    }

    #[test]
    fn every_built_in_role_maps_to_its_own_visual_archetype() {
        for (label, expected) in built_in_roles() {
            assert_eq!(role_from_label(label), expected, "{label}");
        }
        assert_eq!(role_from_label("Task Planner"), RoleArchetype::ProductManager);
        assert_eq!(role_from_label("Swarm Coordinator"), RoleArchetype::Orchestrator);
        assert_eq!(role_from_label("Research Analyst"), RoleArchetype::Researcher);
    }

    #[test]
    fn every_creature_has_three_to_ten_antennae() {
        for (index, (label, role)) in built_in_roles().into_iter().enumerate() {
            let genome = generate_genome(role, &format!("role-{index}"));
            assert!(
                (3..=10).contains(&genome.antennae.len()),
                "{label} produced {} antennae",
                genome.antennae.len()
            );
        }
    }

    #[test]
    fn orchestrator_antennae_wrap_the_full_silhouette() {
        let genome = generate_genome(RoleArchetype::Orchestrator, "orchestrator-radial");
        let directions: Vec<(f32, f32)> = genome
            .antennae
            .iter()
            .map(|antenna| {
                let cos_elevation = antenna.elevation.cos();
                (
                    antenna.azimuth.cos() * cos_elevation,
                    antenna.elevation.sin(),
                )
            })
            .collect();
        assert!(directions.iter().any(|(x, _)| *x > 0.55));
        assert!(directions.iter().any(|(x, _)| *x < -0.55));
        assert!(directions.iter().any(|(_, y)| *y > 0.55));
        assert!(directions.iter().any(|(_, y)| *y < -0.55));
    }

    #[test]
    fn role_families_have_structurally_different_body_plans() {
        let architect = generate_genome(RoleArchetype::Architect, "architect");
        let builder = generate_genome(RoleArchetype::ImplementationEngineer, "builder");
        let dummy = generate_genome(RoleArchetype::CrashTestDummy, "dummy");
        let antagonist = generate_genome(RoleArchetype::Antagonist, "antagonist");
        let hall_monitor = generate_genome(RoleArchetype::HallMonitor, "hall-monitor");

        assert_ne!(architect.body_sides, builder.body_sides);
        assert_eq!(dummy.leg_count, 2);
        assert_eq!(antagonist.leg_count, 2);
        assert_eq!(hall_monitor.antennae.len(), 9);
        assert!(builder
            .antennae
            .iter()
            .any(|antenna| antenna.terminal == TerminalKind::Clamp));
        assert!(dummy
            .antennae
            .iter()
            .any(|antenna| antenna.terminal == TerminalKind::Coil));
        assert!(antagonist
            .antennae
            .iter()
            .any(|antenna| antenna.terminal == TerminalKind::Fork));
        assert_ne!(architect.body_radii, hall_monitor.body_radii);
    }

    #[test]
    fn generation_is_deterministic_but_identity_seed_changes_anatomy() {
        let first = generate_genome(RoleArchetype::CodeReviewer, "reviewer-a");
        let same = generate_genome(RoleArchetype::CodeReviewer, "reviewer-a");
        let other = generate_genome(RoleArchetype::CodeReviewer, "reviewer-b");
        assert_eq!(first, same);
        assert_ne!(first.antennae, other.antennae);
    }

    #[test]
    fn every_active_role_exposes_its_semantic_work_verb() {
        for (role, expected) in [
            (RoleArchetype::Orchestrator, "ROUTING"),
            (RoleArchetype::ProductManager, "CLARIFYING"),
            (RoleArchetype::Researcher, "RESEARCHING"),
            (RoleArchetype::Architect, "STRUCTURING"),
            (RoleArchetype::EpaRepresentative, "PROVISIONING"),
            (RoleArchetype::UxDesigner, "DESIGNING"),
            (RoleArchetype::ImplementationEngineer, "BUILDING"),
            (RoleArchetype::CrashTestDummy, "STRESS-TESTING"),
            (RoleArchetype::QaEngineer, "VERIFYING"),
            (RoleArchetype::AdversarialReviewer, "CHALLENGING"),
            (RoleArchetype::CodeReviewer, "REVIEWING CODE"),
            (RoleArchetype::RecoveryEngineer, "RECOVERING"),
            (RoleArchetype::ReleaseEngineer, "RELEASING"),
            (RoleArchetype::Antagonist, "FINDING FLAWS"),
            (RoleArchetype::HallMonitor, "MONITORING"),
        ] {
            assert_eq!(activity_verb(role, Activity::Active), expected);
        }
        assert_eq!(
            activity_verb(RoleArchetype::AdversarialReviewer, Activity::Blocked),
            "BLOCKED"
        );
    }

    #[test]
    fn all_workflow_states_change_the_creature_pose() {
        for (index, (label, role)) in built_in_roles().into_iter().enumerate() {
            let genome = generate_genome(role, &format!("state-{index}"));
            let queued = animate(&genome, Activity::Queued, 0.33);
            let ready = animate(&genome, Activity::Ready, 0.33);
            let active = animate(&genome, Activity::Active, 0.33);
            let blocked = animate(&genome, Activity::Blocked, 0.33);
            let failed = animate(&genome, Activity::Failed, 0.33);
            let complete = animate(&genome, Activity::Complete, 0.33);
            let gate = animate(&genome, Activity::Gate, 0.33);

            assert_ne!(queued, ready, "{label} has no ready pose");
            assert_ne!(queued, active, "{label} has no active pose");
            assert_ne!(active, blocked, "{label} has no blocked pose");
            assert_ne!(blocked, failed, "{label} has no failed pose");
            assert_ne!(queued, complete, "{label} has no complete pose");
            assert_ne!(queued, gate, "{label} has no gate pose");
        }
    }

    #[test]
    fn blocked_adversarial_roles_physically_tangle_their_antennae() {
        for role in [
            RoleArchetype::AdversarialReviewer,
            RoleArchetype::CodeReviewer,
            RoleArchetype::Antagonist,
        ] {
            let genome = generate_genome(role, "skeptic");
            let active = animate(&genome, Activity::Active, 0.33);
            let blocked = animate(&genome, Activity::Blocked, 0.33);
            assert_ne!(active.antenna_bend, blocked.antenna_bend);
            assert!(blocked.antenna_bend.iter().any(|value| value.abs() > 0.20));
        }
    }

    #[test]
    fn render_packet_is_actual_projected_3d_geometry() {
        let frame = render_creature(
            "QA Engineer",
            "qa-01",
            Activity::Active,
            0.42,
            Camera::default(),
        );
        assert!(frame.triangles.len() > 100);
        assert!(!frame.silhouette_edges.is_empty());
        assert_eq!(frame.terminal_anchors.len(), 6);
        assert!(frame.triangles.iter().any(|triangle| triangle.shade == 0));
        assert!(frame.triangles.iter().any(|triangle| triangle.shade == 2));
        assert!(frame
            .triangles
            .iter()
            .any(|triangle| triangle.material == MaterialClass::Eye));
        assert!(frame
            .triangles
            .iter()
            .any(|triangle| triangle.material == MaterialClass::Terminal));
    }

    #[test]
    fn every_antenna_of_every_role_produces_a_real_graph_socket() {
        for (label, role) in built_in_roles() {
            let genome = generate_genome(role, label);
            let frame = render_creature(label, label, Activity::Active, 0.25, Camera::default());
            assert_eq!(
                frame.terminal_anchors.len(),
                genome.antennae.len(),
                "{label} lost a graph socket during render"
            );
        }
    }

    #[test]
    fn graph_links_choose_real_antenna_terminals() {
        let frame = render_creature(
            "Implementation Engineer",
            "builder-01",
            Activity::Active,
            0.0,
            Camera::default(),
        );
        let right = terminal_anchor_toward(&frame, Vec2::new(1.0, 0.0));
        let left = terminal_anchor_toward(&frame, Vec2::new(-1.0, 0.0));
        assert!(right.x > 0.0);
        assert!(left.x < 0.0);
    }

    #[test]
    fn binary_packet_is_versioned_and_self_describing() {
        let frame = render_creature(
            "Code Reviewer",
            "reviewer-01",
            Activity::Blocked,
            0.5,
            Camera::default(),
        );
        let packet = encode_render_frame(&frame);
        assert!(packet.len() > 20);
        assert_eq!(&packet[0..4], &PACKET_MAGIC);
        assert_eq!(u16::from_le_bytes([packet[4], packet[5]]), PACKET_VERSION);
        assert_eq!(
            u32::from_le_bytes([packet[8], packet[9], packet[10], packet[11]]) as usize,
            frame.triangles.len()
        );
    }
}
