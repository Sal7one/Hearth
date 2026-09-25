// =============================================================================
// Sign-language JNI surface — hand-landmark pipeline + landmark classifier.
//
// Kotlin side: com.sal7one.common_jni.sign.SignNative. Two independent lease
// registries; teardown follows the house pattern (unpublish -> drain) via
// LeaseRegistry. Model-load failures surface their real message.
//
// nativeDetectHands flat layout:
//   [handCount,
//    per hand: palmScore, handPresence, handednessRight, 21 * (x, y, z)]
// =============================================================================

#include "hand_landmarks.h"
#include "sign_classifier.h"
#include "../common/lease_registry.h"

#include <jni.h>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

stt::concurrency::LeaseRegistry<stt::sign::HandLandmarkPipeline> handPipelines;
stt::concurrency::LeaseRegistry<stt::sign::SignClassifier> classifiers;

void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
    }
}

// Reads a UTF-8 model path from a byte array, rejecting embedded NUL and
// oversized paths (JNI strings are modified-UTF-8; byte arrays are not).
std::string pathFromBytes(JNIEnv* env, jbyteArray bytes) {
    if (!bytes || env->GetArrayLength(bytes) < 1 || env->GetArrayLength(bytes) > 4096) {
        throw std::invalid_argument("Invalid model path");
    }
    std::string path(static_cast<size_t>(env->GetArrayLength(bytes)), '\0');
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(path.size()),
                            reinterpret_cast<jbyte*>(path.data()));
    if (env->ExceptionCheck()) throw std::invalid_argument("Model path read failed");
    if (path.find('\0') != std::string::npos) {
        throw std::invalid_argument("NUL in model path");
    }
    return path;
}

constexpr int kMaxHands = 4;
constexpr int kMaxClassCount = 128;

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_sal7one_common_1jni_sign_SignNative_nativeCreateHandPipeline(JNIEnv* env,
                                                                     jobject,
                                                                     jbyteArray dir,
                                                                     jint threads) {
    try {
        const std::string modelDir = pathFromBytes(env, dir);
        auto pipeline = std::make_unique<stt::sign::HandLandmarkPipeline>();
        if (!pipeline->init(modelDir, threads)) {
            throw std::runtime_error("Hand model setup failed: " + pipeline->lastError());
        }
        return handPipelines.insert(std::move(pipeline));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    } catch (...) {
        fail(env, "Hand pipeline initialization failed");
        return 0;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_sal7one_common_1jni_sign_SignNative_nativeDetectHands(
    JNIEnv* env, jobject, jlong handle, jobject frame, jint width, jint height,
    jfloat palmThreshold, jint maxHands) {
    try {
        auto pipeline = handPipelines.acquire(handle);
        if (!pipeline) throw std::runtime_error("Hand pipeline is closed");
        if (width <= 0 || height <= 0) throw std::invalid_argument("Invalid frame size");
        if (maxHands < 1 || maxHands > kMaxHands) {
            throw std::invalid_argument("Invalid hand count");
        }
        if (env->GetDirectBufferCapacity(frame) <
            static_cast<jlong>(width) * height * 4) {
            throw std::invalid_argument("Frame buffer smaller than width*height*4");
        }
        const auto* rgba = reinterpret_cast<const uint8_t*>(
            env->GetDirectBufferAddress(frame));
        if (rgba == nullptr) throw std::invalid_argument("Frame must be a direct buffer");

        const std::vector<stt::sign::HandLandmarkResult> hands =
            pipeline->detect(rgba, width, height, palmThreshold, maxHands);

        const int count = static_cast<int>(hands.size());
        // Layout: [handCount, per hand: palmScore, handPresence,
        // handednessRight, 21 * (x, y, z)]
        const size_t perHand = 3 + stt::sign::HandLandmarkResult::kPoints * 3;
        std::vector<float> flat(1 + static_cast<size_t>(count) * perHand);
        flat[0] = static_cast<float>(count);
        for (int i = 0; i < count; ++i) {
            float* out = flat.data() + 1 + static_cast<size_t>(i) * perHand;
            out[0] = hands[i].score;
            out[1] = hands[i].handPresence;
            out[2] = hands[i].handednessRight;
            for (int p = 0; p < stt::sign::HandLandmarkResult::kPoints; ++p) {
                out[3 + p * 3] = hands[i].points[p].x;
                out[4 + p * 3] = hands[i].points[p].y;
                out[5 + p * 3] = hands[i].points[p].z;
            }
        }
        jfloatArray result = env->NewFloatArray(flat.size());
        if (result != nullptr) {
            env->SetFloatArrayRegion(result, 0, flat.size(), flat.data());
        }
        return result;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    } catch (...) {
        fail(env, "Hand detection failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_sign_SignNative_nativeDestroyHandPipeline(JNIEnv*,
                                                                      jobject,
                                                                      jlong handle) {
    auto retired = handPipelines.retire(handle);
    // Inference holds no internal state to cancel between frames; the
    // exclusive lease only waits for an in-flight detect() to return.
    static_cast<void>(std::move(retired).lockExclusive());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_sal7one_common_1jni_sign_SignNative_nativeCreateClassifier(JNIEnv* env,
                                                                   jobject,
                                                                   jbyteArray path,
                                                                   jint threads) {
    try {
        const std::string modelPath = pathFromBytes(env, path);
        auto classifier = std::make_unique<stt::sign::SignClassifier>();
        if (!classifier->load(modelPath, threads)) {
            throw std::runtime_error("Classifier load failed: " + classifier->lastError());
        }
        return classifiers.insert(std::move(classifier));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    } catch (...) {
        fail(env, "Classifier initialization failed");
        return 0;
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_sal7one_common_1jni_sign_SignNative_nativeClassifierShape(JNIEnv* env,
                                                                   jobject,
                                                                   jlong handle) {
    try {
        auto classifier = classifiers.acquire(handle);
        if (!classifier) throw std::runtime_error("Classifier is closed");
        jint shape[2] = {classifier->inputSize(), classifier->classCount()};
        jintArray result = env->NewIntArray(2);
        if (result != nullptr) env->SetIntArrayRegion(result, 0, 2, shape);
        return result;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    } catch (...) {
        fail(env, "Classifier shape query failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_sal7one_common_1jni_sign_SignNative_nativeClassify(JNIEnv* env, jobject,
                                                            jlong handle,
                                                            jfloatArray landmarks) {
    try {
        auto classifier = classifiers.acquire(handle);
        if (!classifier) throw std::runtime_error("Classifier is closed");
        const jsize length = env->GetArrayLength(landmarks);
        if (length != classifier->inputSize()) {
            throw std::invalid_argument("Landmark vector must match classifier input");
        }
        std::vector<float> features(static_cast<size_t>(length));
        env->GetFloatArrayRegion(landmarks, 0, length, features.data());
        if (env->ExceptionCheck()) return nullptr;

        const stt::sign::SignClassification result =
            classifier->classify(features.data(), static_cast<int>(features.size()));
        if (result.classIndex < 0 || result.probs.size() > kMaxClassCount) {
            throw std::runtime_error("Classification failed: " + classifier->lastError());
        }
        jfloatArray probs = env->NewFloatArray(result.probs.size());
        if (probs != nullptr) {
            env->SetFloatArrayRegion(probs, 0, result.probs.size(), result.probs.data());
        }
        return probs;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    } catch (...) {
        fail(env, "Classification failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sal7one_common_1jni_sign_SignNative_nativeDestroyClassifier(JNIEnv*, jobject,
                                                                    jlong handle) {
    auto retired = classifiers.retire(handle);
    static_cast<void>(std::move(retired).lockExclusive());
}
