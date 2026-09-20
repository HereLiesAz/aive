use crate::genome::{CreatureGenome, CreaturePose, RoleArchetype, TerminalKind};
use crate::math::{rotate_xyz, Vec2, Vec3};
use core::f32::consts::{PI, TAU};
use std::collections::HashMap;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum MaterialClass {
    Body,
    Accent,
    Eye,
    Limb,
    Terminal,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Face {
    pub indices: [usize; 3],
    pub material: MaterialClass,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Mesh {
    pub vertices: Vec<Vec3>,
    pub faces: Vec<Face>,
    pub terminal_points: Vec<Vec3>,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Camera {
    pub yaw: f32,
    pub pitch: f32,
    pub zoom: f32,
}

impl Default for Camera {
    fn default() -> Self {
        Self {
            yaw: -0.18,
            pitch: 0.10,
            zoom: 0.33,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RenderTriangle {
    pub points: [Vec2; 3],
    pub depth: f32,
    pub shade: u8,
    pub material: MaterialClass,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RenderEdge {
    pub from: Vec2,
    pub to: Vec2,
    pub depth: f32,
    pub weight: f32,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct RenderFrame {
    pub triangles: Vec<RenderTriangle>,
    pub silhouette_edges: Vec<RenderEdge>,
    pub terminal_anchors: Vec<Vec2>,
}

pub fn build_mesh(genome: &CreatureGenome, pose: &CreaturePose) -> Mesh {
    let mut mesh = Mesh::default();
    add_ellipsoid(
        &mut mesh,
        Vec3::ZERO,
        genome.body_radii,
        genome.body_sides.max(9),
        8,
        MaterialClass::Body,
    );
    add_logo_rim(&mut mesh, genome);

    add_role_surface_details(&mut mesh, genome);
    add_face(&mut mesh, genome, pose);
    add_antennae(&mut mesh, genome, pose);
    add_limbs(&mut mesh, genome, pose);

    for vertex in &mut mesh.vertices {
        *vertex = transform_model_point(*vertex, pose);
    }
    for terminal in &mut mesh.terminal_points {
        *terminal = transform_model_point(*terminal, pose);
    }

    mesh
}

pub fn project_mesh(mesh: &Mesh, camera: Camera) -> RenderFrame {
    let camera_rotation = Vec3::new(camera.pitch, camera.yaw, 0.0);
    let transformed: Vec<Vec3> = mesh
        .vertices
        .iter()
        .copied()
        .map(|value| rotate_xyz(value, camera_rotation))
        .collect();
    let projected: Vec<Vec2> = transformed
        .iter()
        .map(|value| Vec2::new(value.x * camera.zoom, -value.y * camera.zoom))
        .collect();
    let light = Vec3::new(-0.42, -0.66, 0.82).normalized();

    let mut triangles = Vec::with_capacity(mesh.faces.len());
    for face in &mesh.faces {
        let a = transformed[face.indices[0]];
        let b = transformed[face.indices[1]];
        let c = transformed[face.indices[2]];
        let normal = (b - a).cross(c - a).normalized();
        let light_score = normal.dot(light);
        let shade = if light_score > 0.45 {
            2
        } else if light_score > -0.08 {
            1
        } else {
            0
        };
        triangles.push(RenderTriangle {
            points: [
                projected[face.indices[0]],
                projected[face.indices[1]],
                projected[face.indices[2]],
            ],
            depth: (a.z + b.z + c.z) / 3.0,
            shade,
            material: face.material,
        });
    }
    triangles.sort_by(|left, right| left.depth.total_cmp(&right.depth));

    let terminal_anchors = mesh
        .terminal_points
        .iter()
        .copied()
        .map(|value| rotate_xyz(value, camera_rotation))
        .map(|value| Vec2::new(value.x * camera.zoom, -value.y * camera.zoom))
        .collect();

    let silhouette_edges = extract_silhouette_edges(mesh, &transformed, &projected);

    RenderFrame {
        triangles,
        silhouette_edges,
        terminal_anchors,
    }
}

pub fn terminal_anchor_toward(frame: &RenderFrame, toward: Vec2) -> Vec2 {
    if toward.length() <= f32::EPSILON || frame.terminal_anchors.is_empty() {
        return Vec2::ZERO;
    }
    let direction = toward.normalized();
    frame
        .terminal_anchors
        .iter()
        .copied()
        .max_by(|left, right| {
            let left_score = left.normalized().x * direction.x + left.normalized().y * direction.y;
            let right_score =
                right.normalized().x * direction.x + right.normalized().y * direction.y;
            left_score.total_cmp(&right_score)
        })
        .unwrap_or(Vec2::ZERO)
}

fn add_ellipsoid(
    mesh: &mut Mesh,
    center: Vec3,
    radii: Vec3,
    sides: usize,
    rings: usize,
    material: MaterialClass,
) {
    let top = mesh.vertices.len();
    mesh.vertices.push(center + Vec3::new(0.0, -radii.y, 0.0));

    let mut ring_indices: Vec<Vec<usize>> = Vec::new();
    for ring in 1..rings {
        let latitude = -PI / 2.0 + PI * ring as f32 / rings as f32;
        let cos_lat = latitude.cos();
        let sin_lat = latitude.sin();
        let mut indices = Vec::with_capacity(sides);
        for side in 0..sides {
            let longitude = TAU * side as f32 / sides as f32;
            let point = center
                + Vec3::new(
                    radii.x * cos_lat * longitude.cos(),
                    radii.y * sin_lat,
                    radii.z * cos_lat * longitude.sin(),
                );
            indices.push(mesh.vertices.len());
            mesh.vertices.push(point);
        }
        ring_indices.push(indices);
    }

    let bottom = mesh.vertices.len();
    mesh.vertices.push(center + Vec3::new(0.0, radii.y, 0.0));

    if let Some(first_ring) = ring_indices.first() {
        for side in 0..sides {
            let next = (side + 1) % sides;
            mesh.faces.push(Face {
                indices: [top, first_ring[next], first_ring[side]],
                material,
            });
        }
    }

    for pair in ring_indices.windows(2) {
        let upper = &pair[0];
        let lower = &pair[1];
        for side in 0..sides {
            let next = (side + 1) % sides;
            mesh.faces.push(Face {
                indices: [upper[side], upper[next], lower[side]],
                material,
            });
            mesh.faces.push(Face {
                indices: [upper[next], lower[next], lower[side]],
                material,
            });
        }
    }

    if let Some(last_ring) = ring_indices.last() {
        for side in 0..sides {
            let next = (side + 1) % sides;
            mesh.faces.push(Face {
                indices: [last_ring[side], last_ring[next], bottom],
                material,
            });
        }
    }
}

fn add_logo_rim(mesh: &mut Mesh, genome: &CreatureGenome) {
    let segments = 20;
    let z = genome.body_radii.z * 1.025;
    let rx = genome.body_radii.x * 1.015;
    let ry = genome.body_radii.y * 1.015;
    let radius = 0.034;
    let mut previous = Vec3::new(rx, 0.0, z);
    for segment in 1..=segments {
        let angle = TAU * segment as f32 / segments as f32;
        let next = Vec3::new(rx * angle.cos(), ry * angle.sin(), z);
        add_tube(mesh, previous, next, radius, 4, MaterialClass::Accent);
        previous = next;
    }
}

fn add_role_surface_details(mesh: &mut Mesh, genome: &CreatureGenome) {
    let z = genome.body_radii.z * 1.035;
    match genome.role {
        RoleArchetype::Orchestrator => {
            for (x, y, rx, ry) in [
                (-0.43, -0.52, 0.16, 0.10),
                (0.37, -0.49, 0.13, 0.09),
                (-0.58, 0.23, 0.11, 0.08),
            ] {
                add_disc(
                    mesh,
                    Vec3::new(x * genome.body_radii.x, y * genome.body_radii.y, z),
                    rx * genome.body_radii.x,
                    ry * genome.body_radii.y,
                    10,
                    MaterialClass::Accent,
                );
            }
        }
        RoleArchetype::ProductManager => {
            let center = Vec3::new(0.44 * genome.body_radii.x, -0.08 * genome.body_radii.y, z + 0.02);
            add_rect(mesh, center, 0.40, 0.52, MaterialClass::Terminal);
            for row in [-0.14_f32, 0.0, 0.14] {
                add_disc(
                    mesh,
                    center + Vec3::new(-0.12, row, 0.025),
                    0.035,
                    0.035,
                    8,
                    MaterialClass::Eye,
                );
                add_tube(
                    mesh,
                    center + Vec3::new(-0.04, row, 0.03),
                    center + Vec3::new(0.14, row, 0.03),
                    0.014,
                    4,
                    MaterialClass::Eye,
                );
            }
        }
        RoleArchetype::Researcher => {
            let lens = Vec3::new(0.48 * genome.body_radii.x, -0.32 * genome.body_radii.y, z + 0.03);
            add_disc(mesh, lens, 0.17, 0.17, 16, MaterialClass::Accent);
            add_disc(
                mesh,
                lens + Vec3::new(0.0, 0.0, 0.025),
                0.105,
                0.105,
                14,
                MaterialClass::Body,
            );
            add_tube(
                mesh,
                lens + Vec3::new(0.10, 0.10, 0.01),
                lens + Vec3::new(0.28, 0.28, 0.01),
                0.032,
                5,
                MaterialClass::Accent,
            );
        }
        RoleArchetype::Architect => {
            let origin = Vec3::new(-0.46 * genome.body_radii.x, -0.26 * genome.body_radii.y, z + 0.02);
            for (dx, dy) in [(0.0_f32, 0.0_f32), (0.28, 0.15), (0.12, 0.39)] {
                add_rect(
                    mesh,
                    origin + Vec3::new(dx, dy, 0.0),
                    0.16,
                    0.16,
                    MaterialClass::Terminal,
                );
            }
            add_tube(
                mesh,
                origin + Vec3::new(0.08, 0.08, 0.025),
                origin + Vec3::new(0.28, 0.20, 0.025),
                0.018,
                4,
                MaterialClass::Eye,
            );
            add_tube(
                mesh,
                origin + Vec3::new(0.26, 0.22, 0.025),
                origin + Vec3::new(0.17, 0.39, 0.025),
                0.018,
                4,
                MaterialClass::Eye,
            );
        }
        RoleArchetype::EpaRepresentative => {
            for (x, y) in [(-0.42_f32, -0.22_f32), (-0.42, 0.05), (-0.42, 0.32)] {
                let port = Vec3::new(x * genome.body_radii.x, y * genome.body_radii.y, z + 0.02);
                add_disc(mesh, port, 0.085, 0.085, 12, MaterialClass::Terminal);
                add_disc(
                    mesh,
                    port + Vec3::new(0.0, 0.0, 0.025),
                    0.035,
                    0.035,
                    10,
                    MaterialClass::Limb,
                );
            }
            add_tube(
                mesh,
                Vec3::new(0.28 * genome.body_radii.x, -0.37 * genome.body_radii.y, z + 0.02),
                Vec3::new(0.50 * genome.body_radii.x, -0.19 * genome.body_radii.y, z + 0.02),
                0.035,
                5,
                MaterialClass::Accent,
            );
        }
        RoleArchetype::UxDesigner => {
            let left = Vec3::new(-0.12 * genome.body_radii.x, -0.28 * genome.body_radii.y, z + 0.025);
            let right = Vec3::new(0.12 * genome.body_radii.x, -0.28 * genome.body_radii.y, z + 0.025);
            add_disc(mesh, left, 0.13, 0.13, 14, MaterialClass::Accent);
            add_disc(mesh, right, 0.13, 0.13, 14, MaterialClass::Accent);
            let tip = Vec3::new(0.0, 0.12 * genome.body_radii.y, z + 0.025);
            add_tube(mesh, left + Vec3::new(-0.05, 0.06, 0.0), tip, 0.055, 6, MaterialClass::Accent);
            add_tube(mesh, right + Vec3::new(0.05, 0.06, 0.0), tip, 0.055, 6, MaterialClass::Accent);
        }
        RoleArchetype::ImplementationEngineer => {
            for offset in [-0.32_f32, 0.0, 0.32] {
                add_tube(
                    mesh,
                    Vec3::new(-0.58 * genome.body_radii.x, offset * genome.body_radii.y, z),
                    Vec3::new(
                        -0.15 * genome.body_radii.x,
                        (offset + 0.19) * genome.body_radii.y,
                        z,
                    ),
                    0.035,
                    5,
                    MaterialClass::Limb,
                );
            }
        }
        RoleArchetype::CrashTestDummy => {
            let patch = Vec3::new(0.53 * genome.body_radii.x, 0.18 * genome.body_radii.y, z + 0.02);
            add_tube(
                mesh,
                patch + Vec3::new(-0.13, -0.13, 0.0),
                patch + Vec3::new(0.13, 0.13, 0.0),
                0.045,
                5,
                MaterialClass::Eye,
            );
            add_tube(
                mesh,
                patch + Vec3::new(-0.13, 0.13, 0.0),
                patch + Vec3::new(0.13, -0.13, 0.0),
                0.045,
                5,
                MaterialClass::Eye,
            );
        }
        RoleArchetype::QaEngineer => {
            let stem_root = Vec3::new(0.34 * genome.body_radii.x, -0.55 * genome.body_radii.y, 0.02);
            let screen_center = Vec3::new(0.58 * genome.body_radii.x, -1.04 * genome.body_radii.y, 0.08);
            add_tube(mesh, stem_root, screen_center, 0.055, 5, MaterialClass::Limb);
            add_rect(
                mesh,
                Vec3::new(screen_center.x, screen_center.y, genome.body_radii.z * 0.30),
                0.42,
                0.27,
                MaterialClass::Terminal,
            );
            for (from, to) in [
                ((-0.13_f32, 0.00_f32), (-0.04_f32, -0.05_f32)),
                ((-0.04, -0.05), (0.05, 0.04)),
                ((0.05, 0.04), (0.14, -0.02)),
            ] {
                add_tube(
                    mesh,
                    Vec3::new(
                        screen_center.x + from.0,
                        screen_center.y + from.1,
                        genome.body_radii.z * 0.315,
                    ),
                    Vec3::new(
                        screen_center.x + to.0,
                        screen_center.y + to.1,
                        genome.body_radii.z * 0.315,
                    ),
                    0.018,
                    4,
                    MaterialClass::Eye,
                );
            }
        }
        RoleArchetype::AdversarialReviewer => {
            let mark = Vec3::new(0.58 * genome.body_radii.x, -0.45 * genome.body_radii.y, z + 0.02);
            add_tube(mesh, mark, mark + Vec3::new(-0.06, 0.29, 0.0), 0.050, 5, MaterialClass::Accent);
            add_disc(
                mesh,
                mark + Vec3::new(-0.08, 0.39, 0.0),
                0.055,
                0.055,
                9,
                MaterialClass::Accent,
            );
            for dy in [-0.22_f32, 0.0, 0.22] {
                add_tube(
                    mesh,
                    Vec3::new(-0.58 * genome.body_radii.x, dy, z),
                    Vec3::new(-0.76 * genome.body_radii.x, dy - 0.08, z),
                    0.026,
                    4,
                    MaterialClass::Accent,
                );
            }
        }
        RoleArchetype::CodeReviewer => {
            let left = Vec3::new(-0.20 * genome.body_radii.x, -0.10 * genome.body_radii.y, z + 0.05);
            let right = Vec3::new(0.20 * genome.body_radii.x, -0.10 * genome.body_radii.y, z + 0.05);
            add_disc(mesh, left, 0.17, 0.12, 14, MaterialClass::Terminal);
            add_disc(mesh, right, 0.17, 0.12, 14, MaterialClass::Terminal);
            add_tube(mesh, left, right, 0.025, 4, MaterialClass::Terminal);
            add_rect(
                mesh,
                Vec3::new(0.48 * genome.body_radii.x, 0.26 * genome.body_radii.y, z + 0.02),
                0.30,
                0.38,
                MaterialClass::Accent,
            );
        }
        RoleArchetype::RecoveryEngineer => {
            let center = Vec3::new(0.45 * genome.body_radii.x, 0.02, z + 0.025);
            add_rect(mesh, center, 0.38, 0.16, MaterialClass::Terminal);
            add_rect(mesh, center, 0.14, 0.40, MaterialClass::Terminal);
            add_disc(
                mesh,
                Vec3::new(-0.48 * genome.body_radii.x, 0.24 * genome.body_radii.y, z + 0.02),
                0.075,
                0.075,
                10,
                MaterialClass::Accent,
            );
        }
        RoleArchetype::ReleaseEngineer => {
            let key_center = Vec3::new(0.42 * genome.body_radii.x, -0.25 * genome.body_radii.y, z + 0.025);
            add_disc(mesh, key_center, 0.13, 0.13, 14, MaterialClass::Terminal);
            add_disc(
                mesh,
                key_center + Vec3::new(0.0, 0.0, 0.025),
                0.060,
                0.060,
                12,
                MaterialClass::Body,
            );
            add_tube(
                mesh,
                key_center + Vec3::new(0.0, 0.12, 0.0),
                key_center + Vec3::new(0.0, 0.43, 0.0),
                0.040,
                5,
                MaterialClass::Terminal,
            );
            add_tube(
                mesh,
                key_center + Vec3::new(0.0, 0.32, 0.0),
                key_center + Vec3::new(0.15, 0.32, 0.0),
                0.035,
                5,
                MaterialClass::Terminal,
            );
        }
        RoleArchetype::Antagonist => {
            for (x, y, dx, dy) in [
                (-0.55_f32, -0.28_f32, -0.20_f32, -0.12_f32),
                (0.52, -0.34, 0.22, -0.14),
                (0.55, 0.30, 0.20, 0.16),
            ] {
                let start = Vec3::new(x * genome.body_radii.x, y * genome.body_radii.y, z);
                add_tube(mesh, start, start + Vec3::new(dx, dy, 0.0), 0.038, 5, MaterialClass::Accent);
            }
        }
        RoleArchetype::HallMonitor => {
            for (x, y, radius) in [
                (-0.48_f32, -0.30_f32, 0.10_f32),
                (0.48, -0.30, 0.10),
                (-0.55, 0.25, 0.075),
                (0.55, 0.25, 0.075),
            ] {
                let sensor = Vec3::new(x * genome.body_radii.x, y * genome.body_radii.y, z + 0.02);
                add_disc(mesh, sensor, radius, radius, 12, MaterialClass::Eye);
                add_disc(
                    mesh,
                    sensor + Vec3::new(0.0, 0.0, 0.025),
                    radius * 0.36,
                    radius * 0.36,
                    10,
                    MaterialClass::Limb,
                );
            }
            for (index, height) in [0.10_f32, 0.18, 0.28].into_iter().enumerate() {
                let x = -0.13 + index as f32 * 0.13;
                add_tube(
                    mesh,
                    Vec3::new(x, 0.42 * genome.body_radii.y, z + 0.025),
                    Vec3::new(x, 0.42 * genome.body_radii.y - height, z + 0.025),
                    0.022,
                    4,
                    MaterialClass::Terminal,
                );
            }
        }
        RoleArchetype::Generic => {}
    }
}

fn add_face(mesh: &mut Mesh, genome: &CreatureGenome, pose: &CreaturePose) {
    let z = genome.body_radii.z * 1.055;
    match genome.role {
        RoleArchetype::Orchestrator => {
            add_eye(
                mesh,
                Vec3::new(0.0, -0.02 * genome.body_radii.y, z),
                genome.body_radii.x * 0.50,
                genome.body_radii.y * 0.30,
                pose.eye_aim,
                true,
            );
            add_mouth(mesh, genome, MaterialClass::Accent, 0.24);
        }
        RoleArchetype::ProductManager
        | RoleArchetype::EpaRepresentative
        | RoleArchetype::UxDesigner
        | RoleArchetype::RecoveryEngineer
        | RoleArchetype::ReleaseEngineer
        | RoleArchetype::HallMonitor => {
            add_two_eye_face(mesh, genome, pose, 0.24);
            add_mouth(mesh, genome, MaterialClass::Limb, 0.18);
        }
        RoleArchetype::Researcher => {
            add_eye(
                mesh,
                Vec3::new(0.02 * genome.body_radii.x, -0.04 * genome.body_radii.y, z),
                genome.body_radii.x * 0.43,
                genome.body_radii.y * 0.31,
                pose.eye_aim,
                true,
            );
            add_mouth(mesh, genome, MaterialClass::Limb, 0.14);
        }
        RoleArchetype::Architect => {
            add_eye(
                mesh,
                Vec3::new(0.0, -0.03 * genome.body_radii.y, z),
                genome.body_radii.x * 0.34,
                genome.body_radii.y * 0.27,
                pose.eye_aim,
                false,
            );
            add_rect(
                mesh,
                Vec3::new(0.0, -0.05 * genome.body_radii.y, z + 0.032),
                genome.body_radii.x * 0.72,
                genome.body_radii.y * 0.16,
                MaterialClass::Body,
            );
        }
        RoleArchetype::ImplementationEngineer => {
            add_eye(
                mesh,
                Vec3::new(-0.12 * genome.body_radii.x, -0.03 * genome.body_radii.y, z),
                genome.body_radii.x * 0.28,
                genome.body_radii.y * 0.43,
                pose.eye_aim,
                false,
            );
            add_mouth(mesh, genome, MaterialClass::Limb, 0.19);
        }
        RoleArchetype::CrashTestDummy => {
            let spread = genome.body_radii.x * 0.27;
            add_eye(
                mesh,
                Vec3::new(-spread, -0.04 * genome.body_radii.y, z),
                genome.body_radii.x * 0.22,
                genome.body_radii.y * 0.31,
                pose.eye_aim,
                false,
            );
            add_eye(
                mesh,
                Vec3::new(spread * 0.62, -0.02 * genome.body_radii.y, z),
                genome.body_radii.x * 0.20,
                genome.body_radii.y * 0.29,
                pose.eye_aim,
                false,
            );
            add_mouth(mesh, genome, MaterialClass::Limb, 0.16);
        }
        RoleArchetype::QaEngineer => {
            add_eye(
                mesh,
                Vec3::new(0.02 * genome.body_radii.x, 0.02 * genome.body_radii.y, z),
                genome.body_radii.x * 0.46,
                genome.body_radii.y * 0.25,
                pose.eye_aim,
                false,
            );
            add_rect(
                mesh,
                Vec3::new(0.0, -0.12 * genome.body_radii.y, z + 0.035),
                genome.body_radii.x * 0.92,
                genome.body_radii.y * 0.24,
                MaterialClass::Body,
            );
        }
        RoleArchetype::AdversarialReviewer => {
            add_disc(
                mesh,
                Vec3::new(0.0, 0.02 * genome.body_radii.y, z),
                genome.body_radii.x * 0.44,
                genome.body_radii.y * 0.20,
                16,
                MaterialClass::Eye,
            );
            add_disc(
                mesh,
                Vec3::new(pose.eye_aim.x * 0.12, 0.03 * genome.body_radii.y, z + 0.035),
                genome.body_radii.x * 0.10,
                genome.body_radii.y * 0.13,
                12,
                MaterialClass::Limb,
            );
            add_tube(
                mesh,
                Vec3::new(-0.30 * genome.body_radii.x, -0.20 * genome.body_radii.y, z + 0.045),
                Vec3::new(0.28 * genome.body_radii.x, -0.28 * genome.body_radii.y, z + 0.045),
                0.028,
                5,
                MaterialClass::Limb,
            );
        }
        RoleArchetype::CodeReviewer => {
            add_two_eye_face(mesh, genome, pose, 0.20);
            add_rect(
                mesh,
                Vec3::new(0.0, -0.06 * genome.body_radii.y, z + 0.045),
                genome.body_radii.x * 0.84,
                genome.body_radii.y * 0.18,
                MaterialClass::Body,
            );
        }
        RoleArchetype::Antagonist => {
            let spread = genome.body_radii.x * 0.24;
            add_eye(
                mesh,
                Vec3::new(-spread, -0.03 * genome.body_radii.y, z),
                genome.body_radii.x * 0.20,
                genome.body_radii.y * 0.24,
                pose.eye_aim,
                false,
            );
            add_eye(
                mesh,
                Vec3::new(spread, -0.10 * genome.body_radii.y, z),
                genome.body_radii.x * 0.16,
                genome.body_radii.y * 0.21,
                pose.eye_aim,
                false,
            );
            add_mouth(mesh, genome, MaterialClass::Accent, 0.20);
        }
        RoleArchetype::Generic => {
            add_eye(
                mesh,
                Vec3::new(0.0, -0.02 * genome.body_radii.y, z),
                genome.body_radii.x * 0.38,
                genome.body_radii.y * 0.28,
                pose.eye_aim,
                false,
            );
        }
    }
}

fn add_two_eye_face(mesh: &mut Mesh, genome: &CreatureGenome, pose: &CreaturePose, radius: f32) {
    let z = genome.body_radii.z * 1.055;
    let spread = genome.body_radii.x * 0.25;
    for x in [-spread, spread] {
        add_eye(
            mesh,
            Vec3::new(x, -0.04 * genome.body_radii.y, z),
            genome.body_radii.x * radius,
            genome.body_radii.y * radius * 1.15,
            pose.eye_aim,
            false,
        );
    }
}

fn add_eye(
    mesh: &mut Mesh,
    center: Vec3,
    radius_x: f32,
    radius_y: f32,
    aim: Vec2,
    large_pupil: bool,
) {
    add_disc(mesh, center, radius_x, radius_y, 18, MaterialClass::Eye);
    let pupil_x = center.x + aim.x * radius_x * 0.38;
    let pupil_y = center.y + aim.y * radius_y * 0.30;
    add_disc(
        mesh,
        Vec3::new(pupil_x, pupil_y, center.z + 0.025),
        radius_x * if large_pupil { 0.28 } else { 0.24 },
        radius_y * if large_pupil { 0.82 } else { 0.62 },
        14,
        MaterialClass::Limb,
    );
}

fn add_mouth(mesh: &mut Mesh, genome: &CreatureGenome, material: MaterialClass, width: f32) {
    let z = genome.body_radii.z * 1.075;
    let y = genome.body_radii.y * 0.42;
    let left = Vec3::new(-width * genome.body_radii.x, y - 0.02, z);
    let middle = Vec3::new(0.0, y + 0.08, z);
    let right = Vec3::new(width * genome.body_radii.x, y - 0.02, z);
    add_tube(mesh, left, middle, 0.025, 5, material);
    add_tube(mesh, middle, right, 0.025, 5, material);
}

fn add_antennae(mesh: &mut Mesh, genome: &CreatureGenome, pose: &CreaturePose) {
    for (index, antenna) in genome.antennae.iter().enumerate() {
        let cos_elevation = antenna.elevation.cos();
        let direction = Vec3::new(
            antenna.azimuth.cos() * cos_elevation,
            antenna.elevation.sin(),
            antenna.azimuth.sin() * cos_elevation,
        )
        .normalized();
        let (tangent, _) = perpendicular_basis(direction);
        let body_surface = Vec3::new(
            direction.x * genome.body_radii.x * 0.86,
            direction.y * genome.body_radii.y * 0.86,
            direction.z * genome.body_radii.z * 0.86,
        );
        let bend = antenna.bend + pose.antenna_bend.get(index).copied().unwrap_or(0.0);
        let length = antenna.length + pose.antenna_extension.get(index).copied().unwrap_or(0.0);
        let mid = body_surface + direction * (length * 0.52) + tangent * (bend * 0.42);
        let tip = body_surface + direction * length + tangent * bend;

        if antenna.terminal == TerminalKind::Coil {
            add_coiled_antenna(
                mesh,
                body_surface,
                tip,
                antenna.radius,
                MaterialClass::Accent,
            );
        } else {
            add_tube(
                mesh,
                body_surface,
                mid,
                antenna.radius,
                6,
                MaterialClass::Accent,
            );
            add_tube(
                mesh,
                mid,
                tip,
                antenna.radius * 0.90,
                6,
                MaterialClass::Accent,
            );
        }
        add_terminal(
            mesh,
            tip,
            direction,
            antenna.terminal,
            antenna.radius * 1.90,
        );
        mesh.terminal_points.push(tip);
    }
}

fn add_coiled_antenna(
    mesh: &mut Mesh,
    start: Vec3,
    end: Vec3,
    radius: f32,
    material: MaterialClass,
) {
    let axis_vector = end - start;
    let length = axis_vector.length();
    if length <= f32::EPSILON {
        return;
    }
    let axis = axis_vector / length;
    let (basis_a, basis_b) = perpendicular_basis(axis);
    let segments = 20;
    let turns = 2.6;
    let coil_radius = radius * 1.75;
    let mut previous = start;
    for segment in 1..=segments {
        let t = segment as f32 / segments as f32;
        let angle = TAU * turns * t;
        let envelope = (PI * t).sin();
        let next = start
            + axis * (length * t)
            + basis_a * angle.cos() * coil_radius * envelope
            + basis_b * angle.sin() * coil_radius * envelope;
        add_tube(mesh, previous, next, radius * 0.52, 5, material);
        previous = next;
    }
}

fn add_limbs(mesh: &mut Mesh, genome: &CreatureGenome, pose: &CreaturePose) {
    let leg_count = genome.leg_count;
    for index in 0..leg_count {
        let fraction = if leg_count == 1 {
            0.5
        } else {
            index as f32 / (leg_count - 1) as f32
        };
        let x = (fraction - 0.5) * genome.body_radii.x * 0.78;
        let gait = pose.limb_phase.sin() * 0.05 * if index % 2 == 0 { 1.0 } else { -1.0 };
        let hip = Vec3::new(x, genome.body_radii.y * 0.70, 0.0);
        let knee = Vec3::new(x + gait, genome.body_radii.y * 0.86, 0.04);
        let foot = Vec3::new(x + gait * 1.2, genome.body_radii.y * 0.98, 0.10);
        add_tube(mesh, hip, knee, 0.048, 6, MaterialClass::Limb);
        add_tube(mesh, knee, foot, 0.042, 6, MaterialClass::Limb);
        add_ellipsoid(
            mesh,
            foot,
            Vec3::new(0.09, 0.055, 0.06),
            10,
            5,
            MaterialClass::Limb,
        );
    }

    if genome.arm_count == 0 {
        return;
    }
    let arm_swing = pose.limb_phase.sin() * 0.10;
    for index in 0..genome.arm_count {
        let side = if index % 2 == 0 { -1.0_f32 } else { 1.0_f32 };
        let row = index / 2;
        let y = -0.15 + row as f32 * 0.33;
        let root = Vec3::new(
            side * genome.body_radii.x * 0.76,
            y * genome.body_radii.y,
            0.05,
        );
        let elbow = Vec3::new(
            side * genome.body_radii.x * (0.92 + row as f32 * 0.04),
            genome.body_radii.y * (y + 0.10 + arm_swing * side),
            0.10,
        );
        let hand = Vec3::new(
            side * genome.body_radii.x * (1.06 + row as f32 * 0.05),
            genome.body_radii.y * (y + 0.15 + arm_swing * side),
            0.16,
        );
        add_tube(mesh, root, elbow, 0.052, 6, MaterialClass::Limb);
        add_tube(mesh, elbow, hand, 0.046, 6, MaterialClass::Limb);
        add_terminal(
            mesh,
            hand,
            (hand - elbow).normalized(),
            hand_terminal_for_role(genome.role),
            0.11,
        );
    }
}

fn hand_terminal_for_role(role: RoleArchetype) -> TerminalKind {
    match role {
        RoleArchetype::Orchestrator => TerminalKind::Node,
        RoleArchetype::ProductManager => TerminalKind::Fork,
        RoleArchetype::Researcher => TerminalKind::Loop,
        RoleArchetype::Architect => TerminalKind::Fork,
        RoleArchetype::EpaRepresentative => TerminalKind::Clamp,
        RoleArchetype::UxDesigner => TerminalKind::Loop,
        RoleArchetype::ImplementationEngineer => TerminalKind::Clamp,
        RoleArchetype::CrashTestDummy => TerminalKind::Node,
        RoleArchetype::QaEngineer => TerminalKind::Probe,
        RoleArchetype::AdversarialReviewer => TerminalKind::Fork,
        RoleArchetype::CodeReviewer => TerminalKind::Fork,
        RoleArchetype::RecoveryEngineer => TerminalKind::Clamp,
        RoleArchetype::ReleaseEngineer => TerminalKind::Node,
        RoleArchetype::Antagonist => TerminalKind::Fork,
        RoleArchetype::HallMonitor => TerminalKind::Probe,
        RoleArchetype::Generic => TerminalKind::Node,
    }
}

fn add_disc(
    mesh: &mut Mesh,
    center: Vec3,
    radius_x: f32,
    radius_y: f32,
    sides: usize,
    material: MaterialClass,
) {
    add_oriented_disc(mesh, center, Vec3::Z, radius_x, radius_y, sides, material);
}

fn add_oriented_disc(
    mesh: &mut Mesh,
    center: Vec3,
    normal: Vec3,
    radius_x: f32,
    radius_y: f32,
    sides: usize,
    material: MaterialClass,
) {
    let normal = normal.normalized();
    let (basis_a, basis_b) = perpendicular_basis(normal);
    let center_index = mesh.vertices.len();
    mesh.vertices.push(center);
    let mut ring = Vec::with_capacity(sides);
    for side in 0..sides {
        let angle = TAU * side as f32 / sides as f32;
        ring.push(mesh.vertices.len());
        mesh.vertices
            .push(center + basis_a * radius_x * angle.cos() + basis_b * radius_y * angle.sin());
    }
    for side in 0..sides {
        mesh.faces.push(Face {
            indices: [center_index, ring[side], ring[(side + 1) % sides]],
            material,
        });
    }
}

fn add_rect(mesh: &mut Mesh, center: Vec3, width: f32, height: f32, material: MaterialClass) {
    let half_w = width * 0.5;
    let half_h = height * 0.5;
    let first = mesh.vertices.len();
    mesh.vertices
        .push(center + Vec3::new(-half_w, -half_h, 0.0));
    mesh.vertices.push(center + Vec3::new(half_w, -half_h, 0.0));
    mesh.vertices.push(center + Vec3::new(half_w, half_h, 0.0));
    mesh.vertices.push(center + Vec3::new(-half_w, half_h, 0.0));
    mesh.faces.push(Face {
        indices: [first, first + 1, first + 2],
        material,
    });
    mesh.faces.push(Face {
        indices: [first, first + 2, first + 3],
        material,
    });
}

fn add_tube(
    mesh: &mut Mesh,
    start: Vec3,
    end: Vec3,
    radius: f32,
    sides: usize,
    material: MaterialClass,
) {
    let axis = (end - start).normalized();
    if axis.length() <= f32::EPSILON {
        return;
    }
    let (basis_a, basis_b) = perpendicular_basis(axis);
    let mut start_ring = Vec::with_capacity(sides);
    let mut end_ring = Vec::with_capacity(sides);
    for side in 0..sides {
        let angle = TAU * side as f32 / sides as f32;
        let radial = basis_a * angle.cos() * radius + basis_b * angle.sin() * radius;
        start_ring.push(mesh.vertices.len());
        mesh.vertices.push(start + radial);
        end_ring.push(mesh.vertices.len());
        mesh.vertices.push(end + radial);
    }
    for side in 0..sides {
        let next = (side + 1) % sides;
        mesh.faces.push(Face {
            indices: [start_ring[side], start_ring[next], end_ring[side]],
            material,
        });
        mesh.faces.push(Face {
            indices: [start_ring[next], end_ring[next], end_ring[side]],
            material,
        });
    }
}

fn add_terminal(mesh: &mut Mesh, center: Vec3, direction: Vec3, kind: TerminalKind, radius: f32) {
    let direction = direction.normalized();
    let material = MaterialClass::Terminal;

    // Every antenna ends in an actual node/socket. Role-specific machinery grows out of that node;
    // graph edges attach to the socket center rather than to an abstract body boundary.
    add_ellipsoid(
        mesh,
        center,
        Vec3::new(radius, radius * 0.94, radius * 0.88),
        14,
        6,
        material,
    );

    match kind {
        TerminalKind::Node | TerminalKind::Probe | TerminalKind::Coil => {
            let eye_center = center + direction * radius * 0.78;
            add_oriented_disc(
                mesh,
                eye_center,
                direction,
                radius * 0.56,
                radius * 0.50,
                12,
                MaterialClass::Eye,
            );
            add_oriented_disc(
                mesh,
                eye_center + direction * radius * 0.045,
                direction,
                radius
                    * if kind == TerminalKind::Probe {
                        0.22
                    } else {
                        0.18
                    },
                radius
                    * if kind == TerminalKind::Probe {
                        0.30
                    } else {
                        0.24
                    },
                10,
                MaterialClass::Limb,
            );
        }
        TerminalKind::Clamp => {
            let (tangent, _) = perpendicular_basis(direction);
            add_tube(
                mesh,
                center + direction * radius * 0.35,
                center + direction * radius * 1.65 + tangent * radius * 0.92,
                radius * 0.25,
                5,
                material,
            );
            add_tube(
                mesh,
                center + direction * radius * 0.35,
                center + direction * radius * 1.65 - tangent * radius * 0.92,
                radius * 0.25,
                5,
                material,
            );
        }
        TerminalKind::Fork => {
            let (tangent, _) = perpendicular_basis(direction);
            add_tube(
                mesh,
                center + direction * radius * 0.30,
                center + direction * radius * 1.45 + tangent * radius * 0.76,
                radius * 0.22,
                5,
                material,
            );
            add_tube(
                mesh,
                center + direction * radius * 0.30,
                center + direction * radius * 1.45 - tangent * radius * 0.76,
                radius * 0.22,
                5,
                material,
            );
        }
        TerminalKind::Loop => {
            let (tangent, bitangent) = perpendicular_basis(direction);
            let segments = 12;
            let loop_center = center + direction * radius * 1.10;
            let mut previous = loop_center + tangent * radius * 0.82;
            for segment in 1..=segments {
                let angle = TAU * segment as f32 / segments as f32;
                let next = loop_center
                    + tangent * angle.cos() * radius * 0.82
                    + bitangent * angle.sin() * radius * 0.82;
                add_tube(mesh, previous, next, radius * 0.16, 5, material);
                previous = next;
            }
        }
    }
}

fn perpendicular_basis(axis: Vec3) -> (Vec3, Vec3) {
    let axis = axis.normalized();
    let reference = if axis.z.abs() < 0.84 {
        Vec3::Z
    } else {
        Vec3::Y
    };
    let basis_a = axis.cross(reference).normalized();
    let basis_b = axis.cross(basis_a).normalized();
    (basis_a, basis_b)
}

fn transform_model_point(point: Vec3, pose: &CreaturePose) -> Vec3 {
    rotate_xyz(point.component_mul(pose.body_scale), pose.body_rotation) + pose.body_offset
}

fn extract_silhouette_edges(
    mesh: &Mesh,
    transformed: &[Vec3],
    projected: &[Vec2],
) -> Vec<RenderEdge> {
    #[derive(Clone, Copy, Debug, Default)]
    struct EdgeInfo {
        front: usize,
        back: usize,
        depth: f32,
    }

    let mut edges: HashMap<(usize, usize), EdgeInfo> = HashMap::new();
    for face in &mesh.faces {
        let a = transformed[face.indices[0]];
        let b = transformed[face.indices[1]];
        let c = transformed[face.indices[2]];
        let normal = (b - a).cross(c - a).normalized();
        let front_facing = normal.z >= 0.0;
        let face_depth = (a.z + b.z + c.z) / 3.0;
        for (left, right) in [
            (face.indices[0], face.indices[1]),
            (face.indices[1], face.indices[2]),
            (face.indices[2], face.indices[0]),
        ] {
            let key = if left < right {
                (left, right)
            } else {
                (right, left)
            };
            let entry = edges.entry(key).or_default();
            if front_facing {
                entry.front += 1;
            } else {
                entry.back += 1;
            }
            entry.depth = entry.depth.max(face_depth);
        }
    }

    let mut output = Vec::new();
    for ((left, right), info) in edges {
        let is_boundary = info.front > 0 && (info.back > 0 || info.front == 1);
        if is_boundary {
            output.push(RenderEdge {
                from: projected[left],
                to: projected[right],
                depth: info.depth,
                weight: if info.back > 0 { 1.0 } else { 0.72 },
            });
        }
    }
    output.sort_by(|left, right| left.depth.total_cmp(&right.depth));
    output
}
