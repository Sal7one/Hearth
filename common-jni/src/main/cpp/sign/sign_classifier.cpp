// =============================================================================
// Sign-language landmark classifier implementation — ORT C API (vendored
// runtime, same one Marian/OCR/TTS use).
// =============================================================================

#include "sign_classifier.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

#if __has_include("onnxruntime/onnxruntime_c_api.h")
    #include "onnxruntime/onnxruntime_c_api.h"
#elif __has_include("onnxruntime_c_api.h")
    #include "onnxruntime_c_api.h"
#else
    #error "ONNX Runtime headers not found"
#endif

namespace stt::sign {

namespace {
constexpr int kMaxClasses = 128;
constexpr const char* kInputName = "landmarks";
constexpr const char* kOutputName = "logits";
}  // namespace

SignClassifier::~SignClassifier() { release(); }

bool SignClassifier::load(const std::string& modelPath, int numThreads) {
    release();

    const OrtApi* api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (api == nullptr) {
        lastError_ = "ONNX Runtime API unavailable";
        return false;
    }

    OrtStatus* status = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "sign-classifier",
                                       reinterpret_cast<OrtEnv**>(&env_));
    if (status != nullptr) {
        lastError_ = "CreateEnv failed";
        api->ReleaseStatus(status);
        return false;
    }

    status = api->CreateSessionOptions(reinterpret_cast<OrtSessionOptions**>(&options_));
    if (status != nullptr) {
        lastError_ = "CreateSessionOptions failed";
        api->ReleaseStatus(status);
        release();
        return false;
    }
    api->SetIntraOpNumThreads(reinterpret_cast<OrtSessionOptions*>(options_),
                              std::max(1, numThreads));

    status = api->CreateSession(reinterpret_cast<OrtEnv*>(env_), modelPath.c_str(),
                                reinterpret_cast<OrtSessionOptions*>(options_),
                                reinterpret_cast<OrtSession**>(&session_));
    if (status != nullptr) {
        const char* msg = api->GetErrorMessage(status);
        lastError_ = std::string("CreateSession: ") + (msg ? msg : "?");
        api->ReleaseStatus(status);
        release();
        return false;
    }

    // Contract check: exactly one rank-2 input and one rank-2 output.
    OrtSession* session = reinterpret_cast<OrtSession*>(session_);
    size_t inputCount = 0, outputCount = 0;
    api->SessionGetInputCount(session, &inputCount);
    api->SessionGetOutputCount(session, &outputCount);
    if (inputCount != 1 || outputCount != 1) {
        lastError_ = "Expected exactly 1 input and 1 output tensor";
        release();
        return false;
    }

    auto introspect = [&](bool isInput) -> int {
        OrtTypeInfo* typeInfo = nullptr;
        if (isInput) {
            api->SessionGetInputTypeInfo(session, 0, &typeInfo);
        } else {
            api->SessionGetOutputTypeInfo(session, 0, &typeInfo);
        }
        if (typeInfo == nullptr) return 0;

        const OrtTensorTypeAndShapeInfo* tensorInfo = nullptr;
        api->CastTypeInfoToTensorInfo(typeInfo, &tensorInfo);
        int size = 0;
        if (tensorInfo != nullptr) {
            size_t dims = 0;
            api->GetDimensionsCount(tensorInfo, &dims);
            if (dims == 2) {
                int64_t shape[2] = {0, 0};
                api->GetDimensions(tensorInfo, shape, 2);
                size = static_cast<int>(shape[1]);
            }
        }
        api->ReleaseTypeInfo(typeInfo);
        return size;
    };

    inputSize_ = introspect(true);
    classCount_ = introspect(false);

    if (inputSize_ <= 0 || classCount_ <= 0 || classCount_ > kMaxClasses) {
        lastError_ = "Unexpected model shapes (need rank-2 [batch,63] -> [batch,N])";
        release();
        return false;
    }

    lastError_.clear();
    return true;
}

void SignClassifier::release() {
    if (session_ == nullptr && options_ == nullptr && env_ == nullptr) {
        return;  // Never loaded: destroying stays side-effect free.
    }
    const OrtApi* api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (api != nullptr) {
        if (session_ != nullptr) {
            api->ReleaseSession(reinterpret_cast<OrtSession*>(session_));
            session_ = nullptr;
        }
        if (options_ != nullptr) {
            api->ReleaseSessionOptions(reinterpret_cast<OrtSessionOptions*>(options_));
            options_ = nullptr;
        }
        if (env_ != nullptr) {
            api->ReleaseEnv(reinterpret_cast<OrtEnv*>(env_));
            env_ = nullptr;
        }
    } else {
        session_ = nullptr;
        options_ = nullptr;
        env_ = nullptr;
    }
    inputSize_ = 0;
    classCount_ = 0;
}

SignClassification SignClassifier::classify(const float* features, int featureCount) {
    SignClassification result;
    if (session_ == nullptr || features == nullptr || featureCount != inputSize_) {
        lastError_ = session_ == nullptr ? "Classifier not loaded"
                                         : "Feature size mismatch";
        return result;
    }

    const OrtApi* api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    OrtSession* session = reinterpret_cast<OrtSession*>(session_);

    OrtMemoryInfo* memoryInfo = nullptr;
    api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memoryInfo);

    int64_t inputShape[2] = {1, static_cast<int64_t>(inputSize_)};
    OrtValue* inputTensor = nullptr;
    OrtStatus* status = api->CreateTensorWithDataAsOrtValue(
        memoryInfo, const_cast<float*>(features), sizeof(float) * inputSize_,
        inputShape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &inputTensor);
    if (status != nullptr || inputTensor == nullptr) {
        if (memoryInfo) api->ReleaseMemoryInfo(memoryInfo);
        if (status) api->ReleaseStatus(status);
        lastError_ = "Input tensor creation failed";
        return result;
    }

    const char* inputNames[] = {kInputName};
    const char* outputNames[] = {kOutputName};
    OrtValue* outputTensor = nullptr;

    status = api->Run(session, nullptr, inputNames, &inputTensor, 1, outputNames, 1,
                      &outputTensor);
    api->ReleaseValue(inputTensor);
    if (status != nullptr) {
        if (memoryInfo) api->ReleaseMemoryInfo(memoryInfo);
        const char* msg = api->GetErrorMessage(status);
        lastError_ = std::string("Inference: ") + (msg ? msg : "?");
        api->ReleaseStatus(status);
        return result;
    }

    float* outputData = nullptr;
    api->GetTensorMutableData(outputTensor, reinterpret_cast<void**>(&outputData));
    if (outputData != nullptr && classCount_ > 0) {
        result.probs.resize(classCount_);
        float maxVal = -std::numeric_limits<float>::infinity();
        int maxIdx = 0;
        for (int i = 0; i < classCount_; ++i) {
            if (std::isfinite(outputData[i]) && outputData[i] > maxVal) {
                maxVal = outputData[i];
                maxIdx = i;
            }
        }
        float sum = 0.f;
        for (int i = 0; i < classCount_; ++i) {
            result.probs[i] = std::isfinite(outputData[i])
                                  ? std::exp(outputData[i] - maxVal)
                                  : 0.f;
            sum += result.probs[i];
        }
        if (sum > 0.f) {
            for (int i = 0; i < classCount_; ++i) result.probs[i] /= sum;
        }
        result.classIndex = maxIdx;
        result.confidence = result.probs[maxIdx];
    } else {
        lastError_ = "Classifier returned no output data";
    }

    if (outputTensor) api->ReleaseValue(outputTensor);
    if (memoryInfo) api->ReleaseMemoryInfo(memoryInfo);
    return result;
}

}  // namespace stt::sign
