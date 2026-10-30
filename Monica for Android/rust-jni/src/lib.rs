#![allow(non_snake_case)]

mod autofill;
mod list_sort;
mod password_grouping;
mod search;
mod vault_overview;
mod vault_picker;
mod wallet_stack;

use jni::objects::{JByteArray, JClass, JIntArray, JLongArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jintArray, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use monica_rust_crypto::{
    derive_argon2id, derive_keepass_aes, derive_keepass_argon2, derive_pbkdf2_sha256,
};
use search::{filter_metadata_batch, SearchQuery};
use zeroize::Zeroizing;

const RUST_CORE_VERSION: &str = "monica-rust-jni/0.5.0-kdf";

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_lansync_RustLanSync_nativeRequest(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let request: String = match env.get_string(&input) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    env.new_string(monica_lan_sync::request_json(&request))
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustPasswordGroupingCore_nativeProject(
    env: JNIEnv,
    _class: JClass,
    metadata: JIntArray,
) -> jintArray {
    (|| {
        let len = usize::try_from(env.get_array_length(&metadata).ok()?).ok()?;
        if !(password_grouping::HEADER..=password_grouping::MAX_BATCH_LEN).contains(&len) {
            return None;
        }
        let mut batch = Vec::new();
        batch.try_reserve_exact(len).ok()?;
        batch.resize(len, 0_i32);
        env.get_int_array_region(&metadata, 0, &mut batch).ok()?;
        let projection = password_grouping::project(&batch)?;
        let output = env
            .new_int_array(i32::try_from(projection.len()).ok()?)
            .ok()?;
        env.set_int_array_region(&output, 0, &projection).ok()?;
        Some(output.into_raw())
    })()
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustAutofillCore_nativeOpen(
    env: JNIEnv,
    _class: JClass,
    metadata: JByteArray,
) -> jlong {
    (|| {
        let len = usize::try_from(env.get_array_length(&metadata).ok()?).ok()?;
        if !(8..=autofill::MAX_BYTES).contains(&len) {
            return None;
        }
        autofill::open(&env.convert_byte_array(metadata).ok()?)
    })()
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustAutofillCore_nativeQuery(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    package: JString,
    host: JString,
    root: JString,
    label: JString,
) -> jintArray {
    (|| {
        let package: String = env.get_string(&package).ok()?.into();
        let host: String = env.get_string(&host).ok()?.into();
        let root: String = env.get_string(&root).ok()?.into();
        let label: String = env.get_string(&label).ok()?.into();
        let indices = autofill::query(handle, &package, &host, &root, &label)?;
        let output = env.new_int_array(i32::try_from(indices.len()).ok()?).ok()?;
        env.set_int_array_region(&output, 0, &indices).ok()?;
        Some(output.into_raw())
    })()
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustAutofillCore_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    autofill::close(handle);
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustVaultPickerCore_nativeOpen(
    env: JNIEnv,
    _class: JClass,
    metadata: JByteArray,
) -> jlong {
    let result = (|| {
        let len = usize::try_from(env.get_array_length(&metadata).ok()?).ok()?;
        if !(8..=vault_picker::MAX_BYTES).contains(&len) {
            return None;
        }
        vault_picker::open(&env.convert_byte_array(metadata).ok()?)
    })();
    result.unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustVaultPickerCore_nativeFilter(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    query: JString,
    source: jint,
) -> jintArray {
    let result = (|| {
        let query: String = env.get_string(&query).ok()?.into();
        let indices = vault_picker::filter(handle, &query, source)?;
        let output = env.new_int_array(i32::try_from(indices.len()).ok()?).ok()?;
        env.set_int_array_region(&output, 0, &indices).ok()?;
        Some(output.into_raw())
    })();
    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustVaultPickerCore_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    vault_picker::close(handle);
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustVaultOverviewCore_nativeProject(
    env: JNIEnv,
    _class: JClass,
    metadata: JLongArray,
) -> jintArray {
    let result = (|| {
        let len = usize::try_from(env.get_array_length(&metadata).ok()?).ok()?;
        if !(vault_overview::HEADER..=vault_overview::MAX_BATCH_LEN).contains(&len) {
            return None;
        }
        let mut batch = vec![0_i64; len];
        env.get_long_array_region(&metadata, 0, &mut batch).ok()?;
        let projection = vault_overview::project(&batch)?;
        let output = env
            .new_int_array(i32::try_from(projection.len()).ok()?)
            .ok()?;
        env.set_int_array_region(&output, 0, &projection).ok()?;
        Some(output.into_raw())
    })();
    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustWalletStackCore_nativeProjectIndices(
    env: JNIEnv,
    _class: JClass,
    metadata: JLongArray,
    selection: jboolean,
) -> jintArray {
    let result = (|| {
        let len = env.get_array_length(&metadata).ok()? as usize;
        let mut batch = vec![0_i64; len];
        env.get_long_array_region(&metadata, 0, &mut batch).ok()?;
        let indices = wallet_stack::project_indices(&batch, selection != JNI_FALSE)?;
        let output = env.new_int_array(indices.len() as i32).ok()?;
        env.set_int_array_region(&output, 0, &indices).ok()?;
        Some(output.into_raw())
    })();
    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustListSortCore_nativeSortIndices(
    env: JNIEnv,
    _class: JClass,
    metadata: JLongArray,
    tie_by_id: jboolean,
) -> jintArray {
    let result = (|| {
        let len = env.get_array_length(&metadata).ok()? as usize;
        let mut batch = vec![0_i64; len];
        env.get_long_array_region(&metadata, 0, &mut batch).ok()?;
        let indices = list_sort::sort_indices(&batch, tie_by_id != JNI_FALSE)?;
        let output = env.new_int_array(indices.len() as i32).ok()?;
        env.set_int_array_region(&output, 0, &indices).ok()?;
        Some(output.into_raw())
    })();
    result.unwrap_or(std::ptr::null_mut())
}

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

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustKeePassKdfCore_nativeDerive(
    env: JNIEnv,
    _class: JClass,
    key: JByteArray,
    salt: JByteArray,
    iterations: jint,
    memory_kib: jint,
    parallelism: jint,
    variant: jint,
    version: jint,
) -> jbyteArray {
    let derive = || -> Option<jbyteArray> {
        let key = Zeroizing::new(env.convert_byte_array(&key).ok()?);
        let salt = Zeroizing::new(env.convert_byte_array(&salt).ok()?);
        let result = Zeroizing::new(
            derive_keepass_argon2(
                &key,
                &salt,
                positive_u32(iterations)?,
                positive_u32(memory_kib)?,
                positive_u32(parallelism)?,
                variant as u32,
                version as u32,
            )
            .ok()?,
        );
        Some(env.byte_array_from_slice(&result[..]).ok()?.into_raw())
    };
    // Match the existing fallible JNI boundary; failed derivation falls back in Kotlin.
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(derive))
        .ok()
        .flatten()
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_rustcore_RustKeePassKdfCore_nativeDeriveAes(
    env: JNIEnv,
    _class: JClass,
    key: JByteArray,
    seed: JByteArray,
    rounds: jlong,
) -> jbyteArray {
    let derive = || -> Option<jbyteArray> {
        if rounds < 0 {
            return None;
        }
        let key = Zeroizing::new(env.convert_byte_array(&key).ok()?);
        let seed = Zeroizing::new(env.convert_byte_array(&seed).ok()?);
        let result = Zeroizing::new(derive_keepass_aes(&key, &seed, rounds as u64).ok()?);
        Some(env.byte_array_from_slice(&result[..]).ok()?.into_raw())
    };
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(derive))
        .ok()
        .flatten()
        .unwrap_or(std::ptr::null_mut())
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
