use crate::math::{Vec2, Vec3};
use core::f32::consts::TAU;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RoleArchetype {
    Orchestrator,
    ProductManager,
    Researcher,
    Architect,
    EpaRepresentative,
    UxDesigner,
    ImplementationEngineer,
    CrashTestDummy,
    QaEngineer,
    AdversarialReviewer,
    CodeReviewer,
    RecoveryEngineer,
    ReleaseEngineer,
    Antagonist,
    HallMonitor,
    Generic,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Activity {
    Queued,
    Ready,
    Active,
    Blocked,
    Failed,
    Complete,
    Gate,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TerminalKind {
    Node,
    Clamp,
    Probe,
    Coil,
    Fork,
    Loop,
}

#[derive(Clone, Debug, PartialEq)]
pub struct AntennaGenome {
    pub azimuth: f32,
    pub elevation: f32,
    pub length: f32,
    pub bend: f32,
    pub radius: f32,
    pub terminal: TerminalKind,
    pub phase: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct CreatureGenome {
    pub seed: String,
    pub role: RoleArchetype,
    pub body_radii: Vec3,
    pub body_sides: usize,
    pub depth_scale: f32,
    pub eye_count: usize,
    pub arm_count: usize,
    pub leg_count: usize,
    pub antennae: Vec<AntennaGenome>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct CreaturePose {
    pub body_offset: Vec3,
    pub body_rotation: Vec3,
    pub body_scale: Vec3,
    pub eye_aim: Vec2,
    pub antenna_extension: Vec<f32>,
    pub antenna_bend: Vec<f32>,
    pub limb_phase: f32,
}

#[derive(Clone, Copy)]
struct RoleSpec {
    antenna_count: usize,
    body_radii: Vec3,
    body_sides: usize,
    profile_rotation: f32,
    antenna_length: f32,
    antenna_variation: f32,
    antenna_radius: f32,
    eye_count: usize,
    arm_count: usize,
    leg_count: usize,
}

pub fn role_from_label(label: &str) -> RoleArchetype {
    let role = label.trim().to_ascii_lowercase();
    if role.contains("hall monitor") || role.contains("hall-monitor") || role.contains("systems monitor") {
        RoleArchetype::HallMonitor
    } else if role.contains("antagonist") {
        RoleArchetype::Antagonist
    } else if role.contains("adversarial") {
        RoleArchetype::AdversarialReviewer
    } else if role.contains("recovery") || role.contains("repair engineer") {
        RoleArchetype::RecoveryEngineer
    } else if role.contains("release") || role.contains("publisher") {
        RoleArchetype::ReleaseEngineer
    } else if role.contains("code review") || role.contains("code-review") {
        RoleArchetype::CodeReviewer
    } else if role.contains("crash") || role.contains("dummy") || role.contains("stress test") {
        RoleArchetype::CrashTestDummy
    } else if role == "qa"
        || role.contains("qa engineer")
        || role.contains("quality")
        || role.contains("verification engineer")
        || role.contains("inspector")
    {
        RoleArchetype::QaEngineer
    } else if role.contains("implementation")
        || role.contains("implementer")
        || role.contains("builder")
        || role.contains("developer")
        || role.contains("coder")
    {
        RoleArchetype::ImplementationEngineer
    } else if role.contains("ux") || role.contains("user experience") || role.contains("experience designer") {
        RoleArchetype::UxDesigner
    } else if role.contains("epa")
        || role.contains("environment representative")
        || role.contains("environment planner")
    {
        RoleArchetype::EpaRepresentative
    } else if role.contains("architect") || role.contains("systems design") {
        RoleArchetype::Architect
    } else if role.contains("research") || role.contains("analyst") || role.contains("investigat") {
        RoleArchetype::Researcher
    } else if role.contains("product manager")
        || role.contains("requirements")
        || role.contains("task planner")
        || role == "planner"
    {
        RoleArchetype::ProductManager
    } else if role.contains("orchestrat")
        || role.contains("coordinat")
        || role.contains("queen")
        || role.contains("swarm lead")
    {
        RoleArchetype::Orchestrator
    } else {
        RoleArchetype::Generic
    }
}

pub fn activity_verb(role: RoleArchetype, activity: Activity) -> &'static str {
    match activity {
        Activity::Blocked => "BLOCKED",
        Activity::Failed => "FAILED",
        Activity::Complete => "COMPLETE",
        Activity::Gate => "AWAITING GATE",
        Activity::Ready => "READY",
        Activity::Queued => "QUEUED",
        Activity::Active => match role {
            RoleArchetype::Orchestrator => "ROUTING",
            RoleArchetype::ProductManager => "CLARIFYING",
            RoleArchetype::Researcher => "RESEARCHING",
            RoleArchetype::Architect => "STRUCTURING",
            RoleArchetype::EpaRepresentative => "PROVISIONING",
            RoleArchetype::UxDesigner => "DESIGNING",
            RoleArchetype::ImplementationEngineer => "BUILDING",
            RoleArchetype::CrashTestDummy => "STRESS-TESTING",
            RoleArchetype::QaEngineer => "VERIFYING",
            RoleArchetype::AdversarialReviewer => "CHALLENGING",
            RoleArchetype::CodeReviewer => "REVIEWING CODE",
            RoleArchetype::RecoveryEngineer => "RECOVERING",
            RoleArchetype::ReleaseEngineer => "RELEASING",
            RoleArchetype::Antagonist => "FINDING FLAWS",
            RoleArchetype::HallMonitor => "MONITORING",
            RoleArchetype::Generic => "WORKING",
        },
    }
}

pub fn generate_genome(role: RoleArchetype, seed: &str) -> CreatureGenome {
    let mut rng = StableRng::new(hash64(seed));
    let spec = role_spec(role, &mut rng);

    let antennae = (0..spec.antenna_count)
        .map(|index| {
            let profile_angle = spec.profile_rotation
                + index as f32 / spec.antenna_count as f32 * TAU
                + rng.range(-0.075, 0.075);
            let depth = rng.range(-0.26, 0.30);
            let direction = Vec3::new(profile_angle.cos(), profile_angle.sin(), depth).normalized();
            AntennaGenome {
                azimuth: direction.z.atan2(direction.x),
                elevation: direction.y.asin(),
                length: spec.antenna_length + rng.f32() * spec.antenna_variation,
                bend: rng.range(-0.20, 0.20),
                radius: spec.antenna_radius,
                terminal: terminal_for(role, index),
                phase: rng.f32() * TAU,
            }
        })
        .collect();

    CreatureGenome {
        seed: seed.to_owned(),
        role,
        body_radii: spec.body_radii,
        body_sides: spec.body_sides,
        depth_scale: 0.62 + rng.f32() * 0.08,
        eye_count: spec.eye_count,
        arm_count: spec.arm_count,
        leg_count: spec.leg_count,
        antennae,
    }
}

fn role_spec(role: RoleArchetype, rng: &mut StableRng) -> RoleSpec {
    match role {
        RoleArchetype::Orchestrator => RoleSpec {
            antenna_count: 8,
            body_radii: Vec3::new(1.04, 0.98, 0.70),
            body_sides: 20,
            profile_rotation: -0.10,
            antenna_length: 0.73,
            antenna_variation: 0.24,
            antenna_radius: 0.052,
            eye_count: 2,
            arm_count: 0,
            leg_count: 0,
        },
        RoleArchetype::ProductManager => RoleSpec {
            antenna_count: 6,
            body_radii: Vec3::new(0.98, 1.04, 0.66),
            body_sides: 20,
            profile_rotation: 0.16,
            antenna_length: 0.66,
            antenna_variation: 0.20,
            antenna_radius: 0.047,
            eye_count: 2,
            arm_count: 2,
            leg_count: 0,
        },
        RoleArchetype::Researcher => RoleSpec {
            antenna_count: 5,
            body_radii: Vec3::new(0.94, 0.99, 0.64),
            body_sides: 20,
            profile_rotation: -0.14,
            antenna_length: 0.73,
            antenna_variation: 0.24,
            antenna_radius: 0.046,
            eye_count: 1,
            arm_count: 0,
            leg_count: 0,
        },
        RoleArchetype::Architect => RoleSpec {
            antenna_count: 7,
            body_radii: Vec3::new(1.02, 0.93, 0.68),
            body_sides: 12,
            profile_rotation: 0.04,
            antenna_length: 0.68,
            antenna_variation: 0.18,
            antenna_radius: 0.050,
            eye_count: 1,
            arm_count: 2,
            leg_count: 0,
        },
        RoleArchetype::EpaRepresentative => RoleSpec {
            antenna_count: 6,
            body_radii: Vec3::new(1.00, 0.96, 0.68),
            body_sides: 18,
            profile_rotation: -0.02,
            antenna_length: 0.67,
            antenna_variation: 0.20,
            antenna_radius: 0.052,
            eye_count: 2,
            arm_count: 2,
            leg_count: 0,
        },
        RoleArchetype::UxDesigner => RoleSpec {
            antenna_count: 6,
            body_radii: Vec3::new(0.96, 1.02, 0.64),
            body_sides: 22,
            profile_rotation: 0.12,
            antenna_length: 0.72,
            antenna_variation: 0.22,
            antenna_radius: 0.044,
            eye_count: 2,
            arm_count: 2,
            leg_count: 0,
        },
        RoleArchetype::ImplementationEngineer => RoleSpec {
            antenna_count: 6,
            body_radii: Vec3::new(1.06, 0.92, 0.72),
            body_sides: 14,
            profile_rotation: 0.10,
            antenna_length: 0.66,
            antenna_variation: 0.20,
            antenna_radius: 0.057,
            eye_count: 1,
            arm_count: 2,
            leg_count: 0,
        },
        RoleArchetype::CrashTestDummy => RoleSpec {
            antenna_count: 6,
            body_radii: Vec3::new(0.98, 0.98, 0.68),
            body_sides: 18,
            profile_rotation: -0.20,
            antenna_length: 0.69,
            antenna_variation: 0.28,
            antenna_radius: 0.050,
            eye_count: 2,
            arm_count: 0,
            leg_count: 2,
        },
        RoleArchetype::QaEngineer => RoleSpec {
            antenna_count: 6,
            body_radii: Vec3::new(0.98, 0.94, 0.66),
            body_sides: 18,
            profile_rotation: 0.04,
            antenna_length: 0.70,
            antenna_variation: 0.24,
            antenna_radius: 0.047,
            eye_count: 1,
            arm_count: 2,
            leg_count: 0,
        },
        RoleArchetype::AdversarialReviewer => RoleSpec {
            antenna_count: 7,
            body_radii: Vec3::new(1.01, 0.90, 0.67),
            body_sides: 11,
            profile_rotation: -0.06,
            antenna_length: 0.70,
            antenna_variation: 0.26,
            antenna_radius: 0.046,
            eye_count: 1,
            arm_count: 2,
            leg_count: 0,
        },
        RoleArchetype::CodeReviewer => RoleSpec {
            antenna_count: 6,
            body_radii: Vec3::new(0.99, 0.93, 0.66),
            body_sides: 16,
            profile_rotation: -0.01,
            antenna_length: 0.66,
            antenna_variation: 0.21,
            antenna_radius: 0.046,
            eye_count: 2,
            arm_count: 2,
            leg_count: 0,
        },
        RoleArchetype::RecoveryEngineer => RoleSpec {
            antenna_count: 6,
            body_radii: Vec3::new(1.03, 0.96, 0.70),
            body_sides: 18,
            profile_rotation: 0.08,
            antenna_length: 0.69,
            antenna_variation: 0.22,
            antenna_radius: 0.053,
            eye_count: 2,
            arm_count: 2,
            leg_count: 0,
        },
        RoleArchetype::ReleaseEngineer => RoleSpec {
            antenna_count: 7,
            body_radii: Vec3::new(1.00, 0.97, 0.68),
            body_sides: 20,
            profile_rotation: 0.02,
            antenna_length: 0.70,
            antenna_variation: 0.20,
            antenna_radius: 0.049,
            eye_count: 2,
            arm_count: 2,
            leg_count: 0,
        },
        RoleArchetype::Antagonist => RoleSpec {
            antenna_count: 7,
            body_radii: Vec3::new(0.97, 0.91, 0.67),
            body_sides: 9,
            profile_rotation: -0.12,
            antenna_length: 0.74,
            antenna_variation: 0.28,
            antenna_radius: 0.047,
            eye_count: 2,
            arm_count: 2,
            leg_count: 2,
        },
        RoleArchetype::HallMonitor => RoleSpec {
            antenna_count: 9,
            body_radii: Vec3::new(0.98, 1.00, 0.66),
            body_sides: 20,
            profile_rotation: 0.00,
            antenna_length: 0.72,
            antenna_variation: 0.24,
            antenna_radius: 0.044,
            eye_count: 2,
            arm_count: 2,
            leg_count: 0,
        },
        RoleArchetype::Generic => RoleSpec {
            antenna_count: 4 + rng.usize(4),
            body_radii: Vec3::new(
                0.94 + rng.f32() * 0.10,
                0.92 + rng.f32() * 0.12,
                0.62 + rng.f32() * 0.10,
            ),
            body_sides: 18,
            profile_rotation: rng.range(-0.20, 0.20),
            antenna_length: 0.64,
            antenna_variation: 0.26,
            antenna_radius: 0.048,
            eye_count: 1 + rng.usize(2),
            arm_count: rng.usize(3),
            leg_count: rng.usize(2),
        },
    }
}

const TERRARIUM_LOOP_SECONDS: f32 = 12.0;

fn looped_phase(phase: f32, frequency: f32) -> f32 {
    let whole_cycles = (frequency * TERRARIUM_LOOP_SECONDS).round().max(1.0);
    phase * whole_cycles / TERRARIUM_LOOP_SECONDS
}

fn looped_sin(phase: f32, frequency: f32) -> f32 {
    looped_phase(phase, frequency).sin()
}

fn looped_cos(phase: f32, frequency: f32) -> f32 {
    looped_phase(phase, frequency).cos()
}

pub fn animate(genome: &CreatureGenome, activity: Activity, time_seconds: f32) -> CreaturePose {
    let wave = time_seconds * TAU;
    let active = activity == Activity::Active;

    let mut body_offset = Vec3::ZERO;
    let mut body_rotation = Vec3::ZERO;
    let mut body_scale = Vec3::new(1.0, 1.0, 1.0);
    let mut eye_aim = Vec2::ZERO;

    if active {
        apply_active_personality(
            genome.role,
            wave,
            &mut body_offset,
            &mut body_rotation,
            &mut body_scale,
            &mut eye_aim,
        );
    }

    match activity {
        Activity::Queued => {
            body_offset.y = looped_sin(wave, 0.18) * 0.018;
            eye_aim.x += looped_sin(wave, 0.20) * 0.10;
        }
        Activity::Ready => {
            body_offset.y -= 0.025 + looped_sin(wave, 0.30) * 0.012;
            body_scale.x *= 1.018;
            body_scale.y *= 1.018;
            eye_aim.y -= 0.08;
        }
        Activity::Active => {}
        Activity::Blocked => {
            body_rotation.z += looped_sin(wave, 1.25) * 0.035;
            body_scale.y *= 0.95;
            body_offset.y += 0.030;
            eye_aim.y += 0.12;
        }
        Activity::Failed => {
            body_rotation.z += looped_sin(wave, 2.8) * 0.055;
            body_scale.x *= 0.94;
            body_scale.y *= 0.90;
            body_offset.y += 0.055;
            eye_aim.y += 0.20;
        }
        Activity::Complete => {
            body_offset.y -= looped_sin(wave, 0.55).abs() * 0.045;
            body_rotation.z += looped_sin(wave, 0.42) * 0.055;
            body_scale.x *= 1.0 + looped_sin(wave, 0.55).abs() * 0.025;
            body_scale.y *= 1.0 + looped_sin(wave, 0.55).abs() * 0.025;
            eye_aim.y -= 0.06;
        }
        Activity::Gate => {
            body_rotation.y += looped_sin(wave, 0.16) * 0.035;
            eye_aim.x += looped_sin(wave, 0.27) * 0.22;
            body_offset.y += looped_sin(wave, 0.20) * 0.012;
        }
    }

    apply_state_personality(
        genome.role,
        activity,
        wave,
        &mut body_offset,
        &mut body_rotation,
        &mut body_scale,
        &mut eye_aim,
    );

    let mut antenna_extension = Vec::with_capacity(genome.antennae.len());
    let mut antenna_bend = Vec::with_capacity(genome.antennae.len());
    for (index, antenna) in genome.antennae.iter().enumerate() {
        let local = wave + antenna.phase;
        let extension = match activity {
            Activity::Active => active_antenna_amplitude(genome.role, index, local),
            Activity::Ready => local.sin() * 0.040,
            Activity::Complete => local.sin() * 0.055,
            Activity::Failed => looped_sin(local, 1.7) * 0.025,
            _ => local.sin() * 0.018,
        };
        let bend = match activity {
            Activity::Blocked if matches!(
                genome.role,
                RoleArchetype::AdversarialReviewer
                    | RoleArchetype::CodeReviewer
                    | RoleArchetype::Antagonist
            ) => {
                let sign = if index % 2 == 0 { 1.0 } else { -1.0 };
                sign * (0.25 + looped_sin(local, 0.65) * 0.12)
            }
            Activity::Blocked => looped_sin(local, 0.70) * 0.09,
            Activity::Failed => {
                let sign = if index % 2 == 0 { 1.0 } else { -1.0 };
                sign * (0.15 + looped_sin(local, 1.20) * 0.10)
            }
            Activity::Gate => looped_sin(local, 0.32) * 0.035,
            _ => 0.0,
        };
        antenna_extension.push(extension);
        antenna_bend.push(bend);
    }

    CreaturePose {
        body_offset,
        body_rotation,
        body_scale,
        eye_aim,
        antenna_extension,
        antenna_bend,
        limb_phase: wave,
    }
}

fn apply_state_personality(
    role: RoleArchetype,
    activity: Activity,
    wave: f32,
    body_offset: &mut Vec3,
    body_rotation: &mut Vec3,
    body_scale: &mut Vec3,
    eye_aim: &mut Vec2,
) {
    match (role, activity) {
        (RoleArchetype::Orchestrator, Activity::Gate) => {
            eye_aim.x += looped_sin(wave, 0.65) * 0.18;
        }
        (RoleArchetype::Orchestrator, Activity::Complete) => {
            body_rotation.y += looped_sin(wave, 0.70) * 0.07;
        }
        (RoleArchetype::ProductManager, Activity::Blocked) => {
            body_rotation.z -= 0.055;
            eye_aim.x += looped_sin(wave, 0.82) * 0.24;
        }
        (RoleArchetype::ProductManager, Activity::Complete) => {
            body_rotation.z += looped_sin(wave, 0.55) * 0.045;
        }
        (RoleArchetype::Researcher, Activity::Blocked) => {
            eye_aim.x += looped_sin(wave, 1.10) * 0.34;
            eye_aim.y += looped_cos(wave, 0.73) * 0.12;
        }
        (RoleArchetype::Researcher, Activity::Complete) => {
            body_offset.y -= looped_sin(wave, 0.85).abs() * 0.025;
            body_scale.x *= 1.025;
        }
        (RoleArchetype::Architect, Activity::Blocked) => {
            body_rotation.z += 0.065;
            body_scale.x *= 0.97;
        }
        (RoleArchetype::Architect, Activity::Complete) => {
            body_rotation.z *= 0.30;
            body_scale.x *= 1.025;
            body_scale.y *= 1.025;
        }
        (RoleArchetype::EpaRepresentative, Activity::Gate) => {
            body_rotation.y += looped_sin(wave, 0.72) * 0.06;
            eye_aim.x += looped_sin(wave, 0.58) * 0.20;
        }
        (RoleArchetype::EpaRepresentative, Activity::Complete) => {
            body_rotation.z += looped_sin(wave, 0.48) * 0.04;
        }
        (RoleArchetype::UxDesigner, Activity::Blocked) => {
            body_rotation.z -= 0.045;
            body_scale.y *= 0.97;
        }
        (RoleArchetype::UxDesigner, Activity::Complete) => {
            body_rotation.z += looped_sin(wave, 0.70) * 0.075;
            body_offset.y -= looped_sin(wave, 0.70).abs() * 0.025;
        }
        (RoleArchetype::ImplementationEngineer, Activity::Blocked) => {
            body_rotation.z += looped_sin(wave, 2.20) * 0.035;
        }
        (RoleArchetype::ImplementationEngineer, Activity::Complete) => {
            body_scale.x *= 1.0 + looped_sin(wave, 1.15).abs() * 0.035;
        }
        (RoleArchetype::CrashTestDummy, Activity::Failed) => {
            body_scale.y *= 0.78;
            body_scale.x *= 1.10;
            body_rotation.z += looped_sin(wave, 3.20) * 0.08;
        }
        (RoleArchetype::CrashTestDummy, Activity::Complete) => {
            body_offset.y -= looped_sin(wave, 1.60).abs() * 0.065;
            body_rotation.z += looped_sin(wave, 1.15) * 0.09;
        }
        (RoleArchetype::QaEngineer, Activity::Gate) => {
            eye_aim.x += looped_sin(wave, 1.05) * 0.30;
        }
        (RoleArchetype::QaEngineer, Activity::Complete) => {
            body_rotation.y += looped_sin(wave, 0.48) * 0.035;
            body_scale.x *= 1.02;
        }
        (RoleArchetype::AdversarialReviewer, Activity::Blocked) => {
            body_rotation.z -= 0.075;
            eye_aim.x += looped_sin(wave, 1.20) * 0.24;
        }
        (RoleArchetype::AdversarialReviewer, Activity::Complete) => {
            body_rotation.z += 0.025 + looped_sin(wave, 0.30) * 0.018;
        }
        (RoleArchetype::CodeReviewer, Activity::Gate) => {
            eye_aim.x += looped_sin(wave, 0.82) * 0.24;
        }
        (RoleArchetype::CodeReviewer, Activity::Complete) => {
            body_rotation.z += looped_sin(wave, 0.38) * 0.025;
        }
        (RoleArchetype::RecoveryEngineer, Activity::Failed) => {
            body_rotation.z += looped_sin(wave, 1.75) * 0.055;
            eye_aim.x += looped_sin(wave, 1.20) * 0.25;
        }
        (RoleArchetype::RecoveryEngineer, Activity::Complete) => {
            body_scale.x *= 1.035;
            body_scale.y *= 1.035;
        }
        (RoleArchetype::ReleaseEngineer, Activity::Gate) => {
            body_offset.y += looped_sin(wave, 0.16) * 0.008;
            eye_aim.x += looped_sin(wave, 0.36) * 0.16;
        }
        (RoleArchetype::ReleaseEngineer, Activity::Complete) => {
            body_offset.y -= looped_sin(wave, 0.66).abs() * 0.055;
            body_rotation.y += looped_sin(wave, 0.50) * 0.06;
        }
        (RoleArchetype::Antagonist, Activity::Blocked) => {
            body_rotation.z += looped_sin(wave, 2.40) * 0.09;
            body_offset.x += looped_sin(wave, 2.10) * 0.045;
        }
        (RoleArchetype::Antagonist, Activity::Complete) => {
            body_rotation.z += 0.055 + looped_sin(wave, 0.62) * 0.04;
            body_offset.y -= looped_sin(wave, 0.72).abs() * 0.028;
        }
        (RoleArchetype::HallMonitor, Activity::Blocked) => {
            eye_aim.x += looped_sin(wave, 1.25) * 0.38;
            eye_aim.y += looped_cos(wave, 0.84) * 0.13;
        }
        (RoleArchetype::HallMonitor, Activity::Gate) => {
            eye_aim.x += looped_sin(wave, 0.74) * 0.32;
        }
        (RoleArchetype::HallMonitor, Activity::Complete) => {
            body_rotation.y += looped_sin(wave, 0.28) * 0.025;
            body_scale.x *= 1.02;
        }
        _ => {}
    }
}

fn apply_active_personality(
    role: RoleArchetype,
    wave: f32,
    body_offset: &mut Vec3,
    body_rotation: &mut Vec3,
    body_scale: &mut Vec3,
    eye_aim: &mut Vec2,
) {
    match role {
        RoleArchetype::Orchestrator => {
            body_rotation.y = looped_sin(wave, 0.35) * 0.08;
            eye_aim.x = looped_sin(wave, 0.70) * 0.34;
        }
        RoleArchetype::ProductManager => {
            body_rotation.y = looped_sin(wave, 0.30) * 0.055;
            eye_aim.x = looped_sin(wave, 0.52) * 0.30;
            eye_aim.y = looped_cos(wave, 0.22) * 0.08;
        }
        RoleArchetype::Researcher => {
            eye_aim.x = looped_sin(wave, 0.45) * 0.38;
            eye_aim.y = looped_cos(wave, 0.31) * 0.14;
            body_rotation.y = looped_sin(wave, 0.22) * 0.04;
        }
        RoleArchetype::Architect => {
            body_rotation.z = looped_sin(wave, 0.24) * 0.025;
            body_rotation.y = looped_sin(wave, 0.34) * 0.045;
            body_offset.y = looped_sin(wave, 0.32) * 0.018;
        }
        RoleArchetype::EpaRepresentative => {
            body_rotation.y = looped_sin(wave, 0.42) * 0.065;
            eye_aim.x = looped_sin(wave, 0.36) * 0.26;
        }
        RoleArchetype::UxDesigner => {
            body_rotation.z = looped_sin(wave, 0.38) * 0.045;
            body_offset.y = looped_sin(wave, 0.30) * 0.030;
            body_scale.x = 1.0 + looped_sin(wave, 0.50) * 0.018;
        }
        RoleArchetype::ImplementationEngineer => {
            body_rotation.z = looped_sin(wave, 0.80) * 0.045;
            body_scale.x = 1.0 + looped_sin(wave, 1.60).abs() * 0.025;
        }
        RoleArchetype::CrashTestDummy => {
            body_offset.x = looped_sin(wave, 4.8) * 0.060;
            body_rotation.z = looped_sin(wave, 4.0) * 0.040;
        }
        RoleArchetype::QaEngineer => {
            eye_aim.x = looped_sin(wave, 0.55) * 0.42;
            body_rotation.y = looped_sin(wave, 0.35) * 0.055;
        }
        RoleArchetype::AdversarialReviewer => {
            eye_aim.x = looped_sin(wave, 0.95) * 0.32;
            body_rotation.z = looped_sin(wave, 0.45) * 0.035;
            body_offset.x = looped_sin(wave, 0.30) * 0.018;
        }
        RoleArchetype::CodeReviewer => {
            eye_aim.x = looped_sin(wave, 0.72) * 0.28;
            eye_aim.y = looped_cos(wave, 0.23) * 0.06;
            body_rotation.y = looped_sin(wave, 0.28) * 0.035;
        }
        RoleArchetype::RecoveryEngineer => {
            body_rotation.z = looped_sin(wave, 0.70) * 0.035;
            body_scale.x = 1.0 + looped_sin(wave, 1.15).abs() * 0.020;
            body_offset.y = looped_sin(wave, 0.48) * 0.018;
        }
        RoleArchetype::ReleaseEngineer => {
            body_rotation.y = looped_sin(wave, 0.28) * 0.045;
            body_offset.y = -looped_sin(wave, 0.44).abs() * 0.025;
            eye_aim.x = looped_sin(wave, 0.22) * 0.18;
        }
        RoleArchetype::Antagonist => {
            body_rotation.z = looped_sin(wave, 1.10) * 0.055;
            body_offset.x = looped_sin(wave, 0.88) * 0.035;
            eye_aim.x = looped_sin(wave, 1.30) * 0.40;
        }
        RoleArchetype::HallMonitor => {
            eye_aim.x = looped_sin(wave, 0.32) * 0.44;
            eye_aim.y = looped_cos(wave, 0.24) * 0.12;
            body_rotation.y = looped_sin(wave, 0.18) * 0.035;
        }
        RoleArchetype::Generic => {
            body_offset.y = looped_sin(wave, 0.35) * 0.020;
        }
    }
}

fn active_antenna_amplitude(role: RoleArchetype, index: usize, local: f32) -> f32 {
    let alternating = if index % 2 == 0 { 1.0 } else { -1.0 };
    match role {
        RoleArchetype::Orchestrator => local.sin() * 0.100,
        RoleArchetype::ProductManager => looped_sin(local, 0.75) * 0.070,
        RoleArchetype::Researcher => local.sin() * 0.090,
        RoleArchetype::Architect => looped_sin(local, 0.55) * 0.052,
        RoleArchetype::EpaRepresentative => looped_sin(local, 0.80) * 0.065,
        RoleArchetype::UxDesigner => looped_sin(local, 0.62) * 0.082,
        RoleArchetype::ImplementationEngineer => local.sin() * 0.055,
        RoleArchetype::CrashTestDummy => looped_sin(local, 1.80) * 0.090,
        RoleArchetype::QaEngineer => local.sin() * 0.090,
        RoleArchetype::AdversarialReviewer => local.sin() * 0.080 * alternating,
        RoleArchetype::CodeReviewer => local.sin() * 0.065,
        RoleArchetype::RecoveryEngineer => looped_sin(local, 1.15) * 0.070,
        RoleArchetype::ReleaseEngineer => looped_sin(local, 0.72) * 0.078,
        RoleArchetype::Antagonist => looped_sin(local, 1.35) * 0.095 * alternating,
        RoleArchetype::HallMonitor => looped_sin(local, 0.45) * 0.085,
        RoleArchetype::Generic => local.sin() * 0.055,
    }
}

fn terminal_for(role: RoleArchetype, index: usize) -> TerminalKind {
    match role {
        RoleArchetype::Orchestrator => {
            if index % 4 == 0 { TerminalKind::Probe } else { TerminalKind::Node }
        }
        RoleArchetype::ProductManager => {
            if index % 3 == 0 { TerminalKind::Fork } else { TerminalKind::Node }
        }
        RoleArchetype::Researcher => {
            if index == 0 { TerminalKind::Loop } else { TerminalKind::Probe }
        }
        RoleArchetype::Architect => match index % 3 {
            0 => TerminalKind::Fork,
            1 => TerminalKind::Clamp,
            _ => TerminalKind::Node,
        },
        RoleArchetype::EpaRepresentative => {
            if index % 2 == 0 { TerminalKind::Clamp } else { TerminalKind::Node }
        }
        RoleArchetype::UxDesigner => {
            if index % 2 == 0 { TerminalKind::Loop } else { TerminalKind::Node }
        }
        RoleArchetype::ImplementationEngineer => {
            if index % 2 == 0 { TerminalKind::Clamp } else { TerminalKind::Node }
        }
        RoleArchetype::CrashTestDummy => match index % 3 {
            0 => TerminalKind::Coil,
            1 => TerminalKind::Probe,
            _ => TerminalKind::Node,
        },
        RoleArchetype::QaEngineer => {
            if index % 2 == 0 { TerminalKind::Probe } else { TerminalKind::Node }
        }
        RoleArchetype::AdversarialReviewer => match index % 4 {
            0 => TerminalKind::Fork,
            1 => TerminalKind::Probe,
            2 => TerminalKind::Fork,
            _ => TerminalKind::Node,
        },
        RoleArchetype::CodeReviewer => match index % 4 {
            0 => TerminalKind::Loop,
            1 => TerminalKind::Probe,
            2 => TerminalKind::Fork,
            _ => TerminalKind::Node,
        },
        RoleArchetype::RecoveryEngineer => match index % 3 {
            0 => TerminalKind::Clamp,
            1 => TerminalKind::Loop,
            _ => TerminalKind::Node,
        },
        RoleArchetype::ReleaseEngineer => match index % 3 {
            0 => TerminalKind::Fork,
            1 => TerminalKind::Clamp,
            _ => TerminalKind::Node,
        },
        RoleArchetype::Antagonist => match index % 4 {
            0 => TerminalKind::Fork,
            1 => TerminalKind::Probe,
            2 => TerminalKind::Coil,
            _ => TerminalKind::Node,
        },
        RoleArchetype::HallMonitor => {
            if index % 3 == 0 { TerminalKind::Node } else { TerminalKind::Probe }
        }
        RoleArchetype::Generic => match index % 6 {
            0 => TerminalKind::Node,
            1 => TerminalKind::Clamp,
            2 => TerminalKind::Probe,
            3 => TerminalKind::Coil,
            4 => TerminalKind::Fork,
            _ => TerminalKind::Loop,
        },
    }
}

fn hash64(value: &str) -> u64 {
    let mut hash = 0xcbf29ce484222325u64;
    for byte in value.as_bytes() {
        hash ^= *byte as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

struct StableRng {
    state: u64,
}

impl StableRng {
    fn new(seed: u64) -> Self {
        Self { state: seed.max(1) }
    }

    fn next_u32(&mut self) -> u32 {
        let mut x = self.state;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.state = x;
        (x >> 16) as u32
    }

    fn f32(&mut self) -> f32 {
        self.next_u32() as f32 / u32::MAX as f32
    }

    fn range(&mut self, low: f32, high: f32) -> f32 {
        low + (high - low) * self.f32()
    }

    fn usize(&mut self, exclusive_max: usize) -> usize {
        if exclusive_max == 0 {
            0
        } else {
            self.next_u32() as usize % exclusive_max
        }
    }
}
