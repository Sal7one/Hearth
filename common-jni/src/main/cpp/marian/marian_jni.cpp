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

#if defined(MARIAN_AVAILABLE) && MARIAN_AVAILABLE
#define MARIAN_COMPILED 1
#else
#define MARIAN_COMPILED 0
#endif

static const char* TAG = "MarianJNI";

namespace {

std::mutex g_handlesMutex;
int64_t g_nextHandle = 1;
// shared_ptr so a translate in flight keeps its engine alive while
// nativeRelease erases the map entry; the global mutex never guards a
// whole decode (release would stall behind inference).
std::map<int64_t, std::shared_ptr<stt::marian::MarianEngine>> g_engines;

void clearError() {
    stt::ThreadLocalError::get().clear();
}

void setError(stt::ErrorCode code, const std::string& message) {
    stt::ThreadLocalError::get().set(code, message);
    LOG_E(TAG, "%s", message.c_str());
}

stt::marian::MarianEngine* getEngine(int64_t handle) {
    std::lock_guard<std::mutex> lock(g_handlesMutex);
    auto it = g_engines.find(handle);
    return it == g_engines.end() ? nullptr : it->second.get();
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

        std::lock_guard<std::mutex> lock(g_handlesMutex);
        const int64_t handle = g_nextHandle++;
        g_engines.emplace(
            handle, std::shared_ptr<stt::marian::MarianEngine>(std::move(engine)));
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

        std::shared_ptr<stt::marian::MarianEngine> engine;
        {
            std::lock_guard<std::mutex> lock(g_handlesMutex);
            auto it = g_engines.find(handle);
            if (it == g_engines.end()) {
                setError(stt::ErrorCode::INVALID_HANDLE, "Invalid engine handle");
                return nullptr;
            }
            engine = it->second;
        }
        // Decode outside the registry lock: concurrent release waits only for
        // the map swap, never for inference.
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
    stt::marian::MarianEngine* engine = getEngine(handle);
    return engine ? engine->lastLatencyMs() : -1;
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_marian_MarianNative_nativeRelease(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle
) {
    std::lock_guard<std::mutex> lock(g_handlesMutex);
    const size_t removed = g_engines.erase(handle);
    if (removed > 0) {
        LOG_I(TAG, "Released translation engine (handle %lld)",
              static_cast<long long>(handle));
    }
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_marian_MarianNative_nativeGetLastError(
    JNIEnv* env, jobject /*thiz*/
) {
    const std::string& message = stt::ThreadLocalError::get().message();
    return message.empty() ? nullptr : jni::utf8ToJString(env, message);
}

}  // extern "C"
