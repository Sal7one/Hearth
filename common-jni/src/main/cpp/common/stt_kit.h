// SPDX-License-Identifier: Apache-2.0
//
// common/stt_kit.h — Public, stable C++ surface for the common-jni native
// module. Downstream consumers (other Android projects, desktop prototypes,
// unit tests) should depend ONLY on symbols re-exported through this header.
// Anything else under `common/` is considered implementation detail.
//
// Design rule: if a function is declared here, its ABI (argument order,
// semantics, error conventions) will not change within a major version.
//
// All functions are thread-safe unless explicitly documented otherwise.
// All audio is assumed to be 16-bit signed PCM, little-endian, mono unless
// an overload specifies otherwise.
//
// NOTE: This header is a *specification* of the stable public surface.
// Audio-math helpers are implemented in `stt_kit.cpp` (thin wrappers around
// the internal `AudioUtils` class). The engine handle API is reserved for
// future C-ABI bindings and currently has no implementation — it exists here
// so downstream code can program against a stable header while the
// implementation is still JNI-only.

#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace stt::kit {

// ============================================================================
// Version / capability
// ============================================================================

/// Semantic version of the native module (major * 10000 + minor * 100 + patch).
int version_code() noexcept;

/// Human-readable version string, e.g. "1.4.0-arm64-v8a".
const char* version_string() noexcept;

/// Bit flags for supported backends. Mirror of Kotlin CommonJni.Capability.
enum Capability : uint32_t {
    CAP_WHISPER = 1u << 0,
    CAP_VOSK    = 1u << 1,
    CAP_ONNX    = 1u << 2,
    CAP_FFMPEG  = 1u << 3,
    CAP_OPENCV  = 1u << 4,
};

/// Bitmask of compiled-in capabilities. Test with `mask & CAP_WHISPER`.
uint32_t capability_mask() noexcept;

// ============================================================================
// Audio math — reusable, NEON-accelerated helpers.
// These are thin wrappers around the internal audio_utils functions and have
// no global state, so they are safe to call from any thread.
// ============================================================================

/// Convert int16 PCM [-32768, 32767] to float [-1.0, 1.0].
/// `count` samples are written to `dst`. NEON-accelerated on arm64.
void int16_to_float(const int16_t* src, float* dst, size_t count) noexcept;

/// Inverse of [int16_to_float], with saturating clamp.
void float_to_int16(const float* src, int16_t* dst, size_t count) noexcept;

/// Linear RMS of an int16 PCM buffer, normalised to [0, 1] where 1 == full scale.
float rms_int16(const int16_t* samples, size_t count) noexcept;

/// Linear RMS of a float buffer in [-1, 1].
float rms_float(const float* samples, size_t count) noexcept;

/// Resample int16 mono PCM in-place into caller-owned buffer. Returns the
/// number of output samples written, or 0 on failure (e.g. dst too small).
/// `src_rate` and `dst_rate` must both be positive. If rates match, this is
/// a memcpy.
size_t resample_int16_mono(
    const int16_t* src, size_t src_count, int src_rate,
    int16_t* dst, size_t dst_capacity, int dst_rate
) noexcept;

// ============================================================================
// Engine handle — opaque session. One handle == one independent STT session.
// ============================================================================

/// Backend selection for [engine_create].
enum class Backend { Whisper, Vosk, Onnx };

/// Opaque engine handle. A value of 0 means "invalid / not created".
using EngineHandle = int64_t;

/// Create a new engine session. Returns 0 on failure (check [last_error]).
EngineHandle engine_create(Backend backend) noexcept;

/// Destroy an engine session. Safe to call with handle == 0.
void engine_destroy(EngineHandle handle) noexcept;

/// Initialise the engine with a model file. Returns true on success.
/// `language` may be empty for auto-detect; not all backends honour it.
bool engine_initialize(
    EngineHandle handle,
    const char* model_path,
    const char* language
) noexcept;

/// Push int16 PCM samples into the engine's streaming buffer.
/// Returns 0 on success, or a negative error code.
int engine_push_int16(
    EngineHandle handle,
    const int16_t* samples, size_t count,
    int sample_rate
) noexcept;

/// Fetch the current partial transcript. Writes UTF-8 into `out` up to
/// `out_capacity` bytes (NUL-terminated). Returns the number of bytes
/// actually needed (may be > out_capacity — caller should retry with larger
/// buffer). Returns 0 if there is no partial yet.
size_t engine_partial(
    EngineHandle handle,
    char* out, size_t out_capacity
) noexcept;

/// Finalise and retrieve the full transcript. Same buffer semantics as
/// [engine_partial]. After this call the engine is ready to accept a new
/// utterance (no need to destroy+recreate).
size_t engine_finalize(
    EngineHandle handle,
    char* out, size_t out_capacity
) noexcept;

/// Discard any pending audio and reset the engine's internal state.
void engine_reset(EngineHandle handle) noexcept;

// ============================================================================
// Diagnostics
// ============================================================================

/// Last error message on the calling thread. Returns "" if no error. The
/// returned pointer remains valid until the next API call on this thread.
const char* last_error() noexcept;

/// Clear the per-thread error slot.
void clear_error() noexcept;

}  // namespace stt::kit
