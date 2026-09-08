#include <jni.h>
#include <string>
#include <mutex>
#include <cmath>
#include <cstdint>
#include <cstring>
#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif
#include "common/logging.h"
#include "common/error_codes.h"
#include "common/audio_utils.h"
#include "common/ring_buffer.h"
#include "common/buffer_pool.h"
#include "common/developer_config.h"
#include "common/json_utils.h"
#include "common/scoped_timer.h"
#include "stt_config.h"
#include "jni/jni_helper.h"
#include "jni/native_registry.h"
#include "router/engine_router.h"

#if defined(WITH_TTS) && WITH_TTS
#include "tts/tts_router.h"
#endif

#if defined(WITH_FFMPEG) && WITH_FFMPEG
#include "ffmpeg/ffmpeg_pipeline.h"
#endif

#if defined(WITH_OPENCV) && WITH_OPENCV
#include "opencv/vision_jni_cache.h"
#endif

using namespace stt;

namespace {
    constexpr const char* kEngineWarmUpUnsupportedMessage =
        "Global engine warm-up is unsupported; load and prepare the selected model "
        "in its owning session";
    constexpr const char* kGenericAudioProcessingUnsupportedMessage =
        "Generic processAudioBuffer was a validation/copy shim, not an audio processor; "
        "use a typed AudioUtils operation or an engine-specific direct-buffer API";

    void throwUnsupportedOperation(JNIEnv* env, const char* message) {
        SET_ERROR_CODE(INVALID_STATE, message);
        jclass exceptionClass = env->FindClass("java/lang/UnsupportedOperationException");
        if (exceptionClass) {
            env->ThrowNew(exceptionClass, message);
            env->DeleteLocalRef(exceptionClass);
        }
    }

    void rejectGlobalDeveloperConfig(JNIEnv* env) {
        throwUnsupportedOperation(env, kGlobalDeveloperConfigUnsupportedMessage);
    }

    void rejectEngineWarmUp(JNIEnv* env) {
        throwUnsupportedOperation(env, kEngineWarmUpUnsupportedMessage);
    }
}

// ============================================================================
// Log Callback Support
// ============================================================================
namespace {
    jobject g_logCallback = nullptr;
    jmethodID g_logCallbackMethod = nullptr;
    std::mutex g_logCallbackMutex;
    
    void dispatchLog(int level, const char* tag, const char* message) {
        std::lock_guard<std::mutex> lock(g_logCallbackMutex);
        if (!g_logCallback || !g_logCallbackMethod || !jni::g_jvm) return;
        
        JNIEnv* env = jni::getEnv();
        if (!env) return;
        
        jstring jTag = jni::utf8ToJString(env, tag ? std::string(tag) : std::string{});
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return;
        }
        jstring jMessage = jni::utf8ToJString(
            env,
            message ? std::string(message) : std::string{}
        );
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            if (jTag) env->DeleteLocalRef(jTag);
            return;
        }
        
        if (jTag && jMessage) {
            env->CallVoidMethod(g_logCallback, g_logCallbackMethod, level, jTag, jMessage);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
        
        if (jTag) env->DeleteLocalRef(jTag);
        if (jMessage) env->DeleteLocalRef(jMessage);
    }
}

// ============================================================================
// Native Method Implementations (static functions)
// ============================================================================

static void nativeInit(JNIEnv* env, jobject) {
    LOG_I("CommonJni", "Native module initialized (v1.0.0)");
    LOG_I("CommonJni", "Capabilities: FFmpeg=%d, Whisper=%d, Vosk=%d, ONNX=%d",
          WITH_FFMPEG, WITH_WHISPER, WITH_VOSK, WITH_ONNX);
    LOG_I(
        "CommonJni", "NEON SIMD: %s",
        AudioUtils::hasNeon() ? "enabled" : "disabled"
    );
}

static void nativeSetLogCallback(JNIEnv* env, jobject, jobject callback) {
    jobject newCallback = nullptr;
    jmethodID newCallbackMethod = nullptr;

    if (callback) {
        jclass callbackClass = env->GetObjectClass(callback);
        if (!callbackClass) return;

        newCallbackMethod = env->GetMethodID(
            callbackClass,
            "onLog",
            "(ILjava/lang/String;Ljava/lang/String;)V"
        );
        env->DeleteLocalRef(callbackClass);
        if (!newCallbackMethod) return;

        newCallback = env->NewGlobalRef(callback);
        if (!newCallback) return;
    }

    {
        std::lock_guard<std::mutex> lock(g_logCallbackMutex);
        if (g_logCallback) env->DeleteGlobalRef(g_logCallback);
        g_logCallback = newCallback;
        g_logCallbackMethod = newCallbackMethod;
    }

    LOG_I("CommonJni", "Log callback %s", callback ? "registered" : "cleared");
}

static jstring nativeGetVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("1.0.0");
}

static jint nativeGetCapabilityMask(JNIEnv*, jobject) {
    return STT_CAPABILITY_MASK;
}

static jboolean nativeHasFFmpeg(JNIEnv*, jobject) {
#if defined(WITH_FFMPEG) && WITH_FFMPEG
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

static jboolean nativeHasWhisper(JNIEnv*, jobject) {
#if defined(WITH_WHISPER) && WITH_WHISPER
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

static jboolean nativeHasVosk(JNIEnv*, jobject) {
#if defined(WITH_VOSK) && WITH_VOSK
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

static jboolean nativeHasOnnx(JNIEnv*, jobject) {
#if defined(WITH_ONNX) && WITH_ONNX
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

static jboolean nativeHasOpenCV(JNIEnv*, jobject) {
#if defined(WITH_OPENCV) && WITH_OPENCV
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

static jshortArray nativeDecodeAudio(JNIEnv* env, jobject, jstring filePath, jint targetSampleRate) {
    JNI_TRY_CATCH_BEGIN
    
#if defined(WITH_FFMPEG) && WITH_FFMPEG
    JNI_NULL_CHECK(env, filePath, "filePath cannot be null", nullptr);
    
    jni::JStringGuard path(env, filePath);
    if (!path.valid()) return nullptr;
    
    auto samples = stt::FfmpegPipeline::decodeFile(path.get(), targetSampleRate);
    if (samples.empty()) return nullptr;
    
    jshortArray result = env->NewShortArray(static_cast<jsize>(samples.size()));
    if (!result) return nullptr;
    
    env->SetShortArrayRegion(result, 0, static_cast<jsize>(samples.size()), samples.data());
    return result;
#else
    return nullptr;
#endif

    JNI_TRY_CATCH_END(env, nullptr)
}

static jboolean nativeExtractAudio(JNIEnv* env, jobject, jstring inputPath, jstring outputPath, jstring format, jint bitrateKbps) {
    JNI_TRY_CATCH_BEGIN
    
#if defined(WITH_FFMPEG) && WITH_FFMPEG
    JNI_NULL_CHECK(env, inputPath, "inputPath cannot be null", JNI_FALSE);
    JNI_NULL_CHECK(env, outputPath, "outputPath cannot be null", JNI_FALSE);
    JNI_NULL_CHECK(env, format, "format cannot be null", JNI_FALSE);
    
    jni::JStringGuard input(env, inputPath);
    jni::JStringGuard output(env, outputPath);
    jni::JStringGuard fmt(env, format);
    
    if (!input.valid() || !output.valid() || !fmt.valid()) {
        return JNI_FALSE;
    }
    
    bool result = stt::FfmpegPipeline::extractAudio(input.get(), output.get(), fmt.get(), bitrateKbps);
    return result ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif

    JNI_TRY_CATCH_END(env, JNI_FALSE)
}

static jlong nativeGetFileDuration(JNIEnv* env, jobject, jstring filePath) {
    JNI_TRY_CATCH_BEGIN
    
#if defined(WITH_FFMPEG) && WITH_FFMPEG
    JNI_NULL_CHECK(env, filePath, "filePath cannot be null", -1);
    
    jni::JStringGuard path(env, filePath);
    if (!path.valid()) return -1;
    
    return stt::FfmpegPipeline::getFileDuration(path.get());
#else
    return -1;
#endif

    JNI_TRY_CATCH_END(env, -1)
}

static jstring nativeGetFileInfo(JNIEnv* env, jobject, jstring filePath) {
    JNI_TRY_CATCH_BEGIN
    
#if defined(WITH_FFMPEG) && WITH_FFMPEG
    JNI_NULL_CHECK(env, filePath, "filePath cannot be null", nullptr);
    
    jni::JStringGuard path(env, filePath);
    if (!path.valid()) return nullptr;
    
    auto info = stt::FfmpegPipeline::getFileInfo(path.get());
    
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

    JNI_TRY_CATCH_END(env, nullptr)
}

static jboolean nativeCanStreamCopy(JNIEnv* env, jobject, jstring inputPath, jstring targetFormat, jint targetSampleRate) {
    JNI_TRY_CATCH_BEGIN

#if defined(WITH_FFMPEG) && WITH_FFMPEG
    JNI_NULL_CHECK(env, inputPath, "inputPath cannot be null", JNI_FALSE);
    JNI_NULL_CHECK(env, targetFormat, "targetFormat cannot be null", JNI_FALSE);

    jni::JStringGuard path(env, inputPath);
    jni::JStringGuard format(env, targetFormat);
    if (!path.valid() || !format.valid()) return JNI_FALSE;

    // Delegate to the canonical C++ implementation. The previous inline check
    // compared the *container* format name against the requested *codec*
    // format, which produced false positives (e.g. "mov,mp4,m4a" == "m4a"
    // succeeded without verifying the audio codec). FfmpegPipeline::canStreamCopy
    // inspects the actual audio codec_id.
    return stt::FfmpegPipeline::canStreamCopy(
               path.get(), format.get(), static_cast<int>(targetSampleRate))
               ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif

    JNI_TRY_CATCH_END(env, JNI_FALSE)
}

static jboolean nativeExtractAudioSmart(
    JNIEnv* env, jobject,
    jstring inputPath, jstring outputPath, jstring format,
    jint targetSampleRate, jint bitrateKbps
) {
    JNI_TRY_CATCH_BEGIN

#if defined(WITH_FFMPEG) && WITH_FFMPEG
    JNI_NULL_CHECK(env, inputPath, "inputPath cannot be null", JNI_FALSE);
    JNI_NULL_CHECK(env, outputPath, "outputPath cannot be null", JNI_FALSE);
    JNI_NULL_CHECK(env, format, "format cannot be null", JNI_FALSE);

    jni::JStringGuard in(env, inputPath);
    jni::JStringGuard out(env, outputPath);
    jni::JStringGuard fmt(env, format);
    if (!in.valid() || !out.valid() || !fmt.valid()) return JNI_FALSE;

    return stt::FfmpegPipeline::extractAudioSmart(
               in.get(), out.get(), fmt.get(),
               static_cast<int>(targetSampleRate),
               static_cast<int>(bitrateKbps))
               ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif

    JNI_TRY_CATCH_END(env, JNI_FALSE)
}

static jboolean nativeExtractAudioWithProgress(
    JNIEnv* env, jobject,
    jstring inputPath, jstring outputPath, jstring format,
    jint targetSampleRate, jint bitrateKbps, jboolean allowStreamCopy,
    jobject progressCallback
) {
    JNI_TRY_CATCH_BEGIN

#if defined(WITH_FFMPEG) && WITH_FFMPEG
    JNI_NULL_CHECK(env, inputPath, "inputPath cannot be null", JNI_FALSE);
    JNI_NULL_CHECK(env, outputPath, "outputPath cannot be null", JNI_FALSE);
    JNI_NULL_CHECK(env, format, "format cannot be null", JNI_FALSE);
    JNI_NULL_CHECK(env, progressCallback, "progressCallback cannot be null", JNI_FALSE);

    jni::JStringGuard in(env, inputPath);
    jni::JStringGuard out(env, outputPath);
    jni::JStringGuard fmt(env, format);
    if (!in.valid() || !out.valid() || !fmt.valid()) return JNI_FALSE;

    jclass callbackClass = env->GetObjectClass(progressCallback);
    if (!callbackClass) return JNI_FALSE;
    jmethodID onProgress = env->GetMethodID(callbackClass, "onProgress", "(F)Z");
    env->DeleteLocalRef(callbackClass);
    if (!onProgress) return JNI_FALSE;

    const auto callback = [env, progressCallback, onProgress](float progress) {
        const jboolean keepGoing = env->CallBooleanMethod(progressCallback, onProgress, progress);
        if (env->ExceptionCheck()) {
            // The extraction API reports callback failure as a normal false
            // result. Clear here so cleanup/logging never runs with a pending
            // Java exception on the JNI thread.
            env->ExceptionClear();
            return false;
        }
        return keepGoing == JNI_TRUE;
    };

    const bool result = allowStreamCopy == JNI_TRUE
        ? stt::FfmpegPipeline::extractAudioSmart(
              in.get(), out.get(), fmt.get(),
              static_cast<int>(targetSampleRate),
              static_cast<int>(bitrateKbps),
              callback)
        : stt::FfmpegPipeline::extractAudio(
              in.get(), out.get(), fmt.get(),
              static_cast<int>(bitrateKbps),
              callback);
    return result ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif

    JNI_TRY_CATCH_END(env, JNI_FALSE)
}

static jboolean nativeHasEncoder(JNIEnv* env, jobject, jstring encoderName) {
    JNI_TRY_CATCH_BEGIN
    
#if defined(WITH_FFMPEG) && WITH_FFMPEG
    JNI_NULL_CHECK(env, encoderName, "encoderName cannot be null", JNI_FALSE);
    
    jni::JStringGuard name(env, encoderName);
    if (!name.valid()) return JNI_FALSE;
    
    return stt::FfmpegPipeline::hasEncoder(name.get()) ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif

    JNI_TRY_CATCH_END(env, JNI_FALSE)
}

static void nativeCancelAll(JNIEnv*, jobject) {
    LOG_I("CommonJni", "Cancel all requested");
    // Flip cancel flag on every live STT engine; running operations
    // observe it at their next safe point (whisper/vosk ProcessingGuard).
    stt::EngineRouter::getInstance().cancelAll();
#if defined(WITH_TTS) && WITH_TTS
    common_jni::tts::TtsRouter::getInstance().cancelAll();
#endif
}

static jstring nativeGetLastError(JNIEnv* env, jobject) {
    if (!stt::ThreadLocalError::get().hasError()) {
        return nullptr;
    }
    std::string error = stt::ThreadLocalError::get().format();
    return jni::utf8ToJString(env, error);
}

static void nativeClearError(JNIEnv*, jobject) {
    stt::ThreadLocalError::get().clear();
}

// ============================================================================
// DirectByteBuffer Audio Processing (Zero-Copy)
// ============================================================================

static jboolean nativeDecodeAudioToBuffer(JNIEnv* env, jobject, jstring filePath, jint targetSampleRate, jobject outputBuffer) {
    JNI_TRY_CATCH_BEGIN
    
#if defined(WITH_FFMPEG) && WITH_FFMPEG
    JNI_NULL_CHECK(env, filePath, "filePath cannot be null", JNI_FALSE);
    JNI_NULL_CHECK(env, outputBuffer, "outputBuffer cannot be null", JNI_FALSE);
    
    // Get direct buffer address
    void* bufferPtr = env->GetDirectBufferAddress(outputBuffer);
    if (!bufferPtr) {
        jni::throwIllegalArgumentException(env, "outputBuffer must be a direct ByteBuffer");
        return JNI_FALSE;
    }
    
    jlong capacity = env->GetDirectBufferCapacity(outputBuffer);
    if (capacity <= 0) {
        jni::throwIllegalArgumentException(env, "outputBuffer has invalid capacity");
        return JNI_FALSE;
    }
    
    jni::JStringGuard path(env, filePath);
    if (!path.valid()) return JNI_FALSE;
    
    // Decode audio
    auto samples = stt::FfmpegPipeline::decodeFile(path.get(), targetSampleRate);
    if (samples.empty()) {
        SET_ERROR_CODE(AUDIO_DECODE_FAILED, "Failed to decode audio file");
        return JNI_FALSE;
    }
    
    // Check if buffer is large enough
    size_t requiredBytes = samples.size() * sizeof(int16_t);
    if (static_cast<size_t>(capacity) < requiredBytes) {
        SET_ERROR_FMT(INVALID_ARGUMENT, "Buffer too small: need %zu bytes, have %lld", 
                      requiredBytes, (long long)capacity);
        return JNI_FALSE;
    }
    
    // Copy samples to direct buffer (zero-copy from native perspective)
    memcpy(bufferPtr, samples.data(), requiredBytes);
    
    return JNI_TRUE;
#else
    SET_ERROR_CODE(FFMPEG_NOT_AVAILABLE, "FFmpeg not compiled in");
    return JNI_FALSE;
#endif

    JNI_TRY_CATCH_END(env, JNI_FALSE)
}

static jint nativeGetDecodedSampleCount(JNIEnv* env, jobject, jstring filePath, jint targetSampleRate) {
    JNI_TRY_CATCH_BEGIN
    
#if defined(WITH_FFMPEG) && WITH_FFMPEG
    JNI_NULL_CHECK(env, filePath, "filePath cannot be null", -1);
    
    jni::JStringGuard path(env, filePath);
    if (!path.valid()) return -1;
    
    // Get file info to estimate sample count
    auto info = stt::FfmpegPipeline::getFileInfo(path.get());
    if (info.durationMs <= 0) {
        return -1;
    }
    
    // Calculate expected sample count
    int sampleRate = targetSampleRate > 0 ? targetSampleRate : info.sampleRate;
    jint expectedSamples = static_cast<jint>((info.durationMs * sampleRate) / 1000);
    
    // Add 10% buffer for safety
    return expectedSamples + (expectedSamples / 10);
#else
    return -1;
#endif

    JNI_TRY_CATCH_END(env, -1)
}

static jboolean nativeProcessAudioBuffer(JNIEnv* env, jobject, jobject inputBuffer, jint sampleCount, jint sampleRate, jobject outputBuffer) {
    JNI_TRY_CATCH_BEGIN

    static_cast<void>(inputBuffer);
    static_cast<void>(sampleCount);
    static_cast<void>(sampleRate);
    static_cast<void>(outputBuffer);
    throwUnsupportedOperation(env, kGenericAudioProcessingUnsupportedMessage);
    return JNI_FALSE;

    JNI_TRY_CATCH_END(env, JNI_FALSE)
}

// ----------------------------------------------------------------------------
// nativeAudioLevelDirect
//
// Compute a normalized audio level (0.0..1.0, linear RMS / 32768) over an
// int16 PCM region of a DirectByteBuffer. Used by the Kotlin realtime mic
// visualization to avoid a short-array copy + Java-side loop on every chunk.
// NEON-accelerated on arm64.
// ----------------------------------------------------------------------------
static jfloat nativeAudioLevelDirect(JNIEnv* env, jobject, jobject buffer, jint byteOffset, jint byteCount) {
    JNI_TRY_CATCH_BEGIN
    JNI_NULL_CHECK(env, buffer, "buffer cannot be null", 0.0f);

    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!base) {
        jni::throwIllegalArgumentException(env, "buffer must be a direct ByteBuffer");
        return 0.0f;
    }
    jlong cap = env->GetDirectBufferCapacity(buffer);
    if (cap < 0 || byteOffset < 0 || byteCount <= 0 || (byteCount & 1) != 0 ||
        byteOffset + byteCount > cap) {
        return 0.0f;
    }

    const auto* s = reinterpret_cast<const int16_t*>(base + byteOffset);
    const size_t n = static_cast<size_t>(byteCount) / 2;
    if (n == 0) return 0.0f;

    uint64_t sumSq = 0;
    size_t i = 0;

#if defined(__ARM_NEON) || defined(__aarch64__)
    // Vectorized sum of squares: 8 int16 per iteration -> 4 int32 partial, accumulated as int64
    uint64x2_t acc = vdupq_n_u64(0);
    for (; i + 8 <= n; i += 8) {
        int16x8_t v = vld1q_s16(s + i);
        int16x4_t lo = vget_low_s16(v);
        int16x4_t hi = vget_high_s16(v);
        int32x4_t sqLo = vmull_s16(lo, lo);   // 4 * int32
        int32x4_t sqHi = vmull_s16(hi, hi);
        int32x4_t sq = vaddq_s32(sqLo, sqHi); // 4 * int32
        uint32x4_t sqU = vreinterpretq_u32_s32(sq);
        acc = vaddq_u64(acc, vpaddlq_u32(sqU));
    }
    sumSq += vgetq_lane_u64(acc, 0) + vgetq_lane_u64(acc, 1);
#endif
    for (; i < n; ++i) {
        int32_t v = s[i];
        sumSq += static_cast<uint64_t>(v * v);
    }

    const double meanSq = static_cast<double>(sumSq) / static_cast<double>(n);
    const double rms = std::sqrt(meanSq) / 32768.0;
    if (rms < 0.0) return 0.0f;
    if (rms > 1.0) return 1.0f;
    return static_cast<jfloat>(rms);

    JNI_TRY_CATCH_END(env, 0.0f)
}

// Developer Config APIs
// ============================================================================

static void nativeSetAudioGateConfig(JNIEnv* env, jobject, jstring configJson) {
    JNI_TRY_CATCH_BEGIN

    JNI_NULL_CHECK_VOID(env, configJson, "configJson cannot be null");
    rejectGlobalDeveloperConfig(env);
    
    JNI_TRY_CATCH_END_VOID(env)
}

static void nativeSetProcessingConfig(JNIEnv* env, jobject, jstring configJson) {
    JNI_TRY_CATCH_BEGIN
    
    JNI_NULL_CHECK_VOID(env, configJson, "configJson cannot be null");
    rejectGlobalDeveloperConfig(env);
    
    JNI_TRY_CATCH_END_VOID(env)
}

static jstring nativeGetAudioGateStatus(JNIEnv* env, jobject) {
    JNI_TRY_CATCH_BEGIN
    
    const std::string json = JsonUtils::stringify(globalDeveloperConfigStatus());
    
    return env->NewStringUTF(json.c_str());
    
    JNI_TRY_CATCH_END(env, nullptr)
}

static jstring nativeGetBufferPoolStats(JNIEnv* env, jobject) {
    JNI_TRY_CATCH_BEGIN
    
    auto& pool = AudioBufferPool::getInstance();
    
    const std::string json = JsonUtils::stringify(JsonValue::object({
        {"availableInt16", static_cast<int64_t>(pool.availableInt16())},
        {"availableFloat", static_cast<int64_t>(pool.availableFloat())},
        {"bufferSamples", static_cast<int64_t>(AudioBufferPool::BUFFER_SAMPLES)},
        {"poolSize", static_cast<int64_t>(AudioBufferPool::POOL_SIZE)}
    }));
    
    return env->NewStringUTF(json.c_str());
    
    JNI_TRY_CATCH_END(env, nullptr)
}

static void nativeWarmUpEngines(JNIEnv* env, jobject) {
    JNI_TRY_CATCH_BEGIN
    rejectEngineWarmUp(env);
    
    JNI_TRY_CATCH_END_VOID(env)
}

static jboolean nativeIsBufferAligned(JNIEnv* env, jobject, jobject buffer) {
    JNI_TRY_CATCH_BEGIN
    
    if (!buffer) return JNI_FALSE;
    
    return jni::isBufferAlignedForNeon(env, buffer) ? JNI_TRUE : JNI_FALSE;
    
    JNI_TRY_CATCH_END(env, JNI_FALSE)
}

// ============================================================================
// JNI Method Registration Table
// ============================================================================

static const JNINativeMethod gCommonJniMethods[] = {
    {"nativeInit", "()V", reinterpret_cast<void*>(nativeInit)},
    {"nativeSetLogCallback", "(Lcom/sal7one/common_jni/NativeLogCallback;)V", reinterpret_cast<void*>(nativeSetLogCallback)},
    {"nativeGetVersion", "()Ljava/lang/String;", reinterpret_cast<void*>(nativeGetVersion)},
    {"nativeGetCapabilityMask", "()I", reinterpret_cast<void*>(nativeGetCapabilityMask)},
    {"nativeHasFFmpeg", "()Z", reinterpret_cast<void*>(nativeHasFFmpeg)},
    {"nativeHasWhisper", "()Z", reinterpret_cast<void*>(nativeHasWhisper)},
    {"nativeHasVosk", "()Z", reinterpret_cast<void*>(nativeHasVosk)},
    {"nativeHasOnnx", "()Z", reinterpret_cast<void*>(nativeHasOnnx)},
    {"nativeHasOpenCV", "()Z", reinterpret_cast<void*>(nativeHasOpenCV)},
    {"nativeDecodeAudio", "(Ljava/lang/String;I)[S", reinterpret_cast<void*>(nativeDecodeAudio)},
    {"nativeExtractAudio", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Z", reinterpret_cast<void*>(nativeExtractAudio)},
    {"nativeGetFileDuration", "(Ljava/lang/String;)J", reinterpret_cast<void*>(nativeGetFileDuration)},
    {"nativeGetFileInfo", "(Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(nativeGetFileInfo)},
    {"nativeHasEncoder", "(Ljava/lang/String;)Z", reinterpret_cast<void*>(nativeHasEncoder)},
    {"nativeCanStreamCopy", "(Ljava/lang/String;Ljava/lang/String;I)Z", reinterpret_cast<void*>(nativeCanStreamCopy)},
    {"nativeExtractAudioSmart", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)Z", reinterpret_cast<void*>(nativeExtractAudioSmart)},
    {"nativeExtractAudioWithProgress", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIZLcom/sal7one/common_jni/AudioExtractionProgressCallback;)Z", reinterpret_cast<void*>(nativeExtractAudioWithProgress)},
    {"nativeCancelAll", "()V", reinterpret_cast<void*>(nativeCancelAll)},
    {"nativeGetLastError", "()Ljava/lang/String;", reinterpret_cast<void*>(nativeGetLastError)},
    {"nativeClearError", "()V", reinterpret_cast<void*>(nativeClearError)},
    // DirectByteBuffer methods
    {"nativeDecodeAudioToBuffer", "(Ljava/lang/String;ILjava/nio/ByteBuffer;)Z", reinterpret_cast<void*>(nativeDecodeAudioToBuffer)},
    {"nativeGetDecodedSampleCount", "(Ljava/lang/String;I)I", reinterpret_cast<void*>(nativeGetDecodedSampleCount)},
    {"nativeProcessAudioBuffer", "(Ljava/nio/ByteBuffer;IILjava/nio/ByteBuffer;)Z", reinterpret_cast<void*>(nativeProcessAudioBuffer)},
    {"nativeAudioLevelDirect", "(Ljava/nio/ByteBuffer;II)F", reinterpret_cast<void*>(nativeAudioLevelDirect)},
    // Developer config APIs
    {"nativeSetAudioGateConfig", "(Ljava/lang/String;)V", reinterpret_cast<void*>(nativeSetAudioGateConfig)},
    {"nativeSetProcessingConfig", "(Ljava/lang/String;)V", reinterpret_cast<void*>(nativeSetProcessingConfig)},
    {"nativeGetAudioGateStatus", "()Ljava/lang/String;", reinterpret_cast<void*>(nativeGetAudioGateStatus)},
    {"nativeGetBufferPoolStats", "()Ljava/lang/String;", reinterpret_cast<void*>(nativeGetBufferPoolStats)},
    {"nativeWarmUpEngines", "()V", reinterpret_cast<void*>(nativeWarmUpEngines)},
    {"nativeIsBufferAligned", "(Ljava/nio/ByteBuffer;)Z", reinterpret_cast<void*>(nativeIsBufferAligned)},
};

// ============================================================================
// JNI_OnLoad - Register all native methods
// ============================================================================

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;

    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOG_E("CommonJni", "JNI_OnLoad: Failed to get JNIEnv");
        return JNI_ERR;
    }

    // Store global JVM reference for thread attachment
    jni::g_jvm = vm;

    // Hand the JavaVM to FFmpeg BEFORE anything touches MediaCodec: the
    // h264_mediacodec encoder creates surfaces/codecs through JNI and
    // av_jni_set_java_vm() is the documented prerequisite. Best-effort —
    // a failure only means hw encode stays unavailable (software encode
    // never needs the VM). Provided by media/media_jni.cpp when FFmpeg
    // is compiled in; the weak default keeps non-FFmpeg builds linking.
    {
        extern int hearth_media_set_java_vm(void* vm) __attribute__((weak));
        if (hearth_media_set_java_vm) (void)hearth_media_set_java_vm(vm);
    }

    // Register CommonJni native methods
    jclass commonJniClass = env->FindClass("com/sal7one/common_jni/CommonJni");
    if (!commonJniClass) {
        LOG_E("CommonJni", "JNI_OnLoad: Failed to find CommonJni class");
        jni::g_jvm = nullptr;
        return JNI_ERR;
    }
    
    int methodCount = sizeof(gCommonJniMethods) / sizeof(gCommonJniMethods[0]);
    if (env->RegisterNatives(commonJniClass, gCommonJniMethods, methodCount) < 0) {
        LOG_E("CommonJni", "JNI_OnLoad: Failed to register native methods");
        env->DeleteLocalRef(commonJniClass);
        jni::g_jvm = nullptr;
        return JNI_ERR;
    }
    
    env->DeleteLocalRef(commonJniClass);

#if defined(WITH_OPENCV) && WITH_OPENCV
    if (!vision::jni_cache::initialize(env)) {
        LOG_E("CommonJni", "JNI_OnLoad: Failed to initialize vision JNI cache");
        jni::g_jvm = nullptr;
        return JNI_ERR;
    }
#endif
    
    LOG_I("CommonJni", "JNI_OnLoad: Registered %d native methods", methodCount);
    return JNI_VERSION_1_6;
}

// Forward declaration for cleanup
namespace stt {
    class EngineRouter;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* /*reserved*/) {
    LOG_I("CommonJni", "JNI_OnUnload: Cleaning up");

    JNIEnv* env = nullptr;
    const bool hasEnv =
        vm && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK;

    {
        std::lock_guard<std::mutex> lock(g_logCallbackMutex);
        if (g_logCallback && hasEnv) env->DeleteGlobalRef(g_logCallback);
        g_logCallback = nullptr;
        g_logCallbackMethod = nullptr;
    }

#if defined(WITH_OPENCV) && WITH_OPENCV
    if (hasEnv) {
        vision::jni_cache::release(env);
    } else {
        LOG_W("CommonJni", "JNI_OnUnload: Vision cache thread is not attached");
    }
#endif

    // No JNI helper may attach a thread after this point.
    jni::g_jvm = nullptr;
}
