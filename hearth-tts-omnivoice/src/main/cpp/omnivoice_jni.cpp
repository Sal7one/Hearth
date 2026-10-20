#include "vendor/omnivoice.h"
#include "lease_registry.h"
#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <cmath>

namespace {
struct Api {
    void* library = nullptr;
#define ENTRY(name) decltype(&ov_##name) name = nullptr
    ENTRY(init); ENTRY(free); ENTRY(init_default_params); ENTRY(tts_default_params);
    ENTRY(synthesize); ENTRY(last_error); ENTRY(audio_free); ENTRY(n_languages); ENTRY(language_id);
#undef ENTRY
    Api() {
        library = dlopen("libhearth_omnivoice.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error(dlerror());
        try {
#define LOAD(name) name = reinterpret_cast<decltype(name)>(dlsym(library, "ov_" #name)); if (!name) throw std::runtime_error("OmniVoice runtime ABI missing: " #name)
            LOAD(init); LOAD(free); LOAD(init_default_params); LOAD(tts_default_params);
            LOAD(synthesize); LOAD(last_error); LOAD(audio_free); LOAD(n_languages); LOAD(language_id);
#undef LOAD
        } catch (...) { dlclose(library); library = nullptr; throw; }
    }
    ~Api() { if (library) dlclose(library); }
};
// Keep the DSO alive for process lifetime, including registry destruction during process shutdown.
Api& api() { static Api* instance = new Api(); return *instance; }
void fail(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    const std::string value = message ? message : "OmniVoice native failure";
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
    if (!value || env->GetArrayLength(value) < 1 || size_t(env->GetArrayLength(value)) > bound) throw std::invalid_argument("Invalid OmniVoice UTF-8 argument");
    std::string out(env->GetArrayLength(value), '\0'); env->GetByteArrayRegion(value, 0, out.size(), reinterpret_cast<jbyte*>(out.data()));
    if (env->ExceptionCheck() || out.find('\0') != std::string::npos) throw std::invalid_argument("Invalid OmniVoice UTF-8 argument");
    return out;
}
struct Voice {
    ov_context* handle = nullptr;
    std::atomic<bool> cancelled{false};
    std::mutex operation;
    ~Voice() { if (handle) api().free(handle); }
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
bool pcm(const float* samples, int count, void* opaque) {
    auto& callback = *static_cast<Callback*>(opaque);
    std::lock_guard<std::mutex> serial(callback.mutex);
    if (callback.voice->cancelled.load() || callback.exception) return false;
    if (!samples || count <= 0 || count > 4 * 1024 * 1024) { callback.failure = "OmniVoice returned invalid or oversized PCM callback data"; return false; }
    JNIEnv* env = nullptr; bool attached = false;
    if (callback.vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
#if defined(__ANDROID__)
        const jint attached_status = callback.vm->AttachCurrentThread(&env, nullptr);
#else
        const jint attached_status = callback.vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
#endif
        if (attached_status != JNI_OK) { callback.failure = "OmniVoice PCM callback could not attach to the JVM"; return false; }
        attached = true;
    }
    ActiveSynthesis callback_active(callback.id, callback.voice);
    bool keep = true;
    for (size_t offset = 0; offset < count && keep; ) {
        const size_t length = std::min<size_t>(callback.rate, size_t(count) - offset);
        for (size_t i = 0; i < length; ++i) if (!std::isfinite(samples[offset+i])) { callback.failure = "OmniVoice returned non-finite PCM"; keep = false; break; }
        if (!keep) break;
        jfloatArray array = env->NewFloatArray(length);
        if (array) env->SetFloatArrayRegion(array, 0, length, samples+offset);
        if (array && !env->ExceptionCheck()) keep = env->CallBooleanMethod(callback.target, callback.method, array, callback.rate);
        else keep = false;
        if (env->ExceptionCheck()) {
            auto thrown = env->ExceptionOccurred(); env->ExceptionClear();
            callback.exception = static_cast<jthrowable>(env->NewGlobalRef(thrown)); env->DeleteLocalRef(thrown); if (!callback.exception) { env->ExceptionClear(); callback.failure = "Could not retain the Java PCM callback exception"; } keep = false;
        }
        if (array) env->DeleteLocalRef(array);
        offset += length;
        if (callback.voice->cancelled.load()) keep = false;
    }
    if (attached) callback.vm->DetachCurrentThread();
    return keep;
}
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_sal7one_common_1jni_voice_OmniVoiceNative_available(JNIEnv*, jobject) {
    try { api(); return true; } catch (...) { return false; }
}
extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_voice_OmniVoiceNative_create(JNIEnv* env, jobject, jbyteArray generator, jbyteArray codec) {
    try {
        auto gen = bytes(env, generator, 4096), dec = bytes(env, codec, 4096);
        ov_init_params config{}; api().init_default_params(&config);
        config.model_path=gen.c_str();config.codec_path=dec.c_str();config.device="CPU";config.use_fa=false;
        auto voice=std::make_unique<Voice>();voice->handle=api().init(&config);
        if (!voice->handle) throw std::runtime_error(api().last_error());
        return voices.insert(std::move(voice));
    } catch (const std::exception& e) { fail(env,e.what());return 0; }
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_sal7one_common_1jni_voice_OmniVoiceNative_languages(JNIEnv* env, jobject) {
    try {
        const int count=api().n_languages();if(count<1 || count>2000)throw std::runtime_error("Invalid OmniVoice language inventory");
        auto out=env->NewObjectArray(count,env->FindClass("java/lang/String"),nullptr);
        if (!out) return nullptr;
        for(int i=0;i<count && !env->ExceptionCheck();++i){const char* id=api().language_id(i);if(!id)throw std::runtime_error("Invalid OmniVoice language id");auto name=env->NewStringUTF(id);if(!name)return nullptr;env->SetObjectArrayElement(out,i,name);env->DeleteLocalRef(name);}
        return out;
    }catch(const std::exception& e){fail(env,e.what());return nullptr;}
}
bool cancelled(void* opaque) { return static_cast<Voice*>(opaque)->cancelled.load(); }
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_voice_OmniVoiceNative_synthesize(JNIEnv* env, jobject, jlong id, jbyteArray text, jbyteArray language, jint design, jobject target) {
    try {
        auto voice=voices.acquire(id);if(!voice)throw std::runtime_error("OmniVoice engine is closed");
        std::lock_guard<std::mutex> serial(voice->operation);ActiveSynthesis active(id,voice.get());
        auto input=bytes(env,text,16000),lang=bytes(env,language,64);
        if(design<0 || design>2)throw std::invalid_argument("Invalid OmniVoice voice design");
        if(!target)throw std::invalid_argument("Missing OmniVoice PCM callback");
        Callback callback{};env->GetJavaVM(&callback.vm);callback.target=env->NewGlobalRef(target);callback.voice=voice.get();callback.rate=24000;callback.id=id;
        auto cls=env->GetObjectClass(target);callback.method=env->GetMethodID(cls,"onPcm","([FI)Z");env->DeleteLocalRef(cls);
        if(!callback.target || !callback.method){if(callback.target)env->DeleteGlobalRef(callback.target);return;}
        ov_tts_params options{};api().tts_default_params(&options);
        options.text=input.c_str();options.lang=lang.c_str();options.instruct=design==1?"female":design==2?"male":nullptr;
        options.chunk_duration_sec=5.f;options.chunk_threshold_sec=5.f;
        options.cancel=cancelled;options.cancel_user_data=voice.get();options.on_chunk=pcm;options.on_chunk_user_data=&callback;
        ov_audio audio{};
        const auto status=voice->cancelled.load()?OV_STATUS_CANCELLED:api().synthesize(voice->handle,&options,&audio);
        api().audio_free(&audio);env->DeleteGlobalRef(callback.target);
        if(callback.exception){env->Throw(callback.exception);env->DeleteGlobalRef(callback.exception);return;}
        if(!callback.failure.empty())throw std::runtime_error(callback.failure);
        if(status==OV_STATUS_CANCELLED || voice->cancelled.load()){env->ThrowNew(env->FindClass("java/util/concurrent/CancellationException"),"OmniVoice speech stopped");return;}
        if(status!=OV_STATUS_OK)throw std::runtime_error(api().last_error());
    }catch(const std::exception& e){fail(env,e.what());}
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_voice_OmniVoiceNative_cancel(JNIEnv*, jobject, jlong id) { if (active_id == id && active_voice) { active_voice->cancel(); return; } auto voice = voices.acquire(id); if (voice) voice->cancel(); }
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_voice_OmniVoiceNative_destroy(JNIEnv*, jobject, jlong id) {
    auto retired = voices.retire(id); if (auto* voice = retired.signalTarget()) voice->cancel();
    if (active_id == id && active_voice) return;
    auto owner = std::move(retired).lockExclusive();
}
