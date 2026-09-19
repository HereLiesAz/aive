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

    #[test]
    fn role_mapping_matches_visual_grammar() {
        assert_eq!(role_from_label("Orchestrator"), RoleArchetype::Orchestrator);
        assert_eq!(
            role_from_label("Implementation Engineer"),
            RoleArchetype::Builder
        );
        assert_eq!(role_from_label("Crash Test Dummy"), RoleArchetype::Tester);
        assert_eq!(role_from_label("QA Engineer"), RoleArchetype::Inspector);
        assert_eq!(role_from_label("Code Reviewer"), RoleArchetype::Reviewer);
        assert_eq!(role_from_label("Task Planner"), RoleArchetype::Planner);
        assert_eq!(
            role_from_label("Research Analyst"),
            RoleArchetype::Researcher
        );
    }

    #[test]
    fn every_creature_has_three_to_ten_antennae() {
        let roles = [
            RoleArchetype::Orchestrator,
            RoleArchetype::Builder,
            RoleArchetype::Tester,
            RoleArchetype::Inspector,
            RoleArchetype::Reviewer,
            RoleArchetype::Planner,
            RoleArchetype::Researcher,
            RoleArchetype::Generic,
        ];
        for (index, role) in roles.into_iter().enumerate() {
            let genome = generate_genome(role, &format!("role-{index}"));
            assert!(
                (3..=10).contains(&genome.antennae.len()),
                "{role:?} produced {} antennae",
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
    fn semantic_roles_have_structurally_different_body_plans() {
        let orchestrator = generate_genome(RoleArchetype::Orchestrator, "orchestrator");
        let builder = generate_genome(RoleArchetype::Builder, "builder");
        let tester = generate_genome(RoleArchetype::Tester, "tester");
        let reviewer = generate_genome(RoleArchetype::Reviewer, "reviewer");

        assert_eq!(orchestrator.arm_count, 0);
        assert!(builder.arm_count >= 2);
        assert!(tester
            .antennae
            .iter()
            .any(|antenna| antenna.terminal == TerminalKind::Coil));
        assert!(reviewer
            .antennae
            .iter()
            .any(|antenna| matches!(antenna.terminal, TerminalKind::Loop | TerminalKind::Fork)));
        assert_ne!(builder.body_radii, tester.body_radii);
        assert_ne!(orchestrator.antennae, reviewer.antennae);
    }

    #[test]
    fn generation_is_deterministic_but_not_color_swap_identity() {
        let first = generate_genome(RoleArchetype::Reviewer, "reviewer-a");
        let same = generate_genome(RoleArchetype::Reviewer, "reviewer-a");
        let other = generate_genome(RoleArchetype::Reviewer, "reviewer-b");
        assert_eq!(first, same);
        assert_ne!(first.antennae, other.antennae);
    }

    #[test]
    fn active_role_exposes_semantic_work_verb() {
        assert_eq!(
            activity_verb(RoleArchetype::Orchestrator, Activity::Active),
            "ROUTING"
        );
        assert_eq!(
            activity_verb(RoleArchetype::Builder, Activity::Active),
            "BUILDING"
        );
        assert_eq!(
            activity_verb(RoleArchetype::Tester, Activity::Active),
            "STRESS-TESTING"
        );
        assert_eq!(
            activity_verb(RoleArchetype::Inspector, Activity::Active),
            "VERIFYING"
        );
        assert_eq!(
            activity_verb(RoleArchetype::Reviewer, Activity::Active),
            "REVIEWING"
        );
        assert_eq!(
            activity_verb(RoleArchetype::Reviewer, Activity::Blocked),
            "BLOCKED"
        );
    }

    #[test]
    fn blocked_reviewer_physically_changes_antenna_pose() {
        let genome = generate_genome(RoleArchetype::Reviewer, "reviewer");
        let active = animate(&genome, Activity::Active, 0.33);
        let blocked = animate(&genome, Activity::Blocked, 0.33);
        assert_ne!(active.antenna_bend, blocked.antenna_bend);
        assert!(blocked.antenna_bend.iter().any(|value| value.abs() > 0.20));
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
    fn every_antenna_produces_a_real_graph_socket() {
        for (label, role) in [
            ("Orchestrator", RoleArchetype::Orchestrator),
            ("Implementation Engineer", RoleArchetype::Builder),
            ("Crash Test Dummy", RoleArchetype::Tester),
            ("QA Engineer", RoleArchetype::Inspector),
            ("Code Reviewer", RoleArchetype::Reviewer),
        ] {
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
