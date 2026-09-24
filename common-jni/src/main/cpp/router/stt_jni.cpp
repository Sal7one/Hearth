#include <jni.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "engine_router.h"
#include "engine_interface.h"
#include "logging.h"
#include "error_codes.h"
#include "stt_config.h"
#include "audio_utils.h"
#include "pcm_buffer_range.h"
#include "jni_utils.h"  // For jstringToString, CriticalArrayGuard, etc.
#include "jni/jni_helper.h"  // For global JVM reference
#include "jni/native_registry.h"
#include "json_utils.h"

#if WITH_WHISPER
#include "whisper/whisper_engine.h"
#endif

#if WITH_FFMPEG
#include "ffmpeg/ffmpeg_pipeline.h"
#endif

using namespace stt;

namespace {
// Note: jstringToString, CriticalArrayGuard, ShortArrayGuard, FloatArrayGuard
// are now in jni_utils.h

EngineType intToEngineType(int type) {
    switch (type) {
        case 1: return EngineType::WHISPER;
        case 2: return EngineType::VOSK;
        case 4: return EngineType::ONNX;
        default: return EngineType::WHISPER;
    }
}

// Thread-local scratch buffers for the audio hot path.
// Reused across calls so streaming STT does NOT allocate per-chunk.
// Each JNI-calling thread has its own pair (int16->float, resample out).
thread_local std::vector<float> t_floatScratch;
thread_local std::vector<int16_t> t_i16Scratch;

// Ensure scratch has at least `n` capacity without shrinking.
inline void ensureScratch(std::vector<float>& buf, size_t n) {
    if (buf.size() < n) buf.resize(n);
}

inline void ensureScratch(std::vector<int16_t>& buf, size_t n) {
    if (buf.size() < n) buf.resize(n);
}

// Validate a caller-supplied sample count against the real array length.
// `count` is clamped into [0, arrayLen]; returns false for null arrays or
// non-positive counts. Without this, a count larger than the array feeds the
// engines an out-of-bounds native read.
inline bool clampSampleCount(JNIEnv* env, jshortArray array, jint& count) {
    if (!array || count <= 0) return false;
    const jsize arrayLen = env->GetArrayLength(array);
    if (count > arrayLen) {
        LOG_W("JNI", "Sample count %d exceeds array length %d; clamped", count, arrayLen);
        count = arrayLen;
    }
    return count > 0;
}

inline bool clampSampleCount(JNIEnv* env, jfloatArray array, jint& count) {
    if (!array || count <= 0) return false;
    const jsize arrayLen = env->GetArrayLength(array);
    if (count > arrayLen) {
        LOG_W("JNI", "Sample count %d exceeds array length %d; clamped", count, arrayLen);
        count = arrayLen;
    }
    return count > 0;
}

// Copy `count` samples out of a critical-array guard into thread-local
// scratch and release the guard. The engines run full model inference and may
// block; a GetPrimitiveArrayCritical region must never be held across that
// (it disables moving GC for the duration).
inline const int16_t* copyOutAndRelease(
    ShortArrayGuard& guard, jint count
) {
    ensureScratch(t_i16Scratch, static_cast<size_t>(count));
    std::memcpy(t_i16Scratch.data(), guard.get(), static_cast<size_t>(count) * sizeof(int16_t));
    guard.release();
    return t_i16Scratch.data();
}

#if WITH_FFMPEG
using StreamReaderRegistry =
    jni::NativeRegistry<FfmpegStreamReader, jni::NativeHandleKind::FfmpegStream>;

StreamReaderRegistry& streamReaderRegistry() {
    static StreamReaderRegistry registry;
    return registry;
}
#endif

} // anonymous namespace

extern "C" {

// =============================================================================
// Capability Query
// =============================================================================

JNIEXPORT jint JNICALL
Java_com_sal7one_common_1jni_engine_SttEngineFactoryImpl_nativeGetCapabilityMask(
    JNIEnv*, jclass
) {
    return EngineRouter::getInstance().getCapabilityMask();
}

// =============================================================================
// Whisper Engine
// =============================================================================

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeIsWhisperAvailable(
    JNIEnv*, jclass
) {
#if WITH_WHISPER
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeGetWhisperVersion(
    JNIEnv* env, jclass
) {
#if WITH_WHISPER
    return env->NewStringUTF("whisper.cpp");
#else
    return env->NewStringUTF("not available");
#endif
}

JNIEXPORT jlong JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeCreateWhisper(
    JNIEnv*, jobject
) {
    return EngineRouter::getInstance().createEngine(EngineType::WHISPER);
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeDestroyWhisper(
    JNIEnv*, jobject, jlong handle
) {
    EngineRouter::getInstance().destroyEngine(handle, EngineType::WHISPER);
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeInitWhisper(
    JNIEnv* env, jobject, jlong handle, jstring modelPath, jstring configJson
) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::WHISPER);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid Whisper handle");
        return JNI_FALSE;
    }

    JNI_NULL_CHECK(env, modelPath, "modelPath cannot be null", JNI_FALSE);
    JNI_NULL_CHECK(env, configJson, "configJson cannot be null", JNI_FALSE);
    jni::JStringGuard modelPathGuard(env, modelPath);
    jni::JStringGuard configJsonGuard(env, configJson);
    if (!modelPathGuard.valid() || !configJsonGuard.valid()) {
        jni::throwIllegalArgumentException(env, "Invalid UTF-16 in model path or STT config");
        return JNI_FALSE;
    }

    EngineConfig config;
    std::string configError;
    if (!parseConfig(configJsonGuard.str(), config, &configError)) {
        jni::throwIllegalArgumentException(env, configError.c_str());
        return JNI_FALSE;
    }
    config.modelPath = modelPathGuard.str();
    
    return engine->initialize(config) ? JNI_TRUE : JNI_FALSE;

    JNI_TRY_CATCH_END(env, JNI_FALSE)
}

JNIEXPORT jint JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativePushAudioWhisper(
    JNIEnv* env, jobject, jlong handle, jshortArray samples, jint count, jint sampleRate
) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::WHISPER);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid Whisper handle");
        return -1;
    }
    if (!clampSampleCount(env, samples, count)) {
        jni::throwIllegalArgumentException(
            env, "samples array is required with a positive in-range count");
        return -1;
    }

    // Preserve the caller's rate: the engine owns the stream's resampler
    // phase. Copy out before the engine call so JNI does not pin the GC.
    {
        ShortArrayGuard guard(env, samples);
        if (!guard) {
            jni::throwRuntimeException(env, "Failed to get Whisper audio array");
            return -1;
        }
        copyOutAndRelease(guard, count);
    }
    return engine->pushAudio(t_i16Scratch.data(), count, sampleRate);

    JNI_TRY_CATCH_END(env, -1)
}

JNIEXPORT jint JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativePushAudioFloatWhisper(
    JNIEnv* env, jobject, jlong handle, jfloatArray samples, jint count, jint sampleRate
) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::WHISPER);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid Whisper handle");
        return -1;
    }
    if (!clampSampleCount(env, samples, count)) {
        jni::throwIllegalArgumentException(
            env, "samples array is required with a positive in-range count");
        return -1;
    }

    // Preserve the caller's rate for the engine-owned streaming resampler.
    {
        FloatArrayGuard guard(env, samples);
        if (!guard) {
            jni::throwRuntimeException(env, "Failed to get Whisper float audio array");
            return -1;
        }
        ensureScratch(t_floatScratch, static_cast<size_t>(count));
        std::memcpy(t_floatScratch.data(), guard.get(),
                    static_cast<size_t>(count) * sizeof(float));
    }
    return engine->pushAudioFloat(t_floatScratch.data(), count, sampleRate);

    JNI_TRY_CATCH_END(env, -1)
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeGetPartialWhisper(
    JNIEnv* env, jobject, jlong handle
) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::WHISPER);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid Whisper handle");
        return nullptr;
    }

    // Plain transcript text, not Group C JSON: convert as real UTF-8.
    std::string result = engine->getPartial();
    return result.empty() ? nullptr : jni::utf8ToJString(env, result);

    JNI_TRY_CATCH_END(env, nullptr)
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeFinalizeWhisper(
    JNIEnv* env, jobject, jlong handle
) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::WHISPER);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid Whisper handle");
        return nullptr;
    }

    std::string result = engine->finalize();
    return result.empty() ? nullptr : jni::utf8ToJString(env, result);

    JNI_TRY_CATCH_END(env, nullptr)
}

// =============================================================================
// Zero-copy DirectByteBuffer push (Whisper)
// -----------------------------------------------------------------------------
// This is the preferred hot-path API for realtime streaming.
//
// The Kotlin side passes a Direct ByteBuffer whose bytes are interpreted as
// native-endian 16-bit PCM samples. We obtain the raw backing pointer with
// GetDirectBufferAddress — no JNI copy, no critical section, no GC interaction.
//
// `byteOffset` and `byteCount` are in BYTES (not samples). sampleCount =
// byteCount / 2. The caller is responsible for keeping the ByteBuffer alive
// for the duration of the call.
// =============================================================================
JNIEXPORT jint JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativePushAudioDirectWhisper(
    JNIEnv* env, jobject, jlong handle,
    jobject directBuffer, jint byteOffset, jint byteCount, jint sampleRate
) {
    JNI_TRY_CATCH_BEGIN
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::WHISPER);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid Whisper handle");
        return -1;
    }

    if (!directBuffer) {
        jni::throwIllegalArgumentException(env, "null direct buffer");
        return -1;
    }
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    if (!base) {
        jni::throwIllegalArgumentException(env, "Not a direct buffer");
        return -1;
    }

    const jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    if (const char* error = pcmBufferRangeError(capacity, byteOffset, byteCount, sampleRate)) {
        jni::throwIllegalArgumentException(env, error);
        return -1;
    }

    const auto* samples = reinterpret_cast<const int16_t*>(base + byteOffset);
    const int sampleCount = byteCount / 2;

    return engine->pushAudio(samples, sampleCount, sampleRate);
    JNI_TRY_CATCH_END(env, -1)
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeResetWhisper(
    JNIEnv* env, jobject, jlong handle
) {
    // Reset joins and restarts the streaming thread; report a rejected reset
    // to Kotlin instead of treating the old audio as successfully cleared.
    JNI_TRY_CATCH_BEGIN
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::WHISPER);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid Whisper handle");
        return JNI_FALSE;
    }
#if WITH_WHISPER
    return static_cast<WhisperEngine*>(engine.operator->())->resetChecked()
        ? JNI_TRUE : JNI_FALSE;
#else
    jni::throwIllegalStateException(env, "Whisper not compiled");
    return JNI_FALSE;
#endif
    JNI_TRY_CATCH_END(env, JNI_FALSE)
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeTranscribeBatchWhisper(
    JNIEnv* env, jobject thiz, jlong handle, jshortArray samples, jint count, jint sampleRate
) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::WHISPER);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid Whisper handle");
        return nullptr;
    }
    if (!clampSampleCount(env, samples, count)) {
        jni::throwIllegalArgumentException(
            env, "samples array is required with a positive in-range count");
        return nullptr;
    }

    LOG_I("JNI", "TranscribeBatch: handle=%ld, samples=%d, sampleRate=%d", handle, count, sampleRate);

    // Copy out of the critical section before batch inference: whisper_full
    // can run for minutes and must not pin the GC for that long.
    {
        ShortArrayGuard guard(env, samples);
        if (!guard) {
            jni::throwRuntimeException(env, "Failed to get Whisper audio array");
            return nullptr;
        }

#ifndef NDEBUG
        // Debug-only: scan + log sample stats. In release builds this is completely removed.
        {
            std::string sampleDebug = "First 10 samples in JNI: ";
            for (int i = 0; i < std::min(10, count); ++i) {
                sampleDebug += std::to_string(guard.get()[i]) + " ";
            }
            LOG_I("JNI", "%s", sampleDebug.c_str());

            int nonZeroCount = 0;
            int16_t maxSample = 0;
            for (int i = 0; i < std::min(100, count); ++i) {
                if (guard.get()[i] != 0) nonZeroCount++;
                maxSample = std::max(maxSample, static_cast<int16_t>(std::abs(guard.get()[i])));
            }
            LOG_I("JNI", "Audio validation: %d/%d non-zero, max=%d",
                  nonZeroCount, std::min(100, count), maxSample);

            if (nonZeroCount == 0 && maxSample == 0) {
                LOG_E("JNI", "CRITICAL: All samples are zero - audio corruption detected!");
                guard.release();
                jni::throwIllegalArgumentException(
                    env, "Audio data appears corrupted (all zeros)");
                return nullptr;
            }
            if (maxSample < 10) {
                LOG_W("JNI", "Audio is very quiet (max=%d) but proceeding", maxSample);
            }
        }
#endif

        copyOutAndRelease(guard, count);
    } // Guard is released here - safe to call JNI functions now

    const std::string result = engine->transcribeBatch(
        t_i16Scratch.data(), count, sampleRate);
    LOG_I("JNI", "TranscribeBatch result length: %zu", result.length());
    return result.empty() ? nullptr : jni::utf8ToJString(env, result);

    JNI_TRY_CATCH_END(env, nullptr)
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeGetLastErrorWhisper(
    JNIEnv* env, jobject, jlong handle
) {
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::WHISPER);
    std::string error = engine ? engine->getLastError() : GET_ERROR();
    return jni::utf8ToJString(env, error);
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeIsProcessingWhisper(
    JNIEnv*, jobject, jlong handle
) {
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::WHISPER);
    return engine && engine->isProcessing() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeGetModelTypeWhisper(
    JNIEnv*, jobject, jlong handle
) {
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::WHISPER);
    return engine ? engine->getModelType() : -1;
}

// NEW: Cancellation support (P0 from FFmpeg-Kit analysis)
JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeCancelWhisper(
    JNIEnv*, jobject, jlong handle
) {
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::WHISPER);
    if (engine) {
        LOG_I("JNI", "Cancellation requested for handle %ld", handle);
        engine->cancel();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeIsCancelledWhisper(
    JNIEnv*, jobject, jlong handle
) {
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::WHISPER);
    return engine && engine->isCancelled() ? JNI_TRUE : JNI_FALSE;
}

// Language detection - uses Whisper's dedicated LID-only function (no full transcription)
// Returns detected language code (e.g., "en", "ar") or null on error
JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_engine_whisper_WhisperEngine_nativeDetectLanguage(
    JNIEnv* env, jobject, jlong handle, jshortArray samples, jint count, jint sampleRate
) {
#if WITH_WHISPER
    // Validate handle - we know this is a Whisper handle from the JNI method name
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::WHISPER);
    if (!engine) {
        LOG_E("JNI", "nativeDetectLanguage: invalid handle %ld", handle);
        return nullptr;  // null = error (vs empty string = no detection)
    }
    
    // Type check - should always succeed for Whisper handle
    auto* whisperEngine = dynamic_cast<stt::WhisperEngine*>(engine.operator->());
    if (!whisperEngine) {
        LOG_E("JNI", "nativeDetectLanguage: handle %ld is not a WhisperEngine", handle);
        return nullptr;
    }
    
    // Validate array
    if (!samples) {
        LOG_E("JNI", "nativeDetectLanguage: null samples array");
        return nullptr;
    }
    
    // Validate count against actual array length
    jsize arrayLen = env->GetArrayLength(samples);
    if (count <= 0 || count > arrayLen) {
        LOG_W("JNI", "nativeDetectLanguage: count %d adjusted to array length %d", count, arrayLen);
        count = arrayLen;
    }
    
    if (count == 0) {
        return nullptr;
    }
    
    // Consistent with rest of file: use GetPrimitiveArrayCritical via RAII guard.
    // detectLanguageOnly is synchronous and does not call JNI from inside, so it's
    // safe to hold the critical section across the call.
    std::string lang;
    {
        ShortArrayGuard guard(env, samples);
        if (!guard) {
            LOG_E("JNI", "nativeDetectLanguage: failed to get array elements");
            return nullptr;
        }
        lang = whisperEngine->detectLanguageOnly(guard.get(), count, sampleRate);
    }

    if (lang.empty()) {
        // Detection failed or no language detected
        return nullptr;
    }

    return jni::utf8ToJString(env, lang);
#else
    return nullptr;
#endif
}

// =============================================================================
// Vosk Engine (same pattern)
// =============================================================================

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_engine_vosk_VoskEngine_nativeIsVoskAvailable(JNIEnv*, jclass) {
#if WITH_VOSK
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_sal7one_common_1jni_engine_vosk_VoskEngine_nativeCreateVosk(JNIEnv*, jobject) {
    return EngineRouter::getInstance().createEngine(EngineType::VOSK);
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_engine_vosk_VoskEngine_nativeDestroyVosk(JNIEnv*, jobject, jlong handle) {
    EngineRouter::getInstance().destroyEngine(handle, EngineType::VOSK);
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_engine_vosk_VoskEngine_nativeInitVosk(
    JNIEnv* env, jobject, jlong handle, jstring modelPath, jstring configJson
) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::VOSK);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid Vosk handle");
        return JNI_FALSE;
    }

    JNI_NULL_CHECK(env, modelPath, "modelPath cannot be null", JNI_FALSE);
    JNI_NULL_CHECK(env, configJson, "configJson cannot be null", JNI_FALSE);
    jni::JStringGuard modelPathGuard(env, modelPath);
    jni::JStringGuard configJsonGuard(env, configJson);
    if (!modelPathGuard.valid() || !configJsonGuard.valid()) {
        jni::throwIllegalArgumentException(env, "Invalid UTF-16 in model path or STT config");
        return JNI_FALSE;
    }

    EngineConfig config;
    std::string configError;
    if (!parseConfig(configJsonGuard.str(), config, &configError)) {
        jni::throwIllegalArgumentException(env, configError.c_str());
        return JNI_FALSE;
    }
    config.modelPath = modelPathGuard.str();
    
    return engine->initialize(config) ? JNI_TRUE : JNI_FALSE;

    JNI_TRY_CATCH_END(env, JNI_FALSE)
}

JNIEXPORT jint JNICALL
Java_com_sal7one_common_1jni_engine_vosk_VoskEngine_nativePushAudioVosk(
    JNIEnv* env, jobject, jlong handle, jshortArray samples, jint count, jint sampleRate
) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::VOSK);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid Vosk handle");
        return -1;
    }
    if (!clampSampleCount(env, samples, count)) {
        jni::throwIllegalArgumentException(
            env, "samples array is required with a positive in-range count");
        return -1;
    }

    // Copy out of the critical section: the recognizer runs inference while
    // accepting waveforms.
    {
        ShortArrayGuard guard(env, samples);
        if (!guard) {
            jni::throwRuntimeException(env, "Failed to get Vosk audio array");
            return -1;
        }
        copyOutAndRelease(guard, count);
    }
    return engine->pushAudio(t_i16Scratch.data(), count, sampleRate);

    JNI_TRY_CATCH_END(env, -1)
}

// Zero-copy DirectByteBuffer push (Vosk). See Whisper variant for docs.
JNIEXPORT jint JNICALL
Java_com_sal7one_common_1jni_engine_vosk_VoskEngine_nativePushAudioDirectVosk(
    JNIEnv* env, jobject, jlong handle,
    jobject directBuffer, jint byteOffset, jint byteCount, jint sampleRate
) {
    JNI_TRY_CATCH_BEGIN
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::VOSK);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid Vosk handle");
        return -1;
    }

    if (!directBuffer) {
        jni::throwIllegalArgumentException(env, "null direct buffer");
        return -1;
    }
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    if (!base) {
        jni::throwIllegalArgumentException(env, "Not a direct buffer");
        return -1;
    }

    const jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    if (const char* error = pcmBufferRangeError(capacity, byteOffset, byteCount, sampleRate)) {
        jni::throwIllegalArgumentException(env, error);
        return -1;
    }

    const auto* samples = reinterpret_cast<const int16_t*>(base + byteOffset);
    const int sampleCount = byteCount / 2;

    // Vosk accepts the native sample rate directly (it resamples internally).
    return engine->pushAudio(samples, sampleCount, sampleRate);
    JNI_TRY_CATCH_END(env, -1)
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_engine_vosk_VoskEngine_nativeGetPartialVosk(JNIEnv* env, jobject, jlong handle) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::VOSK);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid Vosk handle");
        return nullptr;
    }
    std::string result = engine->getPartial();
    return result.empty() ? nullptr : jni::utf8ToJString(env, result);

    JNI_TRY_CATCH_END(env, nullptr)
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_engine_vosk_VoskEngine_nativeFinalizeVosk(JNIEnv* env, jobject, jlong handle) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::VOSK);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid Vosk handle");
        return nullptr;
    }
    std::string result = engine->finalize();
    return result.empty() ? nullptr : jni::utf8ToJString(env, result);

    JNI_TRY_CATCH_END(env, nullptr)
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_engine_vosk_VoskEngine_nativeResetVosk(JNIEnv*, jobject, jlong handle) {
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::VOSK);
    if (engine) engine->reset();
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_engine_vosk_VoskEngine_nativeTranscribeBatchVosk(
    JNIEnv* env, jobject, jlong handle, jshortArray samples, jint count, jint sampleRate
) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::VOSK);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid Vosk handle");
        return nullptr;
    }
    if (!clampSampleCount(env, samples, count)) {
        jni::throwIllegalArgumentException(
            env, "samples array is required with a positive in-range count");
        return nullptr;
    }

    // Copy out of the critical section before batch inference.
    {
        ShortArrayGuard guard(env, samples);
        if (!guard) {
            jni::throwRuntimeException(env, "Failed to get Vosk audio array");
            return nullptr;
        }
        copyOutAndRelease(guard, count);
    }

    const std::string result = engine->transcribeBatch(
        t_i16Scratch.data(), count, sampleRate);
    return result.empty() ? nullptr : jni::utf8ToJString(env, result);

    JNI_TRY_CATCH_END(env, nullptr)
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_engine_vosk_VoskEngine_nativeGetLastErrorVosk(JNIEnv* env, jobject, jlong handle) {
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::VOSK);
    std::string error = engine ? engine->getLastError() : GET_ERROR();
    return jni::utf8ToJString(env, error);
}

// Vosk Cancellation support (P0 from FFmpeg-Kit analysis)
JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_engine_vosk_VoskEngine_nativeCancelVosk(
    JNIEnv*, jobject, jlong handle
) {
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::VOSK);
    if (engine) {
        LOG_I("JNI", "Vosk cancellation requested for handle %ld", handle);
        engine->cancel();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_engine_vosk_VoskEngine_nativeIsCancelledVosk(
    JNIEnv*, jobject, jlong handle
) {
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::VOSK);
    return engine && engine->isCancelled() ? JNI_TRUE : JNI_FALSE;
}

// =============================================================================
// ONNX Engine (same pattern)
// =============================================================================

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_engine_onnx_OnnxEngine_nativeIsOnnxAvailable(JNIEnv*, jclass) {
#if WITH_ONNX
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_sal7one_common_1jni_engine_onnx_OnnxEngine_nativeCreateOnnx(JNIEnv*, jobject) {
    return EngineRouter::getInstance().createEngine(EngineType::ONNX);
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_engine_onnx_OnnxEngine_nativeDestroyOnnx(JNIEnv*, jobject, jlong handle) {
    EngineRouter::getInstance().destroyEngine(handle, EngineType::ONNX);
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_engine_onnx_OnnxEngine_nativeInitOnnx(
    JNIEnv* env, jobject, jlong handle, jstring modelPath, jstring configJson
) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::ONNX);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid ONNX handle");
        return JNI_FALSE;
    }

    JNI_NULL_CHECK(env, modelPath, "modelPath cannot be null", JNI_FALSE);
    JNI_NULL_CHECK(env, configJson, "configJson cannot be null", JNI_FALSE);
    jni::JStringGuard modelPathGuard(env, modelPath);
    jni::JStringGuard configJsonGuard(env, configJson);
    if (!modelPathGuard.valid() || !configJsonGuard.valid()) {
        jni::throwIllegalArgumentException(env, "Invalid UTF-16 in model path or STT config");
        return JNI_FALSE;
    }

    EngineConfig config;
    std::string configError;
    if (!parseConfig(configJsonGuard.str(), config, &configError)) {
        jni::throwIllegalArgumentException(env, configError.c_str());
        return JNI_FALSE;
    }
    config.modelPath = modelPathGuard.str();
    
    return engine->initialize(config) ? JNI_TRUE : JNI_FALSE;

    JNI_TRY_CATCH_END(env, JNI_FALSE)
}

JNIEXPORT jint JNICALL
Java_com_sal7one_common_1jni_engine_onnx_OnnxEngine_nativePushAudioOnnx(
    JNIEnv* env, jobject, jlong handle, jshortArray samples, jint count, jint sampleRate
) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::ONNX);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid ONNX handle");
        return -1;
    }
    if (!clampSampleCount(env, samples, count)) {
        jni::throwIllegalArgumentException(
            env, "samples array is required with a positive in-range count");
        return -1;
    }

    {
        ShortArrayGuard guard(env, samples);
        if (!guard) {
            jni::throwRuntimeException(env, "Failed to get ONNX audio array");
            return -1;
        }
        copyOutAndRelease(guard, count);
    }
    return engine->pushAudio(t_i16Scratch.data(), count, sampleRate);

    JNI_TRY_CATCH_END(env, -1)
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_engine_onnx_OnnxEngine_nativeGetPartialOnnx(JNIEnv* env, jobject, jlong handle) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::ONNX);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid ONNX handle");
        return nullptr;
    }
    std::string result = engine->getPartial();
    return result.empty() ? nullptr : jni::utf8ToJString(env, result);

    JNI_TRY_CATCH_END(env, nullptr)
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_engine_onnx_OnnxEngine_nativeFinalizeOnnx(JNIEnv* env, jobject, jlong handle) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::ONNX);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid ONNX handle");
        return nullptr;
    }
    std::string result = engine->finalize();
    return result.empty() ? nullptr : jni::utf8ToJString(env, result);

    JNI_TRY_CATCH_END(env, nullptr)
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_engine_onnx_OnnxEngine_nativeResetOnnx(JNIEnv*, jobject, jlong handle) {
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::ONNX);
    if (engine) engine->reset();
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_engine_onnx_OnnxEngine_nativeTranscribeBatchOnnx(
    JNIEnv* env, jobject, jlong handle, jshortArray samples, jint count, jint sampleRate
) {
    JNI_TRY_CATCH_BEGIN

    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::ONNX);
    if (!engine) {
        jni::throwIllegalStateException(env, "Invalid ONNX handle");
        return nullptr;
    }
    if (!clampSampleCount(env, samples, count)) {
        jni::throwIllegalArgumentException(
            env, "samples array is required with a positive in-range count");
        return nullptr;
    }

    {
        ShortArrayGuard guard(env, samples);
        if (!guard) {
            jni::throwRuntimeException(env, "Failed to get ONNX audio array");
            return nullptr;
        }
        copyOutAndRelease(guard, count);
    }

    const std::string result = engine->transcribeBatch(
        t_i16Scratch.data(), count, sampleRate);
    return result.empty() ? nullptr : jni::utf8ToJString(env, result);

    JNI_TRY_CATCH_END(env, nullptr)
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_engine_onnx_OnnxEngine_nativeGetLastErrorOnnx(JNIEnv* env, jobject, jlong handle) {
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::ONNX);
    std::string error = engine ? engine->getLastError() : GET_ERROR();
    return jni::utf8ToJString(env, error);
}

// ONNX Cancellation support (P0 from FFmpeg-Kit analysis)
JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_engine_onnx_OnnxEngine_nativeCancelOnnx(
    JNIEnv*, jobject, jlong handle
) {
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::ONNX);
    if (engine) {
        LOG_I("JNI", "ONNX cancellation requested for handle %ld", handle);
        engine->cancel();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_engine_onnx_OnnxEngine_nativeIsCancelledOnnx(
    JNIEnv*, jobject, jlong handle
) {
    auto engine = EngineRouter::getInstance().getEngine(handle, EngineType::ONNX);
    return engine && engine->isCancelled() ? JNI_TRUE : JNI_FALSE;
}

// =============================================================================
// FFmpeg Pipeline
// =============================================================================

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_audio_FfmpegAudioPipeline_nativeIsFfmpegAvailable(JNIEnv*, jclass) {
#if WITH_FFMPEG
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jshortArray JNICALL
Java_com_sal7one_common_1jni_audio_FfmpegAudioPipeline_nativeDecodeFile(
    JNIEnv* env, jobject, jstring filePath, jint targetSampleRate
) {
#if WITH_FFMPEG
    JNI_TRY_CATCH_BEGIN

    std::string path = jstringToString(env, filePath);
    auto samples = FfmpegPipeline::decodeFile(path, targetSampleRate);

    if (samples.empty()) return nullptr;

    jshortArray result = env->NewShortArray(samples.size());
    if (result) {
        env->SetShortArrayRegion(result, 0, samples.size(), samples.data());
    }
    return result;

    JNI_TRY_CATCH_END(env, nullptr)
#else
    SET_ERROR("FFmpeg not available");
    return nullptr;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_sal7one_common_1jni_audio_FfmpegAudioPipeline_nativeGetFileDuration(
    JNIEnv* env, jobject, jstring filePath
) {
#if WITH_FFMPEG
    return FfmpegPipeline::getFileDuration(jstringToString(env, filePath));
#else
    return -1;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_audio_FfmpegAudioPipeline_nativeGetFileInfo(
    JNIEnv* env, jobject, jstring filePath
) {
#if WITH_FFMPEG
    auto info = FfmpegPipeline::getFileInfo(jstringToString(env, filePath));
    
    const std::string json = JsonUtils::stringify(JsonValue::object({
        {"durationMs", info.durationMs},
        {"sampleRate", info.sampleRate},
        {"channels", info.channels},
        {"bitrate", info.bitrate},
        {"format", info.format}
    }));
    
    return env->NewStringUTF(json.c_str());
#else
    return nullptr;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_audio_FfmpegAudioPipeline_nativeGetLastError(JNIEnv* env, jobject) {
#if WITH_FFMPEG
    return jni::utf8ToJString(env, FfmpegPipeline::getLastError());
#else
    return env->NewStringUTF("FFmpeg not available");
#endif
}

// =============================================================================
// FFmpeg Streaming JNI (NEW - True Streaming Support)
// =============================================================================

JNIEXPORT jlong JNICALL
Java_com_sal7one_common_1jni_audio_FfmpegAudioPipeline_nativeCreateStreamReader(JNIEnv*, jobject) {
#if WITH_FFMPEG
    try {
        return streamReaderRegistry().store(std::make_unique<FfmpegStreamReader>());
    } catch (...) {
        SET_ERROR("Failed to allocate FFmpeg stream reader");
        return 0;
    }
#else
    return 0;
#endif
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_audio_FfmpegAudioPipeline_nativeDestroyStreamReader(
    JNIEnv*, jobject, jlong handle
) {
#if WITH_FFMPEG
    (void)streamReaderRegistry().remove(handle);
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_audio_FfmpegAudioPipeline_nativeOpenStream(
    JNIEnv* env, jobject, jlong handle, jstring filePath, jint targetSampleRate
) {
#if WITH_FFMPEG
    auto reader = streamReaderRegistry().acquireSerialized(handle);
    if (!reader) {
        SET_ERROR("Invalid stream reader handle");
        return JNI_FALSE;
    }
    std::string path = jstringToString(env, filePath);
    return reader->open(path, targetSampleRate) ? JNI_TRUE : JNI_FALSE;
#else
    SET_ERROR("FFmpeg not available");
    return JNI_FALSE;
#endif
}

JNIEXPORT jshortArray JNICALL
Java_com_sal7one_common_1jni_audio_FfmpegAudioPipeline_nativeReadStreamChunk(
    JNIEnv* env, jobject, jlong handle, jint maxSamples
) {
#if WITH_FFMPEG
    auto reader = streamReaderRegistry().acquireSerialized(handle);
    if (!reader) {
        SET_ERROR("Invalid stream reader handle");
        return nullptr;
    }
    
    std::vector<int16_t> chunk = reader->readChunk(maxSamples);
    
    if (chunk.empty()) {
        // Check if it was an error or just EOF
        if (reader->hasError()) {
            SET_ERROR(reader->getError());
        }
        return nullptr; // EOF or Error
    }
    
    jshortArray result = env->NewShortArray(chunk.size());
    if (result) {
        env->SetShortArrayRegion(result, 0, chunk.size(), chunk.data());
    }
    return result;
#else
    SET_ERROR("FFmpeg not available");
    return nullptr;
#endif
}

JNIEXPORT jint JNICALL
Java_com_sal7one_common_1jni_audio_FfmpegAudioPipeline_nativeReadStreamChunkInto(
    JNIEnv* env, jobject, jlong handle, jobject outputBuffer, jint maxSamples
) {
#if WITH_FFMPEG
    auto reader = streamReaderRegistry().acquireSerialized(handle);
    if (!reader) {
        SET_ERROR("Invalid stream reader handle");
        return -1;
    }
    if (!outputBuffer || maxSamples <= 0) {
        SET_ERROR("Direct output buffer and a positive sample count are required");
        return -1;
    }

    void* address = env->GetDirectBufferAddress(outputBuffer);
    const jlong capacity = env->GetDirectBufferCapacity(outputBuffer);
    if (!address || capacity < 0 ||
        static_cast<jlong>(maxSamples) > capacity / static_cast<jlong>(sizeof(int16_t)) ||
        reinterpret_cast<std::uintptr_t>(address) % alignof(int16_t) != 0) {
        SET_ERROR("Direct output buffer is invalid, misaligned, or too small");
        return -1;
    }

    const int result = reader->readChunkInto(static_cast<int16_t*>(address), maxSamples);
    if (result < 0) {
        const std::string error = reader->getError();
        SET_ERROR(error.empty() ? "FFmpeg streaming decode failed" : error);
    }
    return result;
#else
    (void)env;
    (void)handle;
    (void)outputBuffer;
    (void)maxSamples;
    SET_ERROR("FFmpeg not available");
    return -1;
#endif
}

// Note: JNI_OnUnload is in common_jni_bridge.cpp. It deliberately does NOT
// destroy engines: blocking drain inside OnUnload is unsafe. Close engines
// from Kotlin before system exit; live handles at unload are a caller bug.

} // extern "C"
