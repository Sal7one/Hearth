#include "ct2_model.h"
#include "../../common/lease_registry.h"
#include <jni.h>
namespace {
stt::concurrency::LeaseRegistry<hearth::Ct2Model> models;
std::string bytes(JNIEnv* env, jbyteArray value) {
    if (!value) throw std::invalid_argument("Missing UTF-8 input");
    const auto size = env->GetArrayLength(value);
    if (size > 12000) throw std::invalid_argument("UTF-8 input exceeds 12000 bytes");
    std::string text(size, '\0');
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(text.data()));
    if (text.find('\0') != std::string::npos) throw std::invalid_argument("Embedded NUL in translation input");
    return text;
}
void fail(JNIEnv* env, const char* error) {
    if (env->ExceptionCheck()) return;
    // Decode native UTF-8 using Java's UTF-8 constructor, never NewStringUTF.
#ifdef HEARTH_TRANSLATION_OPENCL
    const std::string text = transiber::failure(error).what();
#else
    const std::string text(error);
#endif
    auto data = env->NewByteArray(text.size());
    if (!data) return;
    env->SetByteArrayRegion(data, 0, text.size(), reinterpret_cast<const jbyte*>(text.data()));
    if (env->ExceptionCheck()) return;
    auto cls = env->FindClass("java/lang/String");
    if (!cls) return;
    auto charset = env->NewStringUTF("UTF-8");
    if (!charset) return;
    const auto stringCtor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    if (!stringCtor) return;
    auto message = env->NewObject(cls, stringCtor, data, charset);
    if (!message) return;
    auto ex = env->FindClass("java/lang/RuntimeException");
    if (!ex) return;
    const auto errorCtor = env->GetMethodID(ex, "<init>", "(Ljava/lang/String;)V");
    if (!errorCtor) return;
    auto instance = static_cast<jthrowable>(env->NewObject(ex, errorCtor, message));
    if (instance) env->Throw(instance);
}
}
extern "C" JNIEXPORT jint JNICALL Java_com_sal7one_common_1jni_translation_Ct2Native_protocolVersion(JNIEnv*, jobject) { return 1; }
extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_translation_Ct2Native_create(JNIEnv* env, jobject, jbyteArray path, jint mode) {
 try { return models.insert(std::make_unique<hearth::Ct2Model>(bytes(env,path), mode)); }
 catch (const std::exception& e) { fail(env,e.what()); return 0; }
 catch (...) { fail(env,"Unknown CT2 load failure"); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_translation_Ct2Native_translate(JNIEnv* env, jobject, jlong handle, jbyteArray text, jbyteArray language) {
 try {
  auto model=models.acquire(handle); if(!model) throw std::runtime_error("CT2 session is closed");
  const auto out=model->translate(bytes(env,text),bytes(env,language));
  auto result=env->NewByteArray(out.size());
  if(result) env->SetByteArrayRegion(result,0,out.size(),reinterpret_cast<const jbyte*>(out.data()));
  return result;
 } catch(const std::exception& e) { fail(env,e.what()); return nullptr; }
 catch (...) { fail(env,"Unknown CT2 inference failure"); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_translation_Ct2Native_cancel(JNIEnv*, jobject, jlong handle) {
 auto model=models.acquire(handle); if(model)model->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_translation_Ct2Native_destroy(JNIEnv*, jobject, jlong handle) {
 auto retired=models.retire(handle); if(auto* model=retired.signalTarget())model->cancelled.store(true);
 auto exclusive=std::move(retired).lockExclusive();
}
