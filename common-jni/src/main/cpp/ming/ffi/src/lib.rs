//! A C ABI over `fusor-ai-ming` 0.1.2 (Ming-3.0-tts-0.5B).
//!
//! The Rust API this wraps is a plain library (`ming::session::Engine`): load a fusor-ai
//! bundle directory, `start` a request, call `step` once per 80 ms frame, drain
//! `take_audio`, write 16-bit mono WAV. Nothing here is WebAssembly-specific, so the same
//! crate is meant to build for `aarch64-apple-darwin` and `aarch64-linux-android`.
//!
//! Thread-safety: one `MingEngine` may be used by one thread at a time. The Rust side is
//! `Box`ed and the caller owns the pointer. Every entry point catches panics and reports
//! them through the error buffer instead of unwinding into C or JNI.
//!
//! This is experimental code, not a stable ABI. Bump `MING_ABI_VERSION` on every change.

use std::ffi::{c_char, c_int, CStr, CString};
use std::io::Write;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::{Path, PathBuf};
use std::time::Instant;

use bundle::{Manifest, Store};
use ming::preset::Preset;
use ming::prompt::Voice;
use ming::session::{Engine, Request};

/// Bump when the functions, structs or argument meanings below change.
pub const MING_ABI_VERSION: u32 = 1;

/// One synthesis's counters. All times in seconds, `rtf` is generation / audio duration.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct MingStats {
    pub frames: u64,
    pub samples: u64,
    pub audio_seconds: f64,
    pub elapsed_seconds: f64,
    pub rtf: f64,
    pub first_audio_seconds: f64,
    pub load_seconds: f64,
    pub threads: u32,
    pub sample_rate: u32,
}

pub struct MingEngine {
    engine: Engine,
    pending: Vec<f32>,
    all: Vec<f32>,
    frames: u64,
    first_audio: Option<f64>,
    started: Option<Instant>,
    load_seconds: f64,
    /// Set once a request was started, so `write_wav` can only write a finished run.
    started_once: bool,
}

fn err_buffer(buffer: *mut c_char, capacity: usize, message: &str) {
    if buffer.is_null() || capacity == 0 {
        return;
    }
    let bytes = message.as_bytes();
    let n = bytes.len().min(capacity - 1);
    // SAFETY: the caller promises `capacity` writable bytes at `buffer`.
    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), buffer.cast::<u8>(), n);
        *buffer.add(n) = 0;
    }
}

/// Runs `body`, reporting its error or a panic through `err`/`err_capacity`.
fn guard(err: *mut c_char, err_capacity: usize, body: impl FnOnce() -> Result<(), String>) -> c_int {
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(Ok(())) => 0,
        Ok(Err(message)) => {
            err_buffer(err, err_capacity, &message);
            -1
        }
        Err(_) => {
            err_buffer(err, err_capacity, "panic inside the Ming runtime");
            -2
        }
    }
}

/// A NUL-terminated UTF-8 path or string from the caller.
///
/// # Safety
/// `text` must be null or a valid NUL-terminated C string.
unsafe fn c_string(text: *const c_char, what: &str) -> Result<String, String> {
    if text.is_null() {
        return Err(format!("{what} is null"));
    }
    // SAFETY: the caller passes a NUL-terminated C string.
    let bytes = unsafe { CStr::from_ptr(text) }.to_bytes();
    std::str::from_utf8(bytes)
        .map(str::to_owned)
        .map_err(|_| format!("{what} is not valid UTF-8"))
}

fn load_bundle(directory: &Path) -> Result<Engine, String> {
    let manifest_path = directory.join("manifest.json");
    let json = std::fs::read_to_string(&manifest_path)
        .map_err(|error| format!("{}: {error}", manifest_path.display()))?;
    let manifest = Manifest::parse(&json).map_err(|error| format!("manifest: {error}"))?;
    let mut store = Store::new(manifest.clone()).map_err(|error| format!("store: {error}"))?;
    for (index, part) in manifest.parts.iter().enumerate() {
        let path = directory.join(&part.file);
        let bytes = std::fs::read(&path).map_err(|error| format!("{}: {error}", path.display()))?;
        for chunk in bytes.chunks(8 << 20) {
            store
                .write(index, chunk)
                .map_err(|error| format!("{}: {error}", part.file))?;
        }
        store
            .end_part(index)
            .map_err(|error| format!("{}: {error}", part.file))?;
    }
    Engine::new(&mut store).map_err(|error| format!("engine: {error}"))
}

fn preset_from_code(code: i32) -> Preset {
    match code {
        1 => Preset::Balanced,
        2 => Preset::Fast,
        _ => Preset::Quality,
    }
}

/// Starts the Rayon global pool with `threads` workers, if no pool exists yet. A host that
/// already built the pool keeps its own size.
fn configure_threads(threads: i32) {
    if threads <= 0 {
        return;
    }
    let _ = rayon::ThreadPoolBuilder::new()
        .num_threads(threads as usize)
        .build_global();
}

/// The ABI revision. `ming_abi_version() >= 1` is the only supported range today.
#[no_mangle]
pub extern "C" fn ming_abi_version() -> u32 {
    MING_ABI_VERSION
}

/// Loads and hashes a bundle directory, allocates the tensors and builds the engine.
///
/// `threads` <= 0 keeps the current Rayon pool (or its default). `out` receives the engine.
///
/// # Safety
/// `bundle_dir` is a NUL-terminated path, `out` points to one writable pointer, and
/// `err`/`err_capacity` describe an optional writable buffer.
#[no_mangle]
pub unsafe extern "C" fn ming_engine_load(
    bundle_dir: *const c_char,
    threads: i32,
    out: *mut *mut MingEngine,
    err: *mut c_char,
    err_capacity: usize,
) -> c_int {
    guard(err, err_capacity, || {
        if out.is_null() {
            return Err("out engine pointer is null".into());
        }
        // SAFETY: the caller promises a NUL-terminated path.
        let path = unsafe { c_string(bundle_dir, "bundle_dir") }?;
        configure_threads(threads);
        let start = Instant::now();
        let engine = load_bundle(Path::new(&path))?;
        let load_seconds = start.elapsed().as_secs_f64();
        let wrapper = Box::new(MingEngine {
            engine,
            pending: Vec::new(),
            all: Vec::new(),
            frames: 0,
            first_audio: None,
            started: None,
            load_seconds,
            started_once: false,
        });
        // SAFETY: `out` is writable by contract.
        unsafe { *out = Box::into_raw(wrapper) };
        Ok(())
    })
}

/// Frees an engine created by `ming_engine_load`. Null is ignored.
///
/// # Safety
/// `engine` must be null or an untouched pointer from `ming_engine_load`.
#[no_mangle]
pub unsafe extern "C" fn ming_engine_free(engine: *mut MingEngine) {
    if engine.is_null() {
        return;
    }
    // SAFETY: the pointer came from `Box::into_raw` and is freed once.
    drop(unsafe { Box::from_raw(engine) });
}

/// The number of compute threads the loaded engine actually uses.
///
/// # Safety
/// `engine` must be a live engine pointer.
#[no_mangle]
pub unsafe extern "C" fn ming_engine_threads(engine: *const MingEngine) -> u32 {
    if engine.is_null() {
        return 0;
    }
    // SAFETY: the caller keeps the engine alive for the call.
    unsafe { (*engine).engine.threads() as u32 }
}

/// Starts one synthesis. `voice` is a built-in preset such as `US_James`; null or empty
/// means `US_James`. `preset`: 0 quality, 1 balanced, 2 fast. `seed` 0 uses the upstream
/// default (1895).
///
/// # Safety
/// `engine` must be live; `text` and `voice` NUL-terminated or null; `err` optional.
#[no_mangle]
pub unsafe extern "C" fn ming_engine_start(
    engine: *mut MingEngine,
    text: *const c_char,
    voice: *const c_char,
    preset: i32,
    seed: u64,
    err: *mut c_char,
    err_capacity: usize,
) -> c_int {
    guard(err, err_capacity, || {
        if engine.is_null() {
            return Err("engine is null".into());
        }
        // SAFETY: the caller promises NUL-terminated strings.
        let text = unsafe { c_string(text, "text") }?;
        let voice = if voice.is_null() {
            String::new()
        } else {
            // SAFETY: same contract as above.
            unsafe { c_string(voice, "voice") }?
        };
        let voice = if voice.trim().is_empty() {
            Voice::Builtin("US_James".into())
        } else {
            Voice::Builtin(voice)
        };
        // SAFETY: the engine pointer is live for this call.
        let wrapper = unsafe { &mut *engine };
        let request = Request {
            text,
            voice,
            preset: preset_from_code(preset),
            seed: if seed == 0 { None } else { Some(seed) },
            language: None,
            max_frames: None,
        };
        wrapper
            .engine
            .start(request)
            .map_err(|error| format!("start: {error}"))?;
        wrapper.pending.clear();
        wrapper.all.clear();
        wrapper.frames = 0;
        wrapper.first_audio = None;
        wrapper.started = Some(Instant::now());
        wrapper.started_once = true;
        Ok(())
    })
}

/// Generates one frame (80 ms of audio). `done` is set to 1 on the last frame.
///
/// # Safety
/// `engine` must be live, `done` writable, `err` optional.
#[no_mangle]
pub unsafe extern "C" fn ming_engine_step(
    engine: *mut MingEngine,
    done: *mut c_int,
    err: *mut c_char,
    err_capacity: usize,
) -> c_int {
    guard(err, err_capacity, || {
        if engine.is_null() {
            return Err("engine is null".into());
        }
        // SAFETY: the caller keeps the engine alive for the call.
        let wrapper = unsafe { &mut *engine };
        let progress = wrapper
            .engine
            .step()
            .map_err(|error| format!("step: {error}"))?;
        wrapper.frames += 1;
        let audio = wrapper.engine.take_audio();
        if wrapper.first_audio.is_none() && !audio.is_empty() {
            wrapper.first_audio = wrapper
                .started
                .map(|start| start.elapsed().as_secs_f64());
        }
        wrapper.pending.extend_from_slice(&audio);
        wrapper.all.extend_from_slice(&audio);
        if !done.is_null() {
            // SAFETY: the caller promises a writable int.
            unsafe { *done = c_int::from(progress.done) };
        }
        Ok(())
    })
}

/// Bytes of audio waiting for `ming_engine_take_audio`.
///
/// # Safety
/// `engine` must be a live engine pointer.
#[no_mangle]
pub unsafe extern "C" fn ming_engine_pending(engine: *const MingEngine) -> usize {
    if engine.is_null() {
        return 0;
    }
    // SAFETY: the caller keeps the engine alive for the call.
    unsafe { (*engine).pending.len() }
}

/// Copies up to `capacity` pending f32 samples to `out` and removes them. With `out` null
/// (or `capacity` 0) it only reports the pending count. Returns the number copied.
///
/// # Safety
/// `out` must be null or point to `capacity` writable f32 values.
#[no_mangle]
pub unsafe extern "C" fn ming_engine_take_audio(
    engine: *mut MingEngine,
    out: *mut f32,
    capacity: usize,
) -> usize {
    if engine.is_null() {
        return 0;
    }
    // SAFETY: the caller keeps the engine alive for the call.
    let wrapper = unsafe { &mut *engine };
    if out.is_null() || capacity == 0 {
        return wrapper.pending.len();
    }
    let n = capacity.min(wrapper.pending.len());
    // SAFETY: `out` has room for `n` f32 values by contract; the regions do not overlap.
    unsafe { std::ptr::copy_nonoverlapping(wrapper.pending.as_ptr(), out, n) };
    wrapper.pending.drain(..n);
    n
}

/// Fills `stats` from the current run. Safe to call after the last `step`.
///
/// # Safety
/// `engine` must be live and `stats` writable.
#[no_mangle]
pub unsafe extern "C" fn ming_engine_stats(engine: *const MingEngine, stats: *mut MingStats) -> c_int {
    if engine.is_null() || stats.is_null() {
        return -1;
    }
    // SAFETY: the caller keeps the engine alive and `stats` writable.
    let wrapper = unsafe { &*engine };
    let samples = wrapper.all.len() as u64;
    let audio_seconds = samples as f64 / f64::from(ming::SAMPLE_RATE);
    let elapsed = wrapper
        .started
        .map_or(0.0, |start| start.elapsed().as_secs_f64());
    let out = MingStats {
        frames: wrapper.frames,
        samples,
        audio_seconds,
        elapsed_seconds: elapsed,
        rtf: if audio_seconds > 0.0 {
            elapsed / audio_seconds
        } else {
            0.0
        },
        first_audio_seconds: wrapper.first_audio.unwrap_or(elapsed),
        load_seconds: wrapper.load_seconds,
        threads: wrapper.engine.threads() as u32,
        sample_rate: ming::SAMPLE_RATE,
    };
    // SAFETY: `stats` is writable.
    unsafe { *stats = out };
    0
}

/// Writes everything generated so far as a 16-bit mono WAV.
///
/// # Safety
/// `engine` live; `path` NUL-terminated; `err` optional.
#[no_mangle]
pub unsafe extern "C" fn ming_engine_write_wav(
    engine: *const MingEngine,
    path: *const c_char,
    err: *mut c_char,
    err_capacity: usize,
) -> c_int {
    guard(err, err_capacity, || {
        if engine.is_null() {
            return Err("engine is null".into());
        }
        // SAFETY: the caller promises a NUL-terminated path.
        let path = unsafe { c_string(path, "wav path") }?;
        // SAFETY: the caller keeps the engine alive for the call.
        let wrapper = unsafe { &*engine };
        if !wrapper.started_once || wrapper.all.is_empty() {
            return Err("no audio has been generated".into());
        }
        let wav = ming::wav::encode(&wrapper.all, ming::SAMPLE_RATE);
        std::fs::write(&path, wav).map_err(|error| format!("{path}: {error}"))?;
        Ok(())
    })
}

/// Convenience one-shot: load, synthesize `text`, write a WAV, fill `stats`.
///
/// Blocks for the whole utterance. Returns 0 on success.
///
/// # Safety
/// Strings NUL-terminated or null; `stats` null or writable; `err` optional.
#[no_mangle]
pub unsafe extern "C" fn ming_synthesize_wav(
    bundle_dir: *const c_char,
    text: *const c_char,
    voice: *const c_char,
    preset: i32,
    seed: u64,
    threads: i32,
    out_wav: *const c_char,
    stats: *mut MingStats,
    err: *mut c_char,
    err_capacity: usize,
) -> c_int {
    guard(err, err_capacity, || {
        // SAFETY: the caller promises a NUL-terminated path.
        let directory = unsafe { c_string(bundle_dir, "bundle_dir") }?;
        // SAFETY: the caller promises a NUL-terminated path.
        let out_wav = unsafe { c_string(out_wav, "out_wav") }?;
        configure_threads(threads);
        let load_start = Instant::now();
        let mut engine = load_bundle(Path::new(&directory))?;
        let load_seconds = load_start.elapsed().as_secs_f64();
        // SAFETY: the caller promises NUL-terminated strings.
        let text = unsafe { c_string(text, "text") }?;
        let voice = if voice.is_null() {
            "US_James".to_owned()
        } else {
            // SAFETY: same contract.
            unsafe { c_string(voice, "voice") }?
        };
        let request = Request {
            text,
            voice: Voice::Builtin(voice),
            preset: preset_from_code(preset),
            seed: if seed == 0 { None } else { Some(seed) },
            language: None,
            max_frames: None,
        };
        let start = Instant::now();
        engine
            .start(request)
            .map_err(|error| format!("start: {error}"))?;
        let mut all = Vec::new();
        let mut frames = 0_u64;
        let mut first_audio = None;
        loop {
            let progress = engine.step().map_err(|error| format!("step: {error}"))?;
            frames += 1;
            let audio = engine.take_audio();
            if first_audio.is_none() && !audio.is_empty() {
                first_audio = Some(start.elapsed().as_secs_f64());
            }
            all.extend_from_slice(&audio);
            if progress.done {
                break;
            }
        }
        let elapsed = start.elapsed().as_secs_f64();
        let audio_seconds = all.len() as f64 / f64::from(ming::SAMPLE_RATE);
        let wav = ming::wav::encode(&all, ming::SAMPLE_RATE);
        std::fs::write(&out_wav, wav).map_err(|error| format!("{out_wav}: {error}"))?;
        if !stats.is_null() {
            // SAFETY: `stats` is writable by contract.
            unsafe {
                *stats = MingStats {
                    frames,
                    samples: all.len() as u64,
                    audio_seconds,
                    elapsed_seconds: elapsed,
                    rtf: if audio_seconds > 0.0 {
                        elapsed / audio_seconds
                    } else {
                        0.0
                    },
                    first_audio_seconds: first_audio.unwrap_or(elapsed),
                    load_seconds,
                    threads: engine.threads() as u32,
                    sample_rate: ming::SAMPLE_RATE,
                }
            };
        }
        Ok(())
    })
}

/// Writes the same stats as one JSON line into `buffer` (for logging from C/JNI).
///
/// # Safety
/// `buffer` writable for `capacity` bytes.
#[no_mangle]
pub unsafe extern "C" fn ming_stats_json(
    stats: *const MingStats,
    buffer: *mut c_char,
    capacity: usize,
) -> c_int {
    if stats.is_null() || buffer.is_null() || capacity == 0 {
        return -1;
    }
    // SAFETY: the caller promises readable/writable pointers.
    let s = unsafe { &*stats };
    let json = format!(
        "{{\"frames\":{},\"samples\":{},\"audio_seconds\":{:.3},\"elapsed_seconds\":{:.3},\"rtf\":{:.3},\"first_audio_seconds\":{:.3},\"load_seconds\":{:.3},\"threads\":{},\"sample_rate\":{}}}",
        s.frames, s.samples, s.audio_seconds, s.elapsed_seconds, s.rtf, s.first_audio_seconds, s.load_seconds, s.threads, s.sample_rate
    );
    let Ok(c) = CString::new(json) else {
        return -1;
    };
    let bytes = c.as_bytes_with_nul();
    let n = bytes.len().min(capacity);
    // SAFETY: `buffer` has `capacity` writable bytes.
    unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), buffer.cast::<u8>(), n) };
    if n == capacity {
        // SAFETY: last byte inside the buffer; keep it NUL-terminated.
        unsafe { *buffer.add(capacity - 1) = 0 };
    }
    0
}

/// A short human-readable name for the loaded model, for diagnostics.
#[no_mangle]
pub extern "C" fn ming_model_name() -> *const c_char {
    c"ming-3.0-tts-0.5b".as_ptr()
}

/// Unused path helper kept private: lets the compiler check `PathBuf` imports are live.
#[allow(dead_code)]
fn manifest_path(directory: &Path) -> PathBuf {
    directory.join("manifest.json")
}

/// Flushes an error to stderr on non-Android native builds; JNI catches it instead.
#[allow(dead_code)]
fn trace(message: &str) {
    let _ = writeln!(std::io::stderr(), "{message}");
}
