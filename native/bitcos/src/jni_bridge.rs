use crate::{best_decode_backend, decode_payload, BITCOS_VERSION};
use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;
use std::ptr::null_mut;

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
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    element_count: jlong,
    presence: JByteArray<'local>,
    signs: JByteArray<'local>,
) -> jbyteArray {
    if element_count < 0 {
        return null_mut();
    }

    let presence = match env.convert_byte_array(&presence) {
        Ok(value) => value,
        Err(_) => return null_mut(),
    };
    let signs = match env.convert_byte_array(&signs) {
        Ok(value) => value,
        Err(_) => return null_mut(),
    };
    let decoded = match decode_payload(element_count as u64, &presence, &signs) {
        Ok(value) => value,
        Err(_) => return null_mut(),
    };
    let bytes = decoded.into_iter().map(|value| value as u8).collect::<Vec<_>>();
    match env.byte_array_from_slice(&bytes) {
        Ok(array) => array.into_raw(),
        Err(_) => null_mut(),
    }
}
