//! Minimal JNI probe over the Ming C ABI.
//!
//! Kotlin side (not committed to the app; this is the shape a wrapper would use):
//!
//! ```kotlin
//! package com.sal7one.common_jni
//! object MingNative {
//!     init { System.loadLibrary("ming_jni") }
//!     external fun abiVersion(): Int
//!     external fun modelName(): String
//!     /** Blocking; returns a JSON stats line. Call from a worker thread. */
//!     external fun synthesizeWav(
//!         bundleDir: String, text: String, voice: String, outWav: String,
//!         preset: Int, seed: Long, threads: Int,
//!     ): String
//! }
//! ```
//!
//! The C ABI (`libming_ffi`) is shared with the macOS desktop path; this crate only adapts
//! Java strings and exceptions. A production wrapper should stream with `ming_engine_step`
//! from a worker thread instead of blocking a JNI call for the whole utterance.

use std::ffi::CString;

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;

fn to_cstring(env: &mut JNIEnv<'_>, value: &JString<'_>, what: &str) -> Result<CString, String> {
    let text: String = env
        .get_string(value)
        .map_err(|error| format!("{what}: {error}"))?
        .into();
    CString::new(text).map_err(|_| format!("{what} contains a NUL byte"))
}

fn throw(env: &mut JNIEnv<'_>, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

#[no_mangle]
pub extern "system" fn Java_com_sal7one_common_1jni_MingNative_abiVersion(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    ming_ffi::MING_ABI_VERSION as jint
}

#[no_mangle]
pub extern "system" fn Java_com_sal7one_common_1jni_MingNative_modelName<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let text = "ming-3.0-tts-0.5b";
    match env.new_string(text) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Loads a bundle, synthesizes one utterance to a WAV and returns a JSON stats line.
/// Blocks for the whole utterance; call from a background thread.
///
/// # Safety
/// The JNI caller owns the `JString` references for the duration of the call.
#[no_mangle]
pub unsafe extern "system" fn Java_com_sal7one_common_1jni_MingNative_synthesizeWav<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    bundle_dir: JString<'local>,
    text: JString<'local>,
    voice: JString<'local>,
    out_wav: JString<'local>,
    preset: jint,
    seed: jlong,
    threads: jint,
) -> jstring {
    let strings = (|| {
        let bundle = to_cstring(&mut env, &bundle_dir, "bundleDir")?;
        let text = to_cstring(&mut env, &text, "text")?;
        let voice = to_cstring(&mut env, &voice, "voice")?;
        let out = to_cstring(&mut env, &out_wav, "outWav")?;
        Ok::<_, String>((bundle, text, voice, out))
    })();
    let (bundle, text, voice, out) = match strings {
        Ok(values) => values,
        Err(message) => {
            throw(&mut env, &message);
            return std::ptr::null_mut();
        }
    };

    let mut stats = ming_ffi::MingStats::default();
    let mut err = [0 as std::ffi::c_char; 2048];
    // SAFETY: every pointer is a live CString or a local buffer; the C ABI copies what it needs.
    let rc = unsafe {
        ming_ffi::ming_synthesize_wav(
            bundle.as_ptr(),
            text.as_ptr(),
            voice.as_ptr(),
            preset,
            seed as u64,
            threads,
            out.as_ptr(),
            &mut stats,
            err.as_mut_ptr(),
            err.len(),
        )
    };
    if rc != 0 {
        let message = unsafe { std::ffi::CStr::from_ptr(err.as_ptr()) }
            .to_string_lossy()
            .into_owned();
        throw(&mut env, &format!("ming_synthesize_wav({rc}): {message}"));
        return std::ptr::null_mut();
    }

    let mut json = [0 as std::ffi::c_char; 1024];
    // SAFETY: `stats` is initialized and `json` is a local writable buffer.
    let json_rc = unsafe { ming_ffi::ming_stats_json(&stats, json.as_mut_ptr(), json.len()) };
    if json_rc != 0 {
        throw(&mut env, "ming_stats_json failed");
        return std::ptr::null_mut();
    }
    let json = unsafe { std::ffi::CStr::from_ptr(json.as_ptr()) }
        .to_string_lossy()
        .into_owned();
    match env.new_string(json) {
        Ok(value) => value.into_raw(),
        Err(error) => {
            throw(&mut env, &format!("new_string: {error}"));
            std::ptr::null_mut()
        }
    }
}
