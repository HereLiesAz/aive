use crate::math::{Vec2, Vec3};
use core::f32::consts::TAU;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RoleArchetype {
    Orchestrator,
    Builder,
    Tester,
    Inspector,
    Reviewer,
    Planner,
    Researcher,
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

pub fn role_from_label(label: &str) -> RoleArchetype {
    let normalized = label.to_ascii_lowercase();
    if normalized.contains("orchestrat") || normalized.contains("queen") || normalized.contains("swarm lead") {
        RoleArchetype::Orchestrator
    } else if normalized.contains("qa") || normalized.contains("quality") || normalized.contains("verif") || normalized.contains("inspect") {
        RoleArchetype::Inspector
    } else if normalized.contains("review") {
        RoleArchetype::Reviewer
    } else if normalized.contains("crash") || normalized.contains("dummy") || normalized.contains("test") {
        RoleArchetype::Tester
    } else if normalized.contains("implement") || normalized.contains("build") || normalized.contains("develop") || normalized.contains("coder") || normalized.contains("engineer") {
        RoleArchetype::Builder
    } else if normalized.contains("plan") || normalized.contains("coordinat") || normalized.contains("decompos") {
        RoleArchetype::Planner
    } else if normalized.contains("research") || normalized.contains("analyst") || normalized.contains("investigat") {
        RoleArchetype::Researcher
    } else {
        RoleArchetype::Generic
    }
}

pub fn activity_verb(role: RoleArchetype, activity: Activity) -> &'static str {
    match activity {
        Activity::Blocked => "BLOCKED",
        Activity::Failed => "FAILED",
        Activity::Complete => "DONE",
        Activity::Gate => "WAITING FOR GATE",
        Activity::Ready => "READY",
        Activity::Queued => "QUEUED",
        Activity::Active => match role {
            RoleArchetype::Orchestrator => "ROUTING",
            RoleArchetype::Builder => "BUILDING",
            RoleArchetype::Tester => "STRESS-TESTING",
            RoleArchetype::Inspector => "VERIFYING",
            RoleArchetype::Reviewer => "REVIEWING",
            RoleArchetype::Planner => "PLANNING",
            RoleArchetype::Researcher => "RESEARCHING",
            RoleArchetype::Generic => "WORKING",
        },
    }
}

pub fn generate_genome(role: RoleArchetype, seed: &str) -> CreatureGenome {
    let mut rng = StableRng::new(hash64(seed));
    let antenna_count = match role {
        RoleArchetype::Orchestrator => 10,
        RoleArchetype::Builder => 6,
        RoleArchetype::Tester => 5,
        RoleArchetype::Inspector => 7,
        RoleArchetype::Reviewer => 8,
        RoleArchetype::Planner => 7,
        RoleArchetype::Researcher => 5,
        RoleArchetype::Generic => 3 + rng.usize(8),
    };

    let body_radii = match role {
        RoleArchetype::Orchestrator => Vec3::new(1.08, 1.02, 0.82),
        RoleArchetype::Builder => Vec3::new(1.14, 0.92, 0.82),
        RoleArchetype::Tester => Vec3::new(0.96, 1.00, 0.78),
        RoleArchetype::Inspector => Vec3::new(1.00, 1.08, 0.76),
        RoleArchetype::Reviewer => Vec3::new(1.14, 0.90, 0.72),
        RoleArchetype::Planner => Vec3::new(0.96, 1.12, 0.74),
        RoleArchetype::Researcher => Vec3::new(0.92, 1.00, 0.72),
        RoleArchetype::Generic => Vec3::new(
            0.92 + rng.f32() * 0.20,
            0.92 + rng.f32() * 0.20,
            0.68 + rng.f32() * 0.16,
        ),
    };

    let body_sides = match role {
        RoleArchetype::Builder => 8,
        RoleArchetype::Reviewer => 10,
        RoleArchetype::Planner => 7,
        _ => 12,
    };

    let base_rotation = match role {
        RoleArchetype::Orchestrator => -0.18,
        RoleArchetype::Builder => 0.16,
        RoleArchetype::Tester => -0.38,
        RoleArchetype::Inspector => 0.05,
        RoleArchetype::Reviewer => -0.08,
        RoleArchetype::Planner => 0.28,
        RoleArchetype::Researcher => -0.25,
        RoleArchetype::Generic => (rng.f32() - 0.5) * 0.52,
    };

    let antennae = (0..antenna_count)
        .map(|index| {
            let jitter = rng.f32();
            let azimuth = base_rotation
                + index as f32 / antenna_count as f32 * TAU
                + (jitter - 0.5) * 0.22;
            let elevation = match role {
                RoleArchetype::Orchestrator => -0.28 + rng.range(-0.18, 0.24),
                RoleArchetype::Builder => -0.10 + rng.range(-0.24, 0.18),
                RoleArchetype::Tester => -0.20 + rng.range(-0.32, 0.30),
                RoleArchetype::Inspector => -0.24 + rng.range(-0.18, 0.24),
                RoleArchetype::Reviewer => -0.14 + rng.range(-0.28, 0.24),
                RoleArchetype::Planner => -0.22 + rng.range(-0.24, 0.26),
                RoleArchetype::Researcher => -0.18 + rng.range(-0.24, 0.26),
                RoleArchetype::Generic => rng.range(-0.38, 0.24),
            };
            let length = match role {
                RoleArchetype::Orchestrator => 1.72 + rng.f32() * 0.24,
                RoleArchetype::Builder => 1.46 + rng.f32() * 0.24,
                RoleArchetype::Tester => 1.50 + rng.f32() * 0.30,
                RoleArchetype::Inspector => 1.62 + rng.f32() * 0.30,
                RoleArchetype::Reviewer => 1.52 + rng.f32() * 0.28,
                RoleArchetype::Planner => 1.60 + rng.f32() * 0.30,
                RoleArchetype::Researcher => 1.56 + rng.f32() * 0.28,
                RoleArchetype::Generic => 1.44 + rng.f32() * 0.34,
            };
            AntennaGenome {
                azimuth,
                elevation,
                length,
                bend: rng.range(-0.24, 0.24),
                radius: match role {
                    RoleArchetype::Builder => 0.095,
                    RoleArchetype::Orchestrator => 0.085,
                    _ => 0.072,
                },
                terminal: terminal_for(role, index),
                phase: rng.f32() * TAU,
            }
        })
        .collect();

    let (eye_count, arm_count, leg_count) = match role {
        RoleArchetype::Orchestrator => (1, 2, 3),
        RoleArchetype::Builder => (1, 2, 4),
        RoleArchetype::Tester => (2, 2, 3),
        RoleArchetype::Inspector => (1, 2, 3),
        RoleArchetype::Reviewer => (1, 2, 4),
        RoleArchetype::Planner => (2, 2, 2),
        RoleArchetype::Researcher => (1, 2, 2),
        RoleArchetype::Generic => (1 + rng.usize(3), 2, 2 + rng.usize(3)),
    };

    CreatureGenome {
        seed: seed.to_owned(),
        role,
        body_radii,
        body_sides,
        depth_scale: 0.70 + rng.f32() * 0.18,
        eye_count,
        arm_count,
        leg_count,
        antennae,
    }
}

pub fn animate(genome: &CreatureGenome, activity: Activity, time_seconds: f32) -> CreaturePose {
    let wave = time_seconds * TAU;
    let active = activity == Activity::Active;
    let blocked = matches!(activity, Activity::Blocked | Activity::Failed);

    let mut body_offset = Vec3::ZERO;
    let mut body_rotation = Vec3::ZERO;
    let mut body_scale = Vec3::new(1.0, 1.0, 1.0);
    let mut eye_aim = Vec2::ZERO;

    match genome.role {
        RoleArchetype::Orchestrator if active => {
            body_rotation.y = (wave * 0.35).sin() * 0.08;
            eye_aim.x = (wave * 0.70).sin() * 0.34;
        }
        RoleArchetype::Builder if active => {
            body_rotation.z = (wave * 0.80).sin() * 0.045;
            body_scale.x = 1.0 + (wave * 1.60).sin().abs() * 0.025;
        }
        RoleArchetype::Tester if active => {
            body_offset.x = (wave * 4.8).sin() * 0.055;
            body_rotation.z = (wave * 4.0).sin() * 0.035;
        }
        RoleArchetype::Inspector if active => {
            eye_aim.x = (wave * 0.55).sin() * 0.42;
            body_rotation.y = (wave * 0.35).sin() * 0.055;
        }
        RoleArchetype::Reviewer if active => {
            eye_aim.x = (wave * 0.95).sin() * 0.28;
            body_rotation.z = (wave * 0.30).sin() * 0.025;
        }
        RoleArchetype::Planner if active => {
            body_offset.y = (wave * 0.42).sin() * 0.035;
            body_rotation.y = (wave * 0.33).sin() * 0.060;
        }
        RoleArchetype::Researcher if active => {
            eye_aim.x = (wave * 0.45).sin() * 0.36;
            eye_aim.y = (wave * 0.31).cos() * 0.12;
        }
        _ => {}
    }

    if blocked {
        body_rotation.z += (wave * 1.4).sin() * 0.025;
        body_scale.y *= 0.97;
    }

    let mut antenna_extension = Vec::with_capacity(genome.antennae.len());
    let mut antenna_bend = Vec::with_capacity(genome.antennae.len());
    for (index, antenna) in genome.antennae.iter().enumerate() {
        let local = wave + antenna.phase;
        let extension = if active {
            match genome.role {
                RoleArchetype::Orchestrator => local.sin() * 0.10,
                RoleArchetype::Builder => local.sin() * 0.055,
                RoleArchetype::Tester => (local * 1.8).sin() * 0.085,
                RoleArchetype::Inspector => local.sin() * 0.090,
                RoleArchetype::Reviewer => local.sin() * 0.070,
                RoleArchetype::Planner => local.sin() * 0.085,
                RoleArchetype::Researcher => local.sin() * 0.075,
                RoleArchetype::Generic => local.sin() * 0.055,
            }
        } else {
            local.sin() * 0.018
        };
        let tangle = if blocked && genome.role == RoleArchetype::Reviewer {
            let sign = if index % 2 == 0 { 1.0 } else { -1.0 };
            sign * (0.26 + (local * 0.6).sin() * 0.12)
        } else if blocked {
            (local * 0.7).sin() * 0.08
        } else {
            0.0
        };
        antenna_extension.push(extension);
        antenna_bend.push(tangle);
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

fn terminal_for(role: RoleArchetype, index: usize) -> TerminalKind {
    match role {
        RoleArchetype::Orchestrator => {
            if index % 3 == 0 { TerminalKind::Probe } else { TerminalKind::Node }
        }
        RoleArchetype::Builder => {
            if index % 2 == 0 { TerminalKind::Clamp } else { TerminalKind::Node }
        }
        RoleArchetype::Tester => match index % 3 {
            0 => TerminalKind::Coil,
            1 => TerminalKind::Probe,
            _ => TerminalKind::Node,
        },
        RoleArchetype::Inspector => {
            if index % 2 == 0 { TerminalKind::Probe } else { TerminalKind::Node }
        }
        RoleArchetype::Reviewer => match index % 3 {
            0 => TerminalKind::Loop,
            1 => TerminalKind::Fork,
            _ => TerminalKind::Node,
        },
        RoleArchetype::Planner => {
            if index % 2 == 0 { TerminalKind::Fork } else { TerminalKind::Node }
        }
        RoleArchetype::Researcher => {
            if index == 0 { TerminalKind::Loop } else { TerminalKind::Probe }
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
