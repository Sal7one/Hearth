#include "supertonic.h"
#include "../common/lease_registry.h"
#include <jni.h>
#include <stdexcept>
namespace {
stt::concurrency::LeaseRegistry<hearth::voice::Supertonic> voices;
void fail(JNIEnv* env,const char* message){if(!env->ExceptionCheck())env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),message);}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_voice_SupertonicNative_create(JNIEnv* env,jobject,jbyteArray path) {
    try {
        if(!path||env->GetArrayLength(path)<1||env->GetArrayLength(path)>4096)throw std::invalid_argument("Invalid voice model path");
        std::string dir(env->GetArrayLength(path),'\0');env->GetByteArrayRegion(path,0,dir.size(),reinterpret_cast<jbyte*>(dir.data()));
        if(env->ExceptionCheck())return 0;
        if(dir.find('\0')!=std::string::npos)throw std::invalid_argument("NUL in model path");
        return voices.insert(std::make_unique<hearth::voice::Supertonic>(dir));
    }catch(const std::exception& e){fail(env,e.what());return 0;}catch(...){fail(env,"Voice initialization failed");return 0;}
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_sal7one_common_1jni_voice_SupertonicNative_synthesize(JNIEnv* env,jobject,jlong id,jlongArray text,jfloatArray ttl,jfloatArray dp,jint steps,jfloat speed) {
    try {
        auto voice=voices.acquire(id);if(!voice)throw std::runtime_error("Voice model is closed");
        if(!text||env->GetArrayLength(text)<1||env->GetArrayLength(text)>1024||!ttl||!dp||env->GetArrayLength(ttl)!=12800||env->GetArrayLength(dp)!=128)
            throw std::invalid_argument("Invalid speech tokens or voice style");
        std::vector<int64_t> ids(env->GetArrayLength(text));std::vector<float> a(12800),b(128);
        env->GetLongArrayRegion(text,0,ids.size(),reinterpret_cast<jlong*>(ids.data()));env->GetFloatArrayRegion(ttl,0,a.size(),a.data());env->GetFloatArrayRegion(dp,0,b.size(),b.data());
        if(env->ExceptionCheck())return nullptr;
        auto samples=voice->synthesize(ids,std::move(a),std::move(b),steps,speed);
        auto result=env->NewFloatArray(samples.size());if(result)env->SetFloatArrayRegion(result,0,samples.size(),samples.data());return result;
    }catch(const std::exception& e){fail(env,e.what());return nullptr;}catch(...){fail(env,"Speech synthesis failed");return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_voice_SupertonicNative_cancel(JNIEnv*,jobject,jlong id){auto voice=voices.acquire(id);if(voice)voice->cancel();}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_voice_SupertonicNative_destroy(JNIEnv*,jobject,jlong id){auto retired=voices.retire(id);if(auto* voice=retired.signalTarget())voice->cancel();auto owner=std::move(retired).lockExclusive();}
