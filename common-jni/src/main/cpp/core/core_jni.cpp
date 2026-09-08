/**
 * JNI bindings for classes under com.sal7one.common_jni.core.*
 *
 * Only live bindings live here. Previous SttKit / SttSession /
 * GpuAccelerator bindings were under a different package
 * (com.sal7one.stt.core.*) and never resolved at runtime; they have
 * been removed. Global init / cancelAll / error APIs live in
 * common_jni_bridge.cpp via RegisterNatives.
 */

#include <jni.h>
#include <cmath>
#include <exception>
#include <string>
#include "vad.h"
#include "../common/logging.h"
#include "../common/json_utils.h"
#include "../jni/jni_helper.h"
#include "../jni/native_registry.h"

using namespace stt;

namespace {
using VadRegistry = jni::NativeRegistry<VadDetector, jni::NativeHandleKind::Vad>;

VadRegistry& vadRegistry() {
    static VadRegistry registry;
    return registry;
}
} // namespace

extern "C" {

// ============================================================================
// VadDetector — com.sal7one.common_jni.core.VadDetector
// ============================================================================

JNIEXPORT jlong JNICALL
Java_com_sal7one_common_1jni_core_VadDetector_nativeCreate(
    JNIEnv* env, jobject,
    jfloat speechThresholdDb,
    jfloat silenceThresholdDb,
    jint minSpeechMs,
    jint minSilenceMs,
    jint windowMs,
    jint sampleRate
) {
    VadDetector::Config config;
    config.speechThresholdDb = speechThresholdDb;
    config.silenceThresholdDb = silenceThresholdDb;
    config.minSpeechMs = minSpeechMs;
    config.minSilenceMs = minSilenceMs;
    config.windowMs = windowMs;
    config.sampleRate = sampleRate;

    std::string error;
    if (!VadDetector::validateConfig(config, &error)) {
        jni::throwIllegalArgumentException(env, error.c_str());
        return 0;
    }
    try {
        return vadRegistry().store(std::make_unique<VadDetector>(config));
    } catch (const std::exception& exception) {
        LOG_E("VadJNI", "Failed to create native VAD detector: %s", exception.what());
        jni::throwRuntimeException(env, exception.what());
        return 0;
    } catch (...) {
        LOG_E("VadJNI", "Unknown native VAD creation failure");
        jni::throwRuntimeException(env, "Unknown native VAD creation failure");
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_core_VadDetector_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    (void)vadRegistry().remove(handle);
}

static std::string segmentsToJson(const std::vector<VadSegment>& segments) {
    JsonValue::Array values;
    values.reserve(segments.size());
    for (const auto& segment : segments) {
        values.emplace_back(JsonValue::object({
            {"startMs", segment.startMs},
            {"endMs", segment.endMs},
            {"confidence", std::isfinite(segment.confidence) ? segment.confidence : 0.0f},
            {"isSpeech", segment.isSpeech}
        }));
    }
    return JsonUtils::stringify(JsonValue::array(std::move(values)));
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_core_VadDetector_nativeProcess(
    JNIEnv* env, jobject, jlong handle, jshortArray samples, jint count
) {
    auto vad = vadRegistry().acquireSerialized(handle);
    if (!vad || !samples || count <= 0) return env->NewStringUTF("[]");

    jsize arrLen = env->GetArrayLength(samples);
    if (count > arrLen) count = arrLen;

    // Critical array avoids a copy when possible; JNI_ABORT = no write-back.
    jshort* data = static_cast<jshort*>(env->GetPrimitiveArrayCritical(samples, nullptr));
    if (!data) return env->NewStringUTF("[]");

    std::vector<VadSegment> segs;
    try {
        segs = vad->process(data, count);
    } catch (...) {
        env->ReleasePrimitiveArrayCritical(samples, data, JNI_ABORT);
        return env->NewStringUTF("[]");
    }
    env->ReleasePrimitiveArrayCritical(samples, data, JNI_ABORT);

    return env->NewStringUTF(segmentsToJson(segs).c_str());
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_core_VadDetector_nativePushAudio(
    JNIEnv* env, jobject, jlong handle, jshortArray samples, jint count
) {
    auto vad = vadRegistry().acquireSerialized(handle);
    if (!vad || !samples || count <= 0) return;

    jsize arrLen = env->GetArrayLength(samples);
    if (count > arrLen) count = arrLen;

    jshort* data = static_cast<jshort*>(env->GetPrimitiveArrayCritical(samples, nullptr));
    if (!data) return;
    try {
        vad->pushAudio(data, count);
    } catch (...) {}
    env->ReleasePrimitiveArrayCritical(samples, data, JNI_ABORT);
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_core_VadDetector_nativeGetSegments(JNIEnv* env, jobject, jlong handle) {
    auto vad = vadRegistry().acquireSerialized(handle);
    if (!vad) return env->NewStringUTF("[]");
    return env->NewStringUTF(segmentsToJson(vad->getSegments()).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_sal7one_common_1jni_core_VadDetector_nativeFinalize(JNIEnv* env, jobject, jlong handle) {
    auto vad = vadRegistry().acquireSerialized(handle);
    if (!vad) return env->NewStringUTF("[]");
    auto seg = vad->finalize();
    if (seg.endMs <= seg.startMs) return env->NewStringUTF("[]");
    return env->NewStringUTF(segmentsToJson({seg}).c_str());
}

JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_core_VadDetector_nativeReset(JNIEnv*, jobject, jlong handle) {
    auto vad = vadRegistry().acquireSerialized(handle);
    if (vad) vad->reset();
}

JNIEXPORT jboolean JNICALL
Java_com_sal7one_common_1jni_core_VadDetector_nativeIsSpeaking(JNIEnv*, jobject, jlong handle) {
    auto vad = vadRegistry().acquireSerialized(handle);
    return (vad && vad->isSpeaking()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_sal7one_common_1jni_core_VadDetector_nativeGetCurrentEnergyDb(JNIEnv*, jobject, jlong handle) {
    auto vad = vadRegistry().acquireSerialized(handle);
    return vad ? vad->getCurrentEnergyDb() : -100.0f;
}

} // extern "C"
