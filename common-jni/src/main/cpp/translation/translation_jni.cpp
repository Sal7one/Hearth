#include "text_model.h"
#if defined(HEARTH_TRANSLATION_OPENCL)
#include "opencl_text_model.h"
#define HEARTH_TRANSLATION_JNI(name) Java_com_sal7one_common_1jni_translation_LocalTranslationOpenClNative_##name
#elif defined(HEARTH_TRANSLATION_VULKAN)
#include "vulkan_text_model.h"
#define HEARTH_TRANSLATION_JNI(name) Java_com_sal7one_common_1jni_translation_LocalTranslationVulkanNative_##name
#else
#define HEARTH_TRANSLATION_JNI(name) Java_com_sal7one_common_1jni_translation_LocalTranslationNative_##name
#endif
#include "../common/lease_registry.h"
#include <jni.h>
namespace {
#if defined(HEARTH_TRANSLATION_OPENCL)
stt::concurrency::LeaseRegistry<transiber::OpenClTextModel> models;
#elif defined(HEARTH_TRANSLATION_VULKAN)
stt::concurrency::LeaseRegistry<transiber::VulkanTextModel> models;
#else
stt::concurrency::LeaseRegistry<transiber::TextModel> models;
#endif
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
// Reject APKs that accidentally bundle an older JNI binary: JNI can resolve
// create(byte[], boolean) to an old create(byte[]) symbol without noticing the
// extra argument, silently selecting a different translation prompt.
extern "C" JNIEXPORT jint JNICALL HEARTH_TRANSLATION_JNI(promptProtocolVersion)(JNIEnv*, jobject) {
    return 2;
}
#ifdef HEARTH_TRANSLATION_OPENCL
extern "C" JNIEXPORT void JNICALL HEARTH_TRANSLATION_JNI(configureCache)(JNIEnv* env, jobject, jbyteArray path) {
    try {
        // The caller supplies an app-private cache directory. A missing path
        // explicitly disables disk caching; never use /tmp on Android.
        const auto directory = bytes(env, path);
        if (!directory.empty() && directory.front() != '/')
            throw std::invalid_argument("OpenCL kernel cache must use an app-private absolute path");
        if (setenv("GGML_OPENCL_KERNEL_CACHE_DIR", directory.empty() ? "off" : directory.c_str(), 1) != 0)
            throw std::runtime_error("Could not configure the OpenCL kernel cache");
    } catch (const std::exception& error) { fail(env, error.what()); }
}
#endif
extern "C" JNIEXPORT jlong JNICALL HEARTH_TRANSLATION_JNI(create)(JNIEnv* env, jobject, jbyteArray path, jboolean rawPrompt) {
    try {
#if defined(HEARTH_TRANSLATION_OPENCL)
        return models.insert(std::make_unique<transiber::OpenClTextModel>(bytes(env, path), rawPrompt == JNI_TRUE));
#elif defined(HEARTH_TRANSLATION_VULKAN)
        return models.insert(std::make_unique<transiber::VulkanTextModel>(bytes(env, path), rawPrompt == JNI_TRUE));
#else
        return models.insert(std::make_unique<transiber::TextModel>(bytes(env, path), 2, 0, rawPrompt == JNI_TRUE));
#endif
    }
    catch (const std::exception& e) { fail(env, e.what()); return 0; }
    catch (...) { fail(env, "Unknown local translation initialization failure"); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL HEARTH_TRANSLATION_JNI(translate)(JNIEnv* env, jobject, jlong id, jbyteArray prompt) {
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
extern "C" JNIEXPORT void JNICALL HEARTH_TRANSLATION_JNI(cancel)(JNIEnv*, jobject, jlong id) {
    auto model = models.acquire(id); if (model) model->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL HEARTH_TRANSLATION_JNI(destroy)(JNIEnv*, jobject, jlong id) {
    auto retired = models.retire(id);
    if (auto* model = retired.signalTarget()) model->cancelled.store(true);
    auto exclusive = std::move(retired).lockExclusive();
}
#if defined(HEARTH_TRANSLATION_VULKAN) || defined(HEARTH_TRANSLATION_OPENCL)
extern "C" JNIEXPORT jbyteArray JNICALL HEARTH_TRANSLATION_JNI(executionInfo)(JNIEnv* env, jobject, jlong id) {
    try {
        auto model = models.acquire(id);
        if (!model) throw std::invalid_argument("Translation model is closed");
        const auto& info = model->executionInfo();
        auto out = env->NewByteArray(info.size());
        if (out) env->SetByteArrayRegion(out, 0, info.size(), reinterpret_cast<const jbyte*>(info.data()));
        return out;
    } catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
    catch (...) { fail(env, "Unknown GPU translation status failure"); return nullptr; }
}
#endif
