#include "speech_session.h"
#include "../common/lease_registry.h"
#include "../common/pcm_buffer_range.h"
#include "../jni/jni_helper.h"
#include <cstring>

namespace {
stt::concurrency::LeaseRegistry<stt::speech::SpeechNativeSession> sessions;
using stt::JsonUtils;
using stt::JsonValue;
void throwSpeechError(JNIEnv* env, const char* text) {
    if (env->ExceptionCheck()) return;
    // ThrowNew expects modified UTF-8. Backend messages are standard UTF-8.
    const auto message = jni::utf8ToJString(env, text ? text : "Speech native error");
    if (!message) return;
    const auto cls = env->FindClass("java/lang/RuntimeException");
    if (!cls) { env->DeleteLocalRef(message); return; }
    const auto ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;)V");
    if (ctor) {
        const auto exception = static_cast<jthrowable>(env->NewObject(cls, ctor, message));
        if (exception) { env->Throw(exception); env->DeleteLocalRef(exception); }
    }
    env->DeleteLocalRef(cls); env->DeleteLocalRef(message);
}
}
extern "C" {
JNIEXPORT jstring JNICALL Java_com_sal7one_common_1jni_speech_SpeechNative_probe(JNIEnv* env, jobject, jstring backend) {
    JNI_TRY_CATCH_BEGIN
    jni::JStringGuard id(env, backend);
    if (!id.valid()) return nullptr;
    try {
        const auto& api = stt::speech::loadBackend(id.str());
        return jni::utf8ToJString(env, JsonUtils::stringify(JsonValue::object({{"available", true}, {"revision", api.revision}, {"error", ""}})));
    } catch (const std::exception& e) {
        return jni::utf8ToJString(env, JsonUtils::stringify(JsonValue::object({{"available", false}, {"revision", ""}, {"error", e.what()}})));
    }
    } catch (const std::exception& e) { throwSpeechError(env, e.what()); return nullptr; }
    catch (...) { throwSpeechError(env, "Unknown speech native exception"); return nullptr; }
}
JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_speech_SpeechNative_create(JNIEnv* env, jobject, jstring config) {
    JNI_TRY_CATCH_BEGIN
    jni::JStringGuard text(env, config);
    if (!text.valid()) return 0;
    stt::speech::SpeechConfig c(text.str());
    auto session = std::make_unique<stt::speech::SpeechNativeSession>(stt::speech::loadBackend(c.backend), c.value);
    const auto handle = sessions.insert(std::move(session));
    if (!handle) throw std::runtime_error("Speech handle registry exhausted");
    return handle;
    } catch (const std::exception& e) { throwSpeechError(env, e.what()); return 0; }
    catch (...) { throwSpeechError(env, "Unknown speech native exception"); return 0; }
}
JNIEXPORT jstring JNICALL Java_com_sal7one_common_1jni_speech_SpeechNative_push(JNIEnv* env, jobject, jlong handle, jobject buffer, jint offset, jint bytes, jlong sampleOffset) {
    JNI_TRY_CATCH_BEGIN
    auto lease = sessions.acquire(handle);
    if (!lease) throw std::invalid_argument("Speech session is closed or invalid");
    if (!buffer) throw std::invalid_argument("Speech requires a direct PCM16 buffer");
    const auto capacity = env->GetDirectBufferCapacity(buffer);
    if (env->ExceptionCheck()) return nullptr;
    if (const char* e = stt::pcmBufferRangeError(capacity, offset, bytes, 16000)) throw std::invalid_argument(e);
    if (bytes > 32000) throw std::invalid_argument("Speech push exceeds one second of PCM16");
    auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!data) throw std::invalid_argument("Speech requires a direct PCM16 buffer");
    std::vector<float> samples(bytes / 2);
    // Explicit little-endian decoding, independent of buffer alignment/order.
    for (size_t i = 0; i < samples.size(); ++i) {
        const auto p = data + offset + i * 2;
        const int value = static_cast<int>(p[0]) | (static_cast<int>(p[1]) << 8);
        samples[i] = static_cast<float>(value >= 32768 ? value - 65536 : value) / 32768.0f;
    }
    std::lock_guard<std::mutex> lock(lease->mutex);
    return jni::utf8ToJString(env, lease->push(samples.data(), samples.size(), sampleOffset));
    } catch (const std::exception& e) { throwSpeechError(env, e.what()); return nullptr; }
    catch (...) { throwSpeechError(env, "Unknown speech native exception"); return nullptr; }
}
JNIEXPORT jstring JNICALL Java_com_sal7one_common_1jni_speech_SpeechNative_finish(JNIEnv* env, jobject, jlong handle) {
    JNI_TRY_CATCH_BEGIN
    auto lease = sessions.acquire(handle);
    if (!lease) throw std::invalid_argument("Speech session is closed or invalid");
    std::lock_guard<std::mutex> lock(lease->mutex);
    return jni::utf8ToJString(env, lease->finish());
    } catch (const std::exception& e) { throwSpeechError(env, e.what()); return nullptr; }
    catch (...) { throwSpeechError(env, "Unknown speech native exception"); return nullptr; }
}
JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_speech_SpeechNative_reset(JNIEnv* env, jobject, jlong handle) {
    JNI_TRY_CATCH_BEGIN
    auto lease = sessions.acquire(handle);
    if (!lease) throw std::invalid_argument("Speech session is closed or invalid");
    std::lock_guard<std::mutex> lock(lease->mutex); lease->reset();
    } catch (const std::exception& e) { throwSpeechError(env, e.what()); }
    catch (...) { throwSpeechError(env, "Unknown speech native exception"); }
}
JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_speech_SpeechNative_destroy(JNIEnv* env, jobject, jlong handle) {
    JNI_TRY_CATCH_BEGIN
    auto retired = sessions.retire(handle);
    auto exclusive = std::move(retired).lockExclusive();
    } catch (const std::exception& e) { throwSpeechError(env, e.what()); }
    catch (...) { throwSpeechError(env, "Unknown speech native exception"); }
}
}
