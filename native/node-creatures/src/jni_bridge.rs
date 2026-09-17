use crate::{encode_render_frame, render_creature, Activity, Camera, PACKET_VERSION};
use jni::objects::{JClass, JString};
use jni::sys::{jbyteArray, jfloat, jint};
use jni::JNIEnv;
use std::ptr::null_mut;

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_geministrator_NodeCreatureNativeBridge_packetVersion(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    PACKET_VERSION as jint
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_geministrator_NodeCreatureNativeBridge_renderPacket<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    role: JString<'local>,
    identity_seed: JString<'local>,
    activity_code: jint,
    time_seconds: jfloat,
    camera_yaw: jfloat,
    camera_pitch: jfloat,
    camera_zoom: jfloat,
) -> jbyteArray {
    let role: String = match env.get_string(&role) {
        Ok(value) => value.into(),
        Err(_) => return null_mut(),
    };
    let identity_seed: String = match env.get_string(&identity_seed) {
        Ok(value) => value.into(),
        Err(_) => return null_mut(),
    };

    let frame = render_creature(
        &role,
        &identity_seed,
        activity_from_code(activity_code),
        time_seconds,
        Camera {
            yaw: camera_yaw,
            pitch: camera_pitch,
            zoom: camera_zoom,
        },
    );
    let packet = encode_render_frame(&frame);

    match env.byte_array_from_slice(&packet) {
        Ok(array) => array.into_raw(),
        Err(_) => null_mut(),
    }
}

fn activity_from_code(code: jint) -> Activity {
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
