use crate::genome::{Activity, CreatureGenome, CreaturePose, RoleArchetype, TerminalKind};
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
            yaw: -0.36,
            pitch: 0.18,
            zoom: 0.34,
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
        genome.body_sides.max(6),
        5,
        MaterialClass::Body,
    );

    add_eyes(&mut mesh, genome, pose);
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
            let right_score = right.normalized().x * direction.x + right.normalized().y * direction.y;
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

fn add_eyes(mesh: &mut Mesh, genome: &CreatureGenome, pose: &CreaturePose) {
    let count = genome.eye_count.max(1);
    for index in 0..count {
        let spread = if count == 1 {
            0.0
        } else {
            (index as f32 / (count - 1) as f32 - 0.5) * genome.body_radii.x * 0.78
        };
        let center = Vec3::new(
            spread + pose.eye_aim.x * genome.body_radii.x * 0.16,
            -genome.body_radii.y * 0.12 + pose.eye_aim.y * genome.body_radii.y * 0.10,
            genome.body_radii.z * 1.025,
        );
        add_disc(
            mesh,
            center,
            genome.body_radii.x * if count == 1 { 0.42 } else { 0.23 },
            genome.body_radii.y * if count == 1 { 0.27 } else { 0.22 },
            12,
            MaterialClass::Eye,
        );
    }
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
        let tangent = Vec3::new(-antenna.azimuth.sin(), 0.0, antenna.azimuth.cos()).normalized();
        let body_surface = Vec3::new(
            direction.x * genome.body_radii.x * 0.78,
            direction.y * genome.body_radii.y * 0.78,
            direction.z * genome.body_radii.z * 0.78,
        );
        let bend = antenna.bend + pose.antenna_bend.get(index).copied().unwrap_or(0.0);
        let length = antenna.length + pose.antenna_extension.get(index).copied().unwrap_or(0.0);
        let mid = body_surface + direction * (length * 0.52) + tangent * (bend * 0.42);
        let tip = body_surface + direction * length + tangent * bend;
        add_tube(mesh, body_surface, mid, antenna.radius, 6, MaterialClass::Accent);
        add_tube(mesh, mid, tip, antenna.radius * 0.90, 6, MaterialClass::Accent);
        add_terminal(mesh, tip, direction, antenna.terminal, antenna.radius * 2.35);
        mesh.terminal_points.push(tip);
    }
}

fn add_limbs(mesh: &mut Mesh, genome: &CreatureGenome, pose: &CreaturePose) {
    let leg_count = genome.leg_count.max(2);
    for index in 0..leg_count {
        let fraction = if leg_count == 1 {
            0.5
        } else {
            index as f32 / (leg_count - 1) as f32
        };
        let x = (fraction - 0.5) * genome.body_radii.x * 1.45;
        let gait = pose.limb_phase.sin() * 0.10 * if index % 2 == 0 { 1.0 } else { -1.0 };
        let hip = Vec3::new(x, genome.body_radii.y * 0.62, 0.0);
        let knee = Vec3::new(x + gait, genome.body_radii.y * 1.03, 0.04);
        let foot = Vec3::new(x + gait * 1.4, genome.body_radii.y * 1.26, 0.14);
        add_tube(mesh, hip, knee, 0.075, 5, MaterialClass::Limb);
        add_tube(mesh, knee, foot, 0.065, 5, MaterialClass::Limb);
    }

    let arm_swing = pose.limb_phase.sin() * 0.12;
    for side in [-1.0_f32, 1.0_f32] {
        let root = Vec3::new(side * genome.body_radii.x * 0.72, 0.02, 0.05);
        let elbow = Vec3::new(
            side * genome.body_radii.x * 1.02,
            genome.body_radii.y * (0.10 + arm_swing * side),
            0.10,
        );
        let hand = Vec3::new(
            side * genome.body_radii.x * 1.30,
            genome.body_radii.y * (0.16 + arm_swing * side),
            0.18,
        );
        add_tube(mesh, root, elbow, 0.070, 5, MaterialClass::Limb);
        add_tube(mesh, elbow, hand, 0.060, 5, MaterialClass::Limb);
        add_terminal(
            mesh,
            hand,
            (hand - elbow).normalized(),
            hand_terminal_for_role(genome.role),
            0.12,
        );
    }
}

fn hand_terminal_for_role(role: RoleArchetype) -> TerminalKind {
    match role {
        RoleArchetype::Builder => TerminalKind::Clamp,
        RoleArchetype::Inspector => TerminalKind::Probe,
        RoleArchetype::Reviewer => TerminalKind::Fork,
        RoleArchetype::Tester => TerminalKind::Node,
        RoleArchetype::Planner => TerminalKind::Fork,
        RoleArchetype::Researcher => TerminalKind::Loop,
        RoleArchetype::Orchestrator | RoleArchetype::Generic => TerminalKind::Node,
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
    let center_index = mesh.vertices.len();
    mesh.vertices.push(center);
    let mut ring = Vec::with_capacity(sides);
    for side in 0..sides {
        let angle = TAU * side as f32 / sides as f32;
        ring.push(mesh.vertices.len());
        mesh.vertices.push(center + Vec3::new(radius_x * angle.cos(), radius_y * angle.sin(), 0.012));
    }
    for side in 0..sides {
        mesh.faces.push(Face {
            indices: [center_index, ring[side], ring[(side + 1) % sides]],
            material,
        });
    }
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
    let reference = if axis.z.abs() < 0.86 { Vec3::Z } else { Vec3::Y };
    let basis_a = axis.cross(reference).normalized();
    let basis_b = axis.cross(basis_a).normalized();
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
    let material = MaterialClass::Terminal;
    match kind {
        TerminalKind::Node | TerminalKind::Probe | TerminalKind::Coil => {
            add_ellipsoid(
                mesh,
                center,
                Vec3::new(radius, radius * 0.92, radius * 0.82),
                8,
                4,
                material,
            );
        }
        TerminalKind::Clamp => {
            let tangent = direction.cross(Vec3::Y).normalized();
            add_tube(mesh, center, center + direction * radius * 1.45 + tangent * radius, radius * 0.28, 5, material);
            add_tube(mesh, center, center + direction * radius * 1.45 - tangent * radius, radius * 0.28, 5, material);
        }
        TerminalKind::Fork => {
            let tangent = direction.cross(Vec3::Z).normalized();
            add_tube(mesh, center, center + direction * radius * 1.35 + tangent * radius * 0.82, radius * 0.24, 5, material);
            add_tube(mesh, center, center + direction * radius * 1.35 - tangent * radius * 0.82, radius * 0.24, 5, material);
        }
        TerminalKind::Loop => {
            let tangent = direction.cross(Vec3::Y).normalized();
            let bitangent = direction.cross(tangent).normalized();
            let segments = 10;
            let mut previous = center + tangent * radius;
            for segment in 1..=segments {
                let angle = TAU * segment as f32 / segments as f32;
                let next = center + tangent * angle.cos() * radius + bitangent * angle.sin() * radius;
                add_tube(mesh, previous, next, radius * 0.20, 5, material);
                previous = next;
            }
        }
    }
}

fn transform_model_point(point: Vec3, pose: &CreaturePose) -> Vec3 {
    rotate_xyz(point.component_mul(pose.body_scale), pose.body_rotation) + pose.body_offset
}

fn extract_silhouette_edges(mesh: &Mesh, transformed: &[Vec3], projected: &[Vec2]) -> Vec<RenderEdge> {
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
            let key = if left < right { (left, right) } else { (right, left) };
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
