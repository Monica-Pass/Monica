#![allow(non_snake_case)]

mod search;

use jni::objects::{JByteArray, JClass, JIntArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jintArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use monica_rust_crypto::{derive_argon2id, derive_pbkdf2_sha256};
use search::{filter_metadata_batch, SearchQuery};

const RUST_CORE_VERSION: &str = "monica-rust-jni/0.5.0-kdf";

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustPasswordListCore_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(RUST_CORE_VERSION) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustPasswordListCore_nativeSelfTest(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let query = SearchQuery::new("github");
    if query.matches_value("GitHub") && !query.matches_value("example.cn") {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustPasswordListCore_nativeFilterIndices(
    mut env: JNIEnv,
    _class: JClass,
    metadata: JByteArray,
    query: JString,
) -> jintArray {
    filter_indices(&mut env, &metadata, &query).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustBitwardenKdfCore_nativeDerivePbkdf2Sha256(
    mut env: JNIEnv,
    _class: JClass,
    password: JByteArray,
    salt: JByteArray,
    iterations: jint,
) -> jbyteArray {
    derive_pbkdf2(&mut env, &password, &salt, iterations).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustBitwardenKdfCore_nativeDeriveArgon2id(
    mut env: JNIEnv,
    _class: JClass,
    password: JByteArray,
    salt: JByteArray,
    iterations: jint,
    memory_kib: jint,
    parallelism: jint,
) -> jbyteArray {
    derive_argon2(
        &mut env,
        &password,
        &salt,
        iterations,
        memory_kib,
        parallelism,
    )
    .unwrap_or(std::ptr::null_mut())
}

fn filter_indices(env: &mut JNIEnv, metadata: &JByteArray, query: &JString) -> Option<jintArray> {
    // One JNI copy replaces five object arrays plus up to 5*N element/string
    // lookups. The parser then borrows UTF-8 slices directly from this buffer.
    let metadata = env.convert_byte_array(metadata).ok()?;
    let query: String = env.get_string(query).ok()?.into();
    let query = SearchQuery::new(&query);
    let selected = filter_metadata_batch(&metadata, &query)?;

    let output: JIntArray<'_> = env.new_int_array(selected.len() as i32).ok()?;
    env.set_int_array_region(&output, 0, &selected).ok()?;
    Some(output.into_raw())
}

fn derive_pbkdf2(
    env: &mut JNIEnv,
    password: &JByteArray,
    salt: &JByteArray,
    iterations: jint,
) -> Option<jbyteArray> {
    let iterations = positive_u32(iterations)?;
    let mut password = env.convert_byte_array(password).ok()?;
    let mut salt = env.convert_byte_array(salt).ok()?;
    let result = derive_pbkdf2_sha256(&password, &salt, iterations).ok();
    password.fill(0);
    salt.fill(0);
    let result = result?;
    let output = env.byte_array_from_slice(&result).ok()?;
    Some(output.into_raw())
}

fn derive_argon2(
    env: &mut JNIEnv,
    password: &JByteArray,
    salt: &JByteArray,
    iterations: jint,
    memory_kib: jint,
    parallelism: jint,
) -> Option<jbyteArray> {
    let iterations = positive_u32(iterations)?;
    let memory_kib = positive_u32(memory_kib)?;
    let parallelism = positive_u32(parallelism)?;
    let mut password = env.convert_byte_array(password).ok()?;
    let mut salt = env.convert_byte_array(salt).ok()?;
    let result = derive_argon2id(&password, &salt, iterations, memory_kib, parallelism).ok();
    password.fill(0);
    salt.fill(0);
    let result = result?;
    let output = env.byte_array_from_slice(&result).ok()?;
    Some(output.into_raw())
}

fn positive_u32(value: jint) -> Option<u32> {
    (value > 0).then_some(value as u32)
}

#[cfg(test)]
mod tests {
    use super::positive_u32;

    #[test]
    fn rejects_non_positive_jni_work_factors() {
        assert_eq!(positive_u32(-1), None);
        assert_eq!(positive_u32(0), None);
        assert_eq!(positive_u32(1), Some(1));
    }
}
