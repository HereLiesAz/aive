use crate::engine::{Camera, MaterialClass, RenderFrame};
use crate::genome::Activity;
use crate::render_creature;
use core::slice;
use core::str;

pub const PACKET_MAGIC: [u8; 4] = *b"HNCR";
pub const PACKET_VERSION: u16 = 1;

#[repr(C)]
pub struct HaiveBuffer {
    pub ptr: *mut u8,
    pub len: usize,
    pub capacity: usize,
}

impl HaiveBuffer {
    fn from_vec(mut bytes: Vec<u8>) -> Self {
        let buffer = Self {
            ptr: bytes.as_mut_ptr(),
            len: bytes.len(),
            capacity: bytes.capacity(),
        };
        core::mem::forget(bytes);
        buffer
    }
}

pub fn encode_render_frame(frame: &RenderFrame) -> Vec<u8> {
    let mut output = Vec::with_capacity(
        20 + frame.triangles.len() * 32
            + frame.silhouette_edges.len() * 24
            + frame.terminal_anchors.len() * 8,
    );
    output.extend_from_slice(&PACKET_MAGIC);
    put_u16(&mut output, PACKET_VERSION);
    put_u16(&mut output, 0);
    put_u32(&mut output, frame.triangles.len() as u32);
    put_u32(&mut output, frame.silhouette_edges.len() as u32);
    put_u32(&mut output, frame.terminal_anchors.len() as u32);

    for triangle in &frame.triangles {
        output.push(material_code(triangle.material));
        output.push(triangle.shade);
        put_u16(&mut output, 0);
        put_f32(&mut output, triangle.depth);
        for point in triangle.points {
            put_f32(&mut output, point.x);
            put_f32(&mut output, point.y);
        }
    }

    for edge in &frame.silhouette_edges {
        put_f32(&mut output, edge.depth);
        put_f32(&mut output, edge.weight);
        put_f32(&mut output, edge.from.x);
        put_f32(&mut output, edge.from.y);
        put_f32(&mut output, edge.to.x);
        put_f32(&mut output, edge.to.y);
    }

    for terminal in &frame.terminal_anchors {
        put_f32(&mut output, terminal.x);
        put_f32(&mut output, terminal.y);
    }

    output
}

#[no_mangle]
pub unsafe extern "C" fn haive_node_render_packet(
    role_ptr: *const u8,
    role_len: usize,
    seed_ptr: *const u8,
    seed_len: usize,
    activity_code: u8,
    time_seconds: f32,
    camera_yaw: f32,
    camera_pitch: f32,
    camera_zoom: f32,
) -> HaiveBuffer {
    let role = decode_utf8(role_ptr, role_len).unwrap_or("Agent");
    let seed = decode_utf8(seed_ptr, seed_len).unwrap_or("haive-agent");
    let activity = decode_activity(activity_code);
    let frame = render_creature(
        role,
        seed,
        activity,
        time_seconds,
        Camera {
            yaw: camera_yaw,
            pitch: camera_pitch,
            zoom: camera_zoom,
        },
    );
    HaiveBuffer::from_vec(encode_render_frame(&frame))
}

#[no_mangle]
pub unsafe extern "C" fn haive_node_free_buffer(buffer: HaiveBuffer) {
    if buffer.ptr.is_null() || buffer.capacity == 0 {
        return;
    }
    drop(Vec::from_raw_parts(buffer.ptr, buffer.len, buffer.capacity));
}

#[no_mangle]
pub extern "C" fn haive_node_alloc(size: usize) -> HaiveBuffer {
    let bytes = vec![0_u8; size];
    HaiveBuffer::from_vec(bytes)
}

fn decode_utf8<'a>(ptr: *const u8, len: usize) -> Option<&'a str> {
    if ptr.is_null() {
        return None;
    }
    let bytes = unsafe { slice::from_raw_parts(ptr, len) };
    str::from_utf8(bytes).ok()
}

fn decode_activity(code: u8) -> Activity {
    match code {
        1 => Activity::Ready,
        2 => Activity::Active,
        3 => Activity::Blocked,
        4 => Activity::Failed,
        5 => Activity::Complete,
        6 => Activity::Gate,
        _ => Activity::Queued,
    }
}

fn material_code(material: MaterialClass) -> u8 {
    match material {
        MaterialClass::Body => 0,
        MaterialClass::Accent => 1,
        MaterialClass::Eye => 2,
        MaterialClass::Limb => 3,
        MaterialClass::Terminal => 4,
    }
}

fn put_u16(output: &mut Vec<u8>, value: u16) {
    output.extend_from_slice(&value.to_le_bytes());
}

fn put_u32(output: &mut Vec<u8>, value: u32) {
    output.extend_from_slice(&value.to_le_bytes());
}

fn put_f32(output: &mut Vec<u8>, value: f32) {
    output.extend_from_slice(&value.to_le_bytes());
}
