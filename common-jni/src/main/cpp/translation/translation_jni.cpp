#include "text_model.h"
#include "../common/lease_registry.h"
#include <jni.h>
namespace {
stt::concurrency::LeaseRegistry<transiber::TextModel> models;
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
    const std::string text(error);
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
// Reject APKs that accidentally bundle an older JNI binary: JNI can resolve
// create(byte[], boolean) to an old create(byte[]) symbol without noticing the
// extra argument, silently selecting a different translation prompt.
extern "C" JNIEXPORT jint JNICALL Java_com_sal7one_common_1jni_translation_LocalTranslationNative_promptProtocolVersion(JNIEnv*, jobject) {
    return 2;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_translation_LocalTranslationNative_create(JNIEnv* env, jobject, jbyteArray path, jboolean rawPrompt) {
    try { return models.insert(std::make_unique<transiber::TextModel>(bytes(env, path), 2, 0, rawPrompt == JNI_TRUE)); }
    catch (const std::exception& e) { fail(env, e.what()); return 0; }
    catch (...) { fail(env, "Unknown local translation initialization failure"); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_translation_LocalTranslationNative_translate(JNIEnv* env, jobject, jlong id, jbyteArray prompt) {
    try {
        auto model = models.acquire(id);
        if (!model) throw std::invalid_argument("Translation model is closed");
        const auto result = model->translate(bytes(env, prompt));
        auto out = env->NewByteArray(result.size());
        if (out) env->SetByteArrayRegion(out, 0, result.size(), reinterpret_cast<const jbyte*>(result.data()));
        return out;
    } catch (const std::exception& e) { fail(env, e.what()); return nullptr; }
    catch (...) { fail(env, "Unknown local translation inference failure"); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_translation_LocalTranslationNative_cancel(JNIEnv*, jobject, jlong id) {
    auto model = models.acquire(id); if (model) model->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_translation_LocalTranslationNative_destroy(JNIEnv*, jobject, jlong id) {
    auto retired = models.retire(id);
    if (auto* model = retired.signalTarget()) model->cancelled.store(true);
    auto exclusive = std::move(retired).lockExclusive();
}
