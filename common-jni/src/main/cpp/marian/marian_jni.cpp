/**
 * Marian translation JNI bridge.
 *
 * Maps Kotlin com.sal7one.common_jni.marian.MarianNative calls to the
 * native MarianEngine. Follows the TTS bridge conventions: status-code
 * errors through ThreadLocalError (surfaced via nativeGetLastError), UTF-16
 * validation through JStringGuard, and no exceptions across the boundary.
 */

#include <jni.h>

#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>

#include "../common/error_codes.h"
#include "../common/logging.h"
#include "../jni/jni_helper.h"
#include "marian_engine.h"
#include "../common/lease_registry.h"

#if defined(MARIAN_AVAILABLE) && MARIAN_AVAILABLE
#define MARIAN_COMPILED 1
#else
#define MARIAN_COMPILED 0
#endif

static const char* TAG = "MarianJNI";

namespace {

stt::concurrency::LeaseRegistry<stt::marian::MarianEngine> g_engines;

void clearError() {
    stt::ThreadLocalError::get().clear();
}

void setError(stt::ErrorCode code, const std::string& message) {
    stt::ThreadLocalError::get().set(code, message);
    LOG_E(TAG, "%s", message.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_marian_MarianNative_nativeRuntimeAvailable(
    JNIEnv* /*env*/, jobject /*thiz*/
) {
    return MARIAN_COMPILED ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_sal7one_common_1jni_marian_MarianNative_nativeCreate(
    JNIEnv* env, jobject /*thiz*/, jstring jModelDir
) {
    clearError();
    if (!MARIAN_COMPILED) {
        setError(stt::ErrorCode::ENGINE_CREATE_FAILED,
                 "Marian translation runtime is not compiled into this build");
        return 0;
    }
    try {
        jni::JStringGuard modelDir(env, jModelDir);
        if (!modelDir.valid()) {
            setError(stt::ErrorCode::INVALID_ARGUMENT,
                     modelDir.error().empty() ? "Invalid model directory string"
                                              : modelDir.error());
            return 0;
        }

        std::string error;
        auto engine = stt::marian::MarianEngine::create(
            modelDir.str(), /*numThreads=*/4, &error);
        if (!engine) {
            setError(stt::ErrorCode::ENGINE_CREATE_FAILED,
                     error.empty() ? "Failed to create translation engine"
                                   : error);
            return 0;
        }

        const int64_t handle = g_engines.insert(std::move(engine));
        LOG_I(TAG, "Created translation engine (handle %lld)",
              static_cast<long long>(handle));
        return handle;
    } catch (const std::exception& e) {
        setError(stt::ErrorCode::ENGINE_CREATE_FAILED,
                 std::string("Exception: ") + e.what());
        return 0;
    } catch (...) {
        setError(stt::ErrorCode::ENGINE_CREATE_FAILED, "Unknown exception");
        return 0;
    }
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_marian_MarianNative_nativeTranslate(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jText
) {
    clearError();
    try {
        jni::JStringGuard text(env, jText);
        if (!text.valid()) {
            setError(stt::ErrorCode::INVALID_ARGUMENT,
                     text.error().empty() ? "Invalid UTF-16 text" : text.error());
            return nullptr;
        }

        auto engine = g_engines.acquire(handle);
        if (!engine) {
            setError(stt::ErrorCode::INVALID_HANDLE, "Invalid engine handle");
            return nullptr;
        }
        const auto result = engine->translate(text.str());
        if (!result.ok) {
            setError(stt::ErrorCode::ENGINE_INFERENCE_FAILED,
                     result.error.empty() ? "Translation failed" : result.error);
            return nullptr;
        }

        // Translation output is standard UTF-8 (Arabic, possibly emoji):
        // NewStringUTF expects modified UTF-8 and mangles 4-byte code points.
        return jni::utf8ToJString(env, result.text);
    } catch (const std::exception& e) {
        setError(stt::ErrorCode::ENGINE_INFERENCE_FAILED,
                 std::string("Exception: ") + e.what());
        return nullptr;
    } catch (...) {
        setError(stt::ErrorCode::ENGINE_INFERENCE_FAILED, "Unknown exception");
        return nullptr;
    }
}

JNIEXPORT jlong JNICALL
Java_com_sal7one_common_1jni_marian_MarianNative_nativeLastLatencyMs(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle
) {
    auto engine = g_engines.acquire(handle);
    return engine ? engine->lastLatencyMs() : -1;
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_marian_MarianNative_nativeRelease(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle
) {
    auto retired = g_engines.retire(handle);
    if (auto* engine = retired.signalTarget()) engine->cancel();
    // Return only when the old inference and statistics leases have drained.
    auto cleanup = std::move(retired).lockExclusive();
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_marian_MarianNative_nativeCancel(JNIEnv*, jobject, jlong handle) {
    auto engine = g_engines.acquire(handle);
    if (engine) engine->cancel();
}

JNIEXPORT jlongArray JNICALL
Java_com_sal7one_common_1jni_marian_MarianNative_nativeLastStats(JNIEnv* env, jobject, jlong handle) {
    auto engine = g_engines.acquire(handle);
    if (!engine) return nullptr;
    const auto stats = engine->lastStageStats();
    const jlong values[] = {stats.tokenizeMs, stats.encoderMs, stats.decoderMs,
        stats.tokensDecoded, engine->lastLatencyMs()};
    auto result = env->NewLongArray(5);
    if (result) env->SetLongArrayRegion(result, 0, 5, values);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_sal7one_common_1jni_marian_MarianNative_nativeCreateConfigured(
    JNIEnv* env, jobject, jstring jModelDir, jint threads, jint maxTokens, jint deadlineMs) {
    clearError();
    if (!MARIAN_COMPILED) {
        setError(stt::ErrorCode::ENGINE_CREATE_FAILED,
                 "Marian translation runtime is not compiled into this build");
        return 0;
    }
    try {
        jni::JStringGuard dir(env, jModelDir);
        if (!dir.valid()) { setError(stt::ErrorCode::INVALID_ARGUMENT, dir.error()); return 0; }
        std::string error;
        auto engine = stt::marian::MarianEngine::create(dir.str(), threads, &error, maxTokens, deadlineMs);
        if (!engine) { setError(stt::ErrorCode::ENGINE_CREATE_FAILED, error); return 0; }
        return g_engines.insert(std::move(engine));
    } catch (const std::exception& e) { setError(stt::ErrorCode::ENGINE_CREATE_FAILED, e.what()); return 0; }
    catch (...) { setError(stt::ErrorCode::ENGINE_CREATE_FAILED, "Unknown Marian initialization failure"); return 0; }
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_marian_MarianNative_nativeGetLastError(
    JNIEnv* env, jobject /*thiz*/
) {
    const std::string& message = stt::ThreadLocalError::get().message();
    return message.empty() ? nullptr : jni::utf8ToJString(env, message);
}

}  // extern "C"
