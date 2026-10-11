#include "vision_model.h"
#include "../common/lease_registry.h"
#include "../translation/runtime_jni.h"
#include <jni.h>

namespace {
stt::concurrency::LeaseRegistry<hearth::vision::VisionModel> models;
std::string path(JNIEnv* env, jbyteArray input) {
    if (!input) throw std::invalid_argument("Missing vision OCR model path");
    const auto length = env->GetArrayLength(input);
    if (length < 1 || length > 4096) throw std::invalid_argument("Vision OCR model path exceeds 4096 bytes or is empty");
    std::string result(length, '\0');
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot read vision OCR model path");
    std::vector<std::uint16_t> validated;
    if (result.find('\0') != std::string::npos || !common_jni::text::utf8ToUtf16(result, validated))
        throw std::invalid_argument("Vision OCR model path must be valid UTF-8 without NUL");
    return result;
}
}
extern "C" JNIEXPORT jint JNICALL Java_com_sal7one_common_1jni_vision_VisionOcrNative_protocolVersion(JNIEnv*, jobject) { return 1; }
extern "C" JNIEXPORT jint JNICALL Java_com_sal7one_common_1jni_vision_VisionOcrNative_pointerWidth(JNIEnv*, jobject) { return sizeof(void*) * 8; }
extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_vision_VisionOcrNative_create(JNIEnv* env, jobject, jbyteArray decoder, jbyteArray projector, jint threads) {
    try { return models.insert(std::make_unique<hearth::vision::VisionModel>(path(env, decoder), path(env, projector), threads)); }
    catch (const std::exception& e) { transiber::throwNativeError(env, e.what()); return 0; }
    catch (...) { transiber::throwNativeError(env, "Unknown vision OCR initialization failure"); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_vision_VisionOcrNative_recognize(JNIEnv* env, jobject, jlong handle, jintArray pixels, jint width, jint height) {
    try {
        auto model = models.acquire(handle);
        if (!model) throw std::invalid_argument("Vision OCR model is closed");
        if (!pixels) throw std::invalid_argument("Vision OCR pixels are missing");
        const auto count = hearth::vision::validateImage(width, height, env->GetArrayLength(pixels));
        std::vector<jint> argb(count);
        env->GetIntArrayRegion(pixels, 0, count, argb.data());
        if (env->ExceptionCheck()) return nullptr;
        std::vector<unsigned char> rgb(count * 3);
        for (std::size_t i = 0; i < count; ++i) {
            const auto value = static_cast<std::uint32_t>(argb[i]);
            rgb[i * 3] = hearth::vision::opaqueChannel(value, 16);
            rgb[i * 3 + 1] = hearth::vision::opaqueChannel(value, 8);
            rgb[i * 3 + 2] = hearth::vision::opaqueChannel(value, 0);
        }
        // No JVM primitive array is pinned during long native inference.
        argb.clear(); argb.shrink_to_fit();
        const auto text = model->recognize(rgb.data(), width, height);
        auto result = env->NewByteArray(static_cast<jsize>(text.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        return result;
    } catch (const std::exception& e) { transiber::throwNativeError(env, e.what()); return nullptr; }
    catch (...) { transiber::throwNativeError(env, "Unknown vision OCR inference failure"); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_vision_VisionOcrNative_cancel(JNIEnv* env, jobject, jlong handle) {
    try { auto model = models.acquire(handle); if (model) model->cancelled.store(true); }
    catch (const std::exception& e) { transiber::throwNativeError(env, e.what()); }
    catch (...) { transiber::throwNativeError(env, "Unknown vision OCR cancellation failure"); }
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_vision_VisionOcrNative_destroy(JNIEnv* env, jobject, jlong handle) {
    try {
        auto retired = models.retire(handle);
        if (auto* model = retired.signalTarget()) model->cancelled.store(true);
        auto exclusive = std::move(retired).lockExclusive();
    } catch (const std::exception& e) { transiber::throwNativeError(env, e.what()); }
    catch (...) { transiber::throwNativeError(env, "Unknown vision OCR teardown failure"); }
}
