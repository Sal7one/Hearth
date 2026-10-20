#include "vendor/hearth_oute.h"
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
 void* library=nullptr;
#define ENTRY(name) decltype(&hearth_oute_##name) name=nullptr
 ENTRY(abi); ENTRY(create); ENTRY(destroy); ENTRY(synthesize); ENTRY(cancel); ENTRY(last_error);
#undef ENTRY
 Api() {
  library=dlopen("libhearth_oute.so",RTLD_NOW|RTLD_LOCAL);
  if(!library)throw std::runtime_error(dlerror());
  try {
#define LOAD(name) name=reinterpret_cast<decltype(name)>(dlsym(library,"hearth_oute_" #name));if(!name)throw std::runtime_error("OuteTTS runtime ABI missing: " #name)
   LOAD(abi);LOAD(create);LOAD(destroy);LOAD(synthesize);LOAD(cancel);LOAD(last_error);
#undef LOAD
   if(abi()!=HEARTH_OUTE_ABI)throw std::runtime_error("OuteTTS runtime ABI mismatch");
  }catch(...){dlclose(library);library=nullptr;throw;}
 }
};
Api& api(){static Api* instance=new Api();return *instance;}
void fail(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    const std::string value = message ? message : "OuteTTS native failure";
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
    if (!value || env->GetArrayLength(value) < 1 || size_t(env->GetArrayLength(value)) > bound) throw std::invalid_argument("Invalid OuteTTS UTF-8 argument");
    std::string out(env->GetArrayLength(value), '\0'); env->GetByteArrayRegion(value, 0, out.size(), reinterpret_cast<jbyte*>(out.data()));
    if (env->ExceptionCheck() || out.find('\0') != std::string::npos) throw std::invalid_argument("Invalid OuteTTS UTF-8 argument");
    return out;
}

struct Voice {
 hearth_oute* handle=nullptr;
 std::atomic_bool cancelled{false};std::mutex operation;
 ~Voice(){if(handle)api().destroy(handle);}
 void cancel(){cancelled.store(true);if(handle)api().cancel(handle);}
};
stt::concurrency::LeaseRegistry<Voice> voices;
thread_local jlong active_id=0;thread_local Voice* active_voice=nullptr;
struct ActiveSynthesis {
 jlong previous_id;Voice* previous_voice;
 ActiveSynthesis(jlong id,Voice* voice):previous_id(active_id),previous_voice(active_voice){active_id=id;active_voice=voice;}
 ~ActiveSynthesis(){active_id=previous_id;active_voice=previous_voice;}
};
struct Callback {
    JavaVM* vm; jobject target; jmethodID method; Voice* voice; jlong id; int rate; size_t frames = 0;
    std::mutex mutex; jthrowable exception = nullptr; std::string failure;
};
int pcm(void* opaque, const float* samples, int count, int rate) {
    auto& callback = *static_cast<Callback*>(opaque);
    std::lock_guard<std::mutex> serial(callback.mutex);
    if (callback.voice->cancelled.load() || callback.exception) return false;
    if (!samples || count <= 0 || count > 24000 || rate != 24000 || callback.frames + size_t(count) > 24000 * 45) { callback.failure = "OuteTTS returned invalid or oversized PCM callback data"; return false; }
    JNIEnv* env = nullptr; bool attached = false;
    if (callback.vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
#if defined(__ANDROID__)
        const jint attached_status = callback.vm->AttachCurrentThread(&env, nullptr);
#else
        const jint attached_status = callback.vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
#endif
        if (attached_status != JNI_OK) { callback.failure = "OuteTTS PCM callback could not attach to the JVM"; return false; }
        attached = true;
    }
    bool keep = true; callback.frames += count;
    for (size_t offset = 0; offset < count && keep; ) {
        const size_t length = std::min<size_t>(callback.rate, size_t(count) - offset);
        for (size_t i = 0; i < length; ++i) if (!std::isfinite(samples[offset+i])) { callback.failure = "OuteTTS returned non-finite PCM"; keep = false; break; }
        if (!keep) break;
        jfloatArray array = env->NewFloatArray(length);
        if (array) env->SetFloatArrayRegion(array, 0, length, samples+offset);
        if (array && !env->ExceptionCheck()) { ActiveSynthesis active(callback.id,callback.voice); keep = env->CallBooleanMethod(callback.target, callback.method, array, callback.rate); }
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
extern "C" JNIEXPORT jboolean JNICALL Java_com_sal7one_common_1jni_voice_OuteNative_available(JNIEnv*,jobject){try{api();return true;}catch(...){return false;}}
extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_voice_OuteNative_create(JNIEnv* env,jobject,jbyteArray model,jbyteArray profile){
 try {
  auto file=bytes(env,model,4096),speaker=bytes(env,profile,4096);
  auto voice=std::make_unique<Voice>();voice->handle=api().create(file.c_str(),speaker.c_str(),2);
  if(!voice->handle)throw std::runtime_error(api().last_error());
  return voices.insert(std::move(voice));
 }catch(const std::exception& e){fail(env,e.what());return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_voice_OuteNative_synthesize(JNIEnv* env,jobject,jlong id,jbyteArray text,jobject target){
 try {
  if(active_id==id&&active_voice)throw std::runtime_error("OuteTTS synthesis is not reentrant");
  auto voice=voices.acquire(id);if(!voice)throw std::runtime_error("OuteTTS engine is closed");
  std::lock_guard<std::mutex> serial(voice->operation);ActiveSynthesis active(id,voice.get());
  auto input=bytes(env,text,2048);if(!target)throw std::invalid_argument("Missing OuteTTS PCM callback");
  Callback callback{};env->GetJavaVM(&callback.vm);callback.target=env->NewGlobalRef(target);callback.voice=voice.get();callback.id=id;callback.rate=24000;
  auto cls=env->GetObjectClass(target);callback.method=env->GetMethodID(cls,"onPcm","([FI)Z");env->DeleteLocalRef(cls);
  if(!callback.target||!callback.method){if(callback.target)env->DeleteGlobalRef(callback.target);return;}
  const int status=voice->cancelled.load()?1:api().synthesize(voice->handle,input.c_str(),pcm,&callback);
  env->DeleteGlobalRef(callback.target);
  if(callback.exception){env->Throw(callback.exception);env->DeleteGlobalRef(callback.exception);return;}
  if(!callback.failure.empty())throw std::runtime_error(callback.failure);
  if(status==1||voice->cancelled.load()){env->ThrowNew(env->FindClass("java/util/concurrent/CancellationException"),"OuteTTS speech stopped");return;}
  if(status!=0)throw std::runtime_error(api().last_error());
  if(!callback.frames)throw std::runtime_error("OuteTTS returned no audio");
 }catch(const std::exception& e){fail(env,e.what());}
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_voice_OuteNative_cancel(JNIEnv*,jobject,jlong id){if(active_id==id&&active_voice){active_voice->cancel();return;}auto voice=voices.acquire(id);if(voice)voice->cancel();}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_voice_OuteNative_destroy(JNIEnv*,jobject,jlong id){auto retired=voices.retire(id);if(auto* voice=retired.signalTarget())voice->cancel();if(active_id==id&&active_voice)return;auto owner=std::move(retired).lockExclusive();}
