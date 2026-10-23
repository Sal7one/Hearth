#include "vendor/tts.h"
#include "lease_registry.h"
#include <jni.h>
#include <atomic>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
// The desktop stage links the pinned NeMo-Speech.cpp TTS runtime into this dylib,
// so the C ABI is resolved directly instead of Android's dlopen of libhearth_magpie.so.
struct Api {
#define ENTRY(name) decltype(&nemo_speech_tts_##name) name = nemo_speech_tts_##name
    ENTRY(create); ENTRY(destroy); ENTRY(speaker_count); ENTRY(speaker_name); ENTRY(sample_rate);
    ENTRY(runtime_config_default); ENTRY(synthesis_options_default); ENTRY(synthesize_text); ENTRY(last_error);
#undef ENTRY
};
// Keep the runtime alive for process lifetime, including registry destruction during process shutdown.
Api& api() { static Api* instance = new Api(); return *instance; }
void fail(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    const std::string value = message ? message : "Magpie native failure";
    auto string_class = env->FindClass("java/lang/String");
    if (!string_class) return;
    auto exception_class = env->FindClass("java/lang/IllegalStateException");
    if (!string_class || !exception_class) return;
    auto string_constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    if (!string_constructor) return;
    auto exception_constructor = env->GetMethodID(exception_class, "<init>", "(Ljava/lang/String;)V");
    if (!string_constructor || !exception_constructor) return;
    auto encoded = env->NewByteArray(value.size());
    if (!encoded) return;
    env->SetByteArrayRegion(encoded, 0, value.size(), reinterpret_cast<const jbyte*>(value.data()));
    if (env->ExceptionCheck()) { env->DeleteLocalRef(encoded); return; }
    auto charset = env->NewStringUTF("UTF-8");
    if (!charset) { env->DeleteLocalRef(encoded); return; }
    auto text = env->NewObject(string_class, string_constructor, encoded, charset);
    if (text && !env->ExceptionCheck()) {
        auto exception = static_cast<jthrowable>(env->NewObject(exception_class, exception_constructor, text));
        if (exception) { env->Throw(exception); env->DeleteLocalRef(exception); }
    }
    if (text) env->DeleteLocalRef(text);
    if (charset) env->DeleteLocalRef(charset);
    env->DeleteLocalRef(encoded); env->DeleteLocalRef(string_class); env->DeleteLocalRef(exception_class);
}
std::string bytes(JNIEnv* env, jbyteArray value, size_t bound) {
    if (!value || env->GetArrayLength(value) < 1 || size_t(env->GetArrayLength(value)) > bound) throw std::invalid_argument("Invalid Magpie UTF-8 argument");
    std::string out(env->GetArrayLength(value), '\0'); env->GetByteArrayRegion(value, 0, out.size(), reinterpret_cast<jbyte*>(out.data()));
    if (env->ExceptionCheck() || out.find('\0') != std::string::npos) throw std::invalid_argument("Invalid Magpie UTF-8 argument");
    return out;
}
struct Voice {
    nemo_speech_tts_synthesizer* handle = nullptr;
    std::atomic<bool> cancelled{false};
    std::mutex operation;
    ~Voice() { if (handle) api().destroy(handle); }
    void cancel() { cancelled.store(true); }
};
stt::concurrency::LeaseRegistry<Voice> voices;
// A PCM callback may reenter cancel on its synthesis thread; do not recursively lock a shared_mutex.
thread_local jlong active_id = 0;
thread_local Voice* active_voice = nullptr;
struct ActiveSynthesis {
    jlong previous_id; Voice* previous_voice;
    ActiveSynthesis(jlong id, Voice* voice) : previous_id(active_id), previous_voice(active_voice) { active_id = id; active_voice = voice; }
    ~ActiveSynthesis() { active_id = previous_id; active_voice = previous_voice; }
};
struct Callback {
    JavaVM* vm; jobject target; jmethodID method; Voice* voice; int rate; jlong id=0;
    std::mutex mutex; jthrowable exception = nullptr; std::string failure;
};
bool pcm(const uint8_t* samples, size_t count, void* opaque) {
    auto& callback = *static_cast<Callback*>(opaque);
    std::lock_guard<std::mutex> serial(callback.mutex);
    if (callback.voice->cancelled.load() || callback.exception) return false;
    if (!samples || count == 0 || count % 2 || count > 8 * 1024 * 1024) { callback.failure = "Magpie returned invalid or oversized PCM callback data"; return false; }
    JNIEnv* env = nullptr; bool attached = false;
    if (callback.vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
#if defined(__ANDROID__)
        const jint attached_status = callback.vm->AttachCurrentThread(&env, nullptr);
#else
        const jint attached_status = callback.vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
#endif
        if (attached_status != JNI_OK) { callback.failure = "Magpie PCM callback could not attach to the JVM"; return false; }
        attached = true;
    }
    ActiveSynthesis callback_active(callback.id, callback.voice);
    bool keep = true;
    for (size_t offset = 0; offset < count && keep; ) {
        const size_t length = std::min<size_t>(callback.rate, (count - offset) / 2);
        std::vector<jshort> values(length);
        for (size_t i = 0; i < length; ++i) values[i] = static_cast<jshort>(uint16_t(samples[offset + i * 2]) | (uint16_t(samples[offset + i * 2 + 1]) << 8));
        jshortArray array = env->NewShortArray(length);
        if (array) env->SetShortArrayRegion(array, 0, length, values.data());
        if (array && !env->ExceptionCheck()) keep = env->CallBooleanMethod(callback.target, callback.method, array, callback.rate);
        else keep = false;
        if (env->ExceptionCheck()) {
            auto thrown = env->ExceptionOccurred(); env->ExceptionClear();
            callback.exception = static_cast<jthrowable>(env->NewGlobalRef(thrown)); env->DeleteLocalRef(thrown); if (!callback.exception) { env->ExceptionClear(); callback.failure = "Could not retain the Java PCM callback exception"; } keep = false;
        }
        if (array) env->DeleteLocalRef(array);
        offset += length * 2;
        if (callback.voice->cancelled.load()) keep = false;
    }
    if (attached) callback.vm->DetachCurrentThread();
    return keep;
}
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_sal7one_common_1jni_voice_MagpieNative_available(JNIEnv*, jobject) {
    try { api(); return true; } catch (...) { return false; }
}
extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_voice_MagpieNative_create(JNIEnv* env, jobject, jbyteArray generator, jbyteArray codec, jbyteArray tokenizer, jint threads) {
    try {
        auto gen = bytes(env, generator, 4096), dec = bytes(env, codec, 4096), tok = bytes(env, tokenizer, 4096);
        if (threads < 1 || threads > 8) throw std::invalid_argument("Magpie requires 1–8 CPU threads");
        nemo_speech_tts_model_config model{}; model.size = sizeof(model); model.magpie_model = gen.c_str(); model.codec_model = dec.c_str(); model.tokenizer_model_dir = tok.c_str();
        auto runtime = api().runtime_config_default(); runtime.threads = threads; runtime.codec_threads = threads; runtime.codec_queue_depth = 1;
        runtime.lt_backend = NEMO_SPEECH_TTS_BACKEND_CPU; runtime.sampling_backend = NEMO_SPEECH_TTS_BACKEND_CPU;
        runtime.codec_cpu = true;
        nemo_speech_tts_synthesizer_config config{}; config.size = sizeof(config); config.model = &model; config.runtime = &runtime;
        auto voice = std::make_unique<Voice>();
        if (api().create(&config, &voice->handle) != NEMO_SPEECH_TTS_OK) throw std::runtime_error(api().last_error());
        return voices.insert(std::move(voice));
    } catch (const std::exception& e) { fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_sal7one_common_1jni_voice_MagpieNative_voices(JNIEnv* env, jobject, jlong id) {
    try {
        auto voice = voices.acquire(id); if (!voice) throw std::runtime_error("Magpie engine is closed");
        const int count = api().speaker_count(voice->handle); if (count < 1 || count > 100) throw std::runtime_error("Invalid Magpie speaker inventory");
        auto out = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
        for (int i = 0; i < count && !env->ExceptionCheck(); ++i) { auto name = env->NewStringUTF(api().speaker_name(voice->handle, i)); env->SetObjectArrayElement(out, i, name); env->DeleteLocalRef(name); }
        return out;
    } catch (const std::exception& e) { fail(env, e.what()); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_voice_MagpieNative_synthesize(JNIEnv* env, jobject, jlong id, jbyteArray text, jbyteArray language, jbyteArray speaker, jobject target) {
    try {
        auto voice = voices.acquire(id); if (!voice) throw std::runtime_error("Magpie engine is closed");
        std::lock_guard<std::mutex> serial(voice->operation);
        ActiveSynthesis active(id, voice.get());
        auto input = bytes(env, text, 16000), lang = bytes(env, language, 32), name = bytes(env, speaker, 128);
        if (!target) throw std::invalid_argument("Missing Magpie PCM callback");
        const int rate = api().sample_rate(voice->handle); if (rate < 8000 || rate > 96000) throw std::runtime_error("Invalid Magpie sample rate");
        Callback callback{}; env->GetJavaVM(&callback.vm); callback.target = env->NewGlobalRef(target); callback.voice = voice.get(); callback.rate = rate; callback.id=id;
        auto cls = env->GetObjectClass(target); callback.method = env->GetMethodID(cls, "onPcm", "([SI)Z"); env->DeleteLocalRef(cls);
        if (!callback.target || !callback.method) { if (callback.target) env->DeleteGlobalRef(callback.target); return; }
        auto options = api().synthesis_options_default(); options.language_code = lang.c_str(); options.voice_name = name.c_str(); options.speaker = -1;
        auto status = voice->cancelled.load() ? NEMO_SPEECH_TTS_ERROR_CANCELLED : api().synthesize_text(voice->handle, &options, input.c_str(), pcm, &callback, nullptr);
        env->DeleteGlobalRef(callback.target);
        if (callback.exception) { env->Throw(callback.exception); env->DeleteGlobalRef(callback.exception); return; }
        if (!callback.failure.empty()) throw std::runtime_error(callback.failure);
        if (status == NEMO_SPEECH_TTS_ERROR_CANCELLED || voice->cancelled.load()) {
            env->ThrowNew(env->FindClass("java/util/concurrent/CancellationException"), "Magpie speech stopped"); return;
        }
        if (status != NEMO_SPEECH_TTS_OK) throw std::runtime_error(api().last_error());
    } catch (const std::exception& e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_voice_MagpieNative_cancel(JNIEnv*, jobject, jlong id) { if (active_id == id && active_voice) { active_voice->cancel(); return; } auto voice = voices.acquire(id); if (voice) voice->cancel(); }
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_voice_MagpieNative_destroy(JNIEnv*, jobject, jlong id) {
    auto retired = voices.retire(id); if (auto* voice = retired.signalTarget()) voice->cancel();
    // A callback may close its own engine. Its existing lease owns teardown after callback return.
    if (active_id == id && active_voice) return;
    auto owner = std::move(retired).lockExclusive();
}
