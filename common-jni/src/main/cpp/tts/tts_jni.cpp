/**
 * TTS JNI Bridge
 * 
 * Provides JNI bindings for TTS engines.
 * Maps Kotlin TtsNative calls to native TTS router.
 */

#include <jni.h>
#include "tts_router.h"
#include "tts_engine_interface.h"
#include "../common/error_codes.h"
#include "../common/logging.h"
#include "../common/json_utils.h"
#include "../jni/jni_helper.h"

using namespace common_jni::tts;

static const char* TAG = "TtsJNI";

namespace {

void clearTtsError() {
    stt::ThreadLocalError::get().clear();
}

void setTtsError(stt::ErrorCode code, const std::string& message) {
    stt::ThreadLocalError::get().set(code, message);
}

void setAndLogTtsError(stt::ErrorCode code, const std::string& message) {
    setTtsError(code, message);
    LOG_E(TAG, "%s", message.c_str());
}

std::string engineErrorOr(const std::string& engineError, const char* fallback) {
    return engineError.empty() ? std::string(fallback) : engineError;
}

} // namespace

extern "C" {

// ============================================================================
// Engine Lifecycle
// ============================================================================

JNIEXPORT jlong JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeCreateEngine(
    JNIEnv* env, jobject thiz, jint engineType
) {
    clearTtsError();
    try {
        auto type = static_cast<TtsEngineType>(engineType);
        int64_t handle = TtsRouter::getInstance().createEngine(type);
        
        if (handle <= 0) {
            setAndLogTtsError(
                stt::ErrorCode::ENGINE_CREATE_FAILED,
                "Failed to create engine of type " + std::to_string(engineType)
            );
        }
        
        return handle;
    } catch (const std::exception& e) {
        setAndLogTtsError(
            stt::ErrorCode::ENGINE_CREATE_FAILED,
            std::string("Exception: ") + e.what()
        );
        return -1;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeInitialize(
    JNIEnv* env, jobject thiz,
    jlong handle,
    jstring jModelPath,
    jstring jConfigJson
) {
    clearTtsError();
    try {
        auto engine = TtsRouter::getInstance().getEngine(handle);
        if (!engine) {
            setTtsError(stt::ErrorCode::INVALID_HANDLE, "Invalid engine handle");
            return JNI_FALSE;
        }
        
        jni::JStringGuard modelPath(env, jModelPath);
        jni::JStringGuard configJson(env, jConfigJson);
        if (!modelPath.valid() || !configJson.valid()) {
            setTtsError(
                stt::ErrorCode::INVALID_ARGUMENT,
                "Invalid UTF-16 in model path or TTS config string"
            );
            return JNI_FALSE;
        }

        TtsEngineConfig config;
        std::string configError;
        if (!parseTtsConfig(configJson.str(), config, &configError)) {
            setTtsError(stt::ErrorCode::INVALID_ARGUMENT, configError);
            return JNI_FALSE;
        }
        config.modelPath = modelPath.str();
        
        bool success = engine->initialize(config);
        
        if (!success) {
            setTtsError(
                stt::ErrorCode::INIT_FAILED,
                engineErrorOr(engine->getLastError(), "TTS initialization failed")
            );
        }
        
        return success ? JNI_TRUE : JNI_FALSE;
        
    } catch (const std::exception& e) {
        setAndLogTtsError(
            stt::ErrorCode::INIT_FAILED,
            std::string("Exception: ") + e.what()
        );
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeRelease(
    JNIEnv* env, jobject thiz, jlong handle
) {
    TtsRouter::getInstance().destroyEngine(handle);
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeReset(
    JNIEnv* env, jobject thiz, jlong handle
) {
    auto engine = TtsRouter::getInstance().getEngine(handle);
    if (engine) {
        engine->reset();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeIsInitialized(
    JNIEnv* env, jobject thiz, jlong handle
) {
    auto engine = TtsRouter::getInstance().getEngine(handle);
    return (engine && engine->isInitialized()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeCancel(
    JNIEnv* env, jobject thiz, jlong handle
) {
    auto engine = TtsRouter::getInstance().getEngineForSignal(handle);
    if (engine) {
        engine->cancel();
    }
}

// ============================================================================
// Synthesis
// ============================================================================

JNIEXPORT jfloatArray JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeSynthesize(
    JNIEnv* env, jobject thiz,
    jlong handle,
    jstring jText,
    jint targetSampleRate
) {
    clearTtsError();
    try {
        auto engine = TtsRouter::getInstance().getEngine(handle);
        if (!engine) {
            setTtsError(stt::ErrorCode::INVALID_HANDLE, "Invalid engine handle");
            return nullptr;
        }

        jni::JStringGuard text(env, jText);
        if (!text.valid()) {
            setTtsError(stt::ErrorCode::INVALID_ARGUMENT, "Invalid TTS text string");
            return nullptr;
        }

        std::vector<float> audio = engine->synthesize(text.str(), targetSampleRate);
        
        if (audio.empty()) {
            setTtsError(
                stt::ErrorCode::ENGINE_INFERENCE_FAILED,
                engineErrorOr(engine->getLastError(), "TTS synthesis produced no audio")
            );
            return nullptr;
        }
        
        jfloatArray result = env->NewFloatArray(static_cast<jsize>(audio.size()));
        if (!result) {
            setTtsError(stt::ErrorCode::OUT_OF_MEMORY, "Failed to allocate TTS audio array");
            return nullptr;
        }
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(audio.size()), audio.data());
        if (env->ExceptionCheck()) return nullptr;
        
        return result;
        
    } catch (const std::exception& e) {
        setAndLogTtsError(
            stt::ErrorCode::ENGINE_INFERENCE_FAILED,
            std::string("Exception: ") + e.what()
        );
        return nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeStartStreaming(
    JNIEnv* env, jobject thiz, jlong handle
) {
    clearTtsError();
    auto engine = TtsRouter::getInstance().getEngine(handle);
    if (!engine) {
        setTtsError(stt::ErrorCode::INVALID_HANDLE, "Invalid engine handle");
        return JNI_FALSE;
    }

    const bool success = engine->startStreaming(nullptr);
    if (!success) {
        setTtsError(
            stt::ErrorCode::INVALID_STATE,
            engineErrorOr(engine->getLastError(), "Failed to start TTS streaming")
        );
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativePushText(
    JNIEnv* env, jobject thiz,
    jlong handle,
    jstring jText
) {
    clearTtsError();
    auto engine = TtsRouter::getInstance().getEngine(handle);
    if (!engine) {
        setTtsError(stt::ErrorCode::INVALID_HANDLE, "Invalid engine handle");
        return -1;
    }

    jni::JStringGuard text(env, jText);
    if (!text.valid()) {
        setTtsError(stt::ErrorCode::INVALID_ARGUMENT, "Invalid TTS text string");
        return -1;
    }
    const int result = engine->pushText(text.str());
    if (result < 0) {
        setTtsError(
            stt::ErrorCode::ENGINE_INFERENCE_FAILED,
            engineErrorOr(engine->getLastError(), "Failed to queue TTS text")
        );
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeFinalizeStreaming(
    JNIEnv* env, jobject thiz, jlong handle
) {
    clearTtsError();
    auto engine = TtsRouter::getInstance().getEngine(handle);
    if (!engine) {
        setTtsError(stt::ErrorCode::INVALID_HANDLE, "Invalid engine handle");
        return JNI_FALSE;
    }
    const bool success = engine->finalizeStreaming();
    if (!success) {
        setTtsError(
            stt::ErrorCode::ENGINE_INFERENCE_FAILED,
            engineErrorOr(engine->getLastError(), "Failed to finalize TTS streaming")
        );
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

// ============================================================================
// Voice Management
// ============================================================================

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeGetVoices(
    JNIEnv* env, jobject thiz, jlong handle
) {
    clearTtsError();
    auto engine = TtsRouter::getInstance().getEngine(handle);
    if (!engine) {
        setTtsError(stt::ErrorCode::INVALID_HANDLE, "Invalid engine handle");
        return env->NewStringUTF("[]");
    }
    
    try {
        const auto voices = engine->getAvailableVoices();
        stt::JsonValue::Array values;
        values.reserve(voices.size());
        for (const auto& voice : voices) {
            values.emplace_back(stt::JsonValue::object({
                {"id", voice.id},
                {"name", voice.displayName},
                {"language", voice.language},
                {"gender", voice.gender},
                {"style", voice.style},
                {"isDefault", voice.isDefault},
                {"isCustom", voice.isCustom}
            }));
        }
        const std::string json = stt::JsonUtils::stringify(
            stt::JsonValue::array(std::move(values))
        );
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& error) {
        LOG_E(TAG, "Voice JSON failed: %s", error.what());
        jni::throwRuntimeException(env, error.what());
        return nullptr;
    } catch (...) {
        jni::throwRuntimeException(env, "Unknown voice JSON failure");
        return nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeSetVoice(
    JNIEnv* env, jobject thiz,
    jlong handle,
    jstring jVoiceId
) {
    clearTtsError();
    auto engine = TtsRouter::getInstance().getEngine(handle);
    if (!engine) {
        setTtsError(stt::ErrorCode::INVALID_HANDLE, "Invalid engine handle");
        return JNI_FALSE;
    }

    jni::JStringGuard voiceId(env, jVoiceId);
    if (!voiceId.valid()) {
        setTtsError(stt::ErrorCode::INVALID_ARGUMENT, "Invalid TTS voice identifier");
        return JNI_FALSE;
    }
    const bool success = engine->setVoice(voiceId.str());
    if (!success) {
        setTtsError(
            stt::ErrorCode::INVALID_ARGUMENT,
            engineErrorOr(engine->getLastError(), "TTS voice is unavailable")
        );
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeGetCurrentVoice(
    JNIEnv* env, jobject thiz, jlong handle
) {
    clearTtsError();
    auto engine = TtsRouter::getInstance().getEngine(handle);
    if (!engine) {
        setTtsError(stt::ErrorCode::INVALID_HANDLE, "Invalid engine handle");
        return nullptr;
    }
    
    std::string voice = engine->getCurrentVoice();
    if (voice.empty()) return nullptr;
    
    return jni::utf8ToJString(env, voice);
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeLoadCustomVoice(
    JNIEnv* env, jobject thiz,
    jlong handle,
    jstring jVoicePath
) {
    clearTtsError();
    auto engine = TtsRouter::getInstance().getEngine(handle);
    if (!engine) {
        setTtsError(stt::ErrorCode::INVALID_HANDLE, "Invalid engine handle");
        return JNI_FALSE;
    }

    jni::JStringGuard voicePath(env, jVoicePath);
    if (!voicePath.valid()) {
        setTtsError(stt::ErrorCode::INVALID_ARGUMENT, "Invalid custom voice path");
        return JNI_FALSE;
    }
    const bool success = engine->loadCustomVoice(voicePath.str());
    if (!success) {
        setTtsError(
            stt::ErrorCode::MODEL_LOAD_FAILED,
            engineErrorOr(engine->getLastError(), "Failed to load custom TTS voice")
        );
    }
    
    return success ? JNI_TRUE : JNI_FALSE;
}

// ============================================================================
// Engine Info
// ============================================================================

JNIEXPORT jint JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeGetSampleRate(
    JNIEnv* env, jobject thiz, jlong handle
) {
    auto engine = TtsRouter::getInstance().getEngine(handle);
    return engine ? engine->getDefaultSampleRate() : 24000;
}

JNIEXPORT jint JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeGetCapabilities(
    JNIEnv* env, jobject thiz, jlong handle
) {
    auto engine = TtsRouter::getInstance().getEngine(handle);
    return engine ? static_cast<jint>(engine->getCapabilities()) : 0;
}

JNIEXPORT jint JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeGetSequenceLength(
    JNIEnv* env, jobject thiz, jlong handle
) {
    auto engine = TtsRouter::getInstance().getEngine(handle);
    return engine ? engine->getSequenceLength() : 0;
}

JNIEXPORT jint JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeGetRemainingCapacity(
    JNIEnv* env, jobject thiz, jlong handle
) {
    auto engine = TtsRouter::getInstance().getEngine(handle);
    return engine ? engine->getRemainingCapacity() : 0;
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeGetLastError(
    JNIEnv* env, jobject thiz
) {
    return jni::utf8ToJString(env, stt::ThreadLocalError::get().message());
}

// ============================================================================
// Engine Availability
// ============================================================================

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeIsEngineAvailable(
    JNIEnv* env, jobject thiz, jint engineType
) {
    return isTtsEngineAvailable(static_cast<TtsEngineType>(engineType)) 
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_sal7one_common_1jni_tts_TtsNative_nativeGetAvailableEngines(
    JNIEnv* env, jobject thiz
) {
    auto engines = getAvailableTtsEngines();
    
    jintArray result = env->NewIntArray(static_cast<jsize>(engines.size()));
    if (result && !engines.empty()) {
        std::vector<jint> intEngines;
        intEngines.reserve(engines.size());
        for (auto e : engines) {
            intEngines.push_back(static_cast<jint>(e));
        }
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(engines.size()), intEngines.data());
    }
    
    return result;
}

} // extern "C"
