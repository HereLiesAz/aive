use crate::{best_decode_backend, decode_payload, BITCOS_VERSION};
use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_geministrator_inference_BitcosNativeBridge_abiVersion(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    BITCOS_VERSION as jint
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_geministrator_inference_BitcosNativeBridge_bestDecodeBackend(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    best_decode_backend() as jint
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_geministrator_inference_BitcosNativeBridge_decodePayload<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    element_count: jlong,
    presence: JByteArray<'local>,
    signs: JByteArray<'local>,
) -> jbyteArray {
    // Any failure hands Java a null array; no raw pointer is made or touched on this side.
    decode_to_java(&mut env, element_count, &presence, &signs)
        .unwrap_or_default()
        .into_raw()
}

fn decode_to_java<'local>(
    env: &mut JNIEnv<'local>,
    element_count: jlong,
    presence: &JByteArray<'local>,
    signs: &JByteArray<'local>,
) -> Option<JByteArray<'local>> {
    let element_count = u64::try_from(element_count).ok()?;
    let presence = env.convert_byte_array(presence).ok()?;
    let signs = env.convert_byte_array(signs).ok()?;
    let decoded = decode_payload(element_count, &presence, &signs).ok()?;
    let bytes: Vec<u8> = decoded.iter().map(|&value| value as u8).collect();
    env.byte_array_from_slice(&bytes).ok()
}
