// =============================================================================
// OrtSession implementation — ORT C API (vendored runtime headers).
// =============================================================================

#include "ort_session.h"

#include <algorithm>
#include <cstring>

#if __has_include("onnxruntime/onnxruntime_c_api.h")
    #include "onnxruntime/onnxruntime_c_api.h"
#elif __has_include("onnxruntime_c_api.h")
    #include "onnxruntime_c_api.h"
#else
    #error "ONNX Runtime headers not found"
#endif

namespace stt::sign {

namespace {
// Raw ORT C API type aliases: this class is itself named OrtSession, so the
// unqualified C types would resolve to the wrapper instead of the runtime.
using RawEnv = ::OrtEnv;
using RawSessionOptions = ::OrtSessionOptions;
using RawSession = ::OrtSession;
using RawValue = ::OrtValue;
using RawStatus = ::OrtStatus;
using RawAllocator = ::OrtAllocator;
using RawTypeInfo = ::OrtTypeInfo;
using RawTensorInfo = ::OrtTensorTypeAndShapeInfo;

// One process-wide ORT environment keeps the thread pool and logging state
// shared with the other ORT engines; sessions stay independently owned.
RawEnv* sharedEnv() {
    static RawEnv* env = [] {
        const OrtApi* api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
        RawEnv* created = nullptr;
        if (api != nullptr) {
            api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "sign-vision", &created);
        }
        return created;
    }();
    return env;
}
}  // namespace

OrtSession::~OrtSession() { release(); }

bool OrtSession::load(const std::string& modelPath, int numThreads) {
    release();

    const OrtApi* api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    RawEnv* env = sharedEnv();
    if (api == nullptr || env == nullptr) {
        lastError_ = "ONNX Runtime API unavailable";
        return false;
    }

    RawStatus* status =
        api->CreateSessionOptions(reinterpret_cast<RawSessionOptions**>(&options_));
    if (status != nullptr) {
        lastError_ = "CreateSessionOptions failed";
        api->ReleaseStatus(status);
        return false;
    }
    api->SetIntraOpNumThreads(reinterpret_cast<RawSessionOptions*>(options_),
                              std::max(1, numThreads));

    status = api->CreateSession(env, modelPath.c_str(),
                                reinterpret_cast<RawSessionOptions*>(options_),
                                reinterpret_cast<RawSession**>(&session_));
    if (status != nullptr) {
        const char* msg = api->GetErrorMessage(status);
        lastError_ = std::string("CreateSession: ") + (msg ? msg : "?");
        api->ReleaseStatus(status);
        release();
        return false;
    }

    RawSession* session = reinterpret_cast<RawSession*>(session_);
    size_t inputCount = 0, outputCount = 0;
    api->SessionGetInputCount(session, &inputCount);
    api->SessionGetOutputCount(session, &outputCount);
    if (inputCount != 1) {
        lastError_ = "Expected exactly 1 input tensor";
        release();
        return false;
    }
    if (outputCount == 0) {
        lastError_ = "Model has no outputs";
        release();
        return false;
    }

    RawAllocator* allocator = nullptr;
    if (api->GetAllocatorWithDefaultOptions(&allocator) != nullptr || allocator == nullptr) {
        lastError_ = "Allocator unavailable";
        release();
        return false;
    }

    char* inputName = nullptr;
    if (api->SessionGetInputName(session, 0, allocator, &inputName) != nullptr) {
        lastError_ = "SessionGetInputName failed";
        release();
        return false;
    }
    inputName_ = inputName;
    api->AllocatorFree(allocator, inputName);

    RawTypeInfo* typeInfo = nullptr;
    api->SessionGetInputTypeInfo(session, 0, &typeInfo);
    const RawTensorInfo* tensorInfo = nullptr;
    if (typeInfo != nullptr) {
        api->CastTypeInfoToTensorInfo(typeInfo, &tensorInfo);
    }
    if (tensorInfo == nullptr) {
        lastError_ = "Input is not a tensor";
        if (typeInfo) api->ReleaseTypeInfo(typeInfo);
        release();
        return false;
    }
    size_t dims = 0;
    api->GetDimensionsCount(tensorInfo, &dims);
    inputShape_.resize(dims);
    api->GetDimensions(tensorInfo, inputShape_.data(), dims);
    if (typeInfo) api->ReleaseTypeInfo(typeInfo);
    if (inputShape_.empty()) {
        lastError_ = "Model input has no static shape";
        release();
        return false;
    }

    outputNames_.clear();
    for (size_t i = 0; i < outputCount; ++i) {
        char* name = nullptr;
        if (api->SessionGetOutputName(session, i, allocator, &name) != nullptr) {
            lastError_ = "SessionGetOutputName failed";
            release();
            return false;
        }
        outputNames_.emplace_back(name);
        api->AllocatorFree(allocator, name);
    }

    lastError_.clear();
    return true;
}

void OrtSession::release() {
    if (session_ == nullptr && options_ == nullptr) {
        // Never loaded (or already released): do not touch the runtime —
        // destroying an unloaded session must stay side-effect free.
        return;
    }
    const OrtApi* api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (api != nullptr) {
        if (session_ != nullptr) {
            api->ReleaseSession(reinterpret_cast<RawSession*>(session_));
            session_ = nullptr;
        }
        if (options_ != nullptr) {
            api->ReleaseSessionOptions(reinterpret_cast<RawSessionOptions*>(options_));
            options_ = nullptr;
        }
    } else {
        session_ = nullptr;
        options_ = nullptr;
    }
    inputName_.clear();
    inputShape_.clear();
    outputNames_.clear();
}

bool OrtSession::invoke(const float* input, size_t inputFloats,
                        std::vector<std::vector<float>>& outputs) {
    outputs.clear();
    if (session_ == nullptr || input == nullptr) {
        lastError_ = session_ == nullptr ? "Session not loaded" : "Null input";
        return false;
    }
    const OrtApi* api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    RawSession* session = reinterpret_cast<RawSession*>(session_);

    int64_t elements = 1;
    for (int64_t d : inputShape_) elements *= d;
    if (elements <= 0 || static_cast<size_t>(elements) != inputFloats) {
        lastError_ = "Input size mismatch: model expects " + std::to_string(elements) +
                     " floats, got " + std::to_string(inputFloats);
        return false;
    }

    OrtMemoryInfo* memoryInfo = nullptr;
    api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memoryInfo);
    RawValue* inputTensor = nullptr;
    RawStatus* status = api->CreateTensorWithDataAsOrtValue(
        memoryInfo, const_cast<float*>(input), sizeof(float) * inputFloats,
        inputShape_.data(), inputShape_.size(),
        ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &inputTensor);
    if (status != nullptr || inputTensor == nullptr) {
        if (memoryInfo) api->ReleaseMemoryInfo(memoryInfo);
        if (status) api->ReleaseStatus(status);
        lastError_ = "Input tensor creation failed";
        return false;
    }

    std::vector<const char*> inNames{inputName_.c_str()};
    std::vector<const char*> outNames;
    outNames.reserve(outputNames_.size());
    for (const auto& name : outputNames_) outNames.push_back(name.c_str());
    std::vector<RawValue*> outputTensors(outputNames_.size(), nullptr);

    status = api->Run(session, nullptr, inNames.data(), &inputTensor, 1,
                      outNames.data(), outNames.size(), outputTensors.data());
    api->ReleaseValue(inputTensor);
    if (status != nullptr) {
        const char* msg = api->GetErrorMessage(status);
        lastError_ = std::string("Inference: ") + (msg ? msg : "?");
        api->ReleaseStatus(status);
        for (RawValue* v : outputTensors) {
            if (v) api->ReleaseValue(v);
        }
        if (memoryInfo) api->ReleaseMemoryInfo(memoryInfo);
        return false;
    }

    outputs.resize(outputTensors.size());
    for (size_t i = 0; i < outputTensors.size(); ++i) {
        RawValue* value = outputTensors[i];
        if (value == nullptr) {
            lastError_ = "Missing output tensor " + std::to_string(i);
            for (RawValue* v : outputTensors) {
                if (v) api->ReleaseValue(v);
            }
            if (memoryInfo) api->ReleaseMemoryInfo(memoryInfo);
            outputs.clear();
            return false;
        }
        float* data = nullptr;
        api->GetTensorMutableData(value, reinterpret_cast<void**>(&data));
        size_t floats = 0;
        RawTensorInfo* info = nullptr;
        if (api->GetTensorTypeAndShape(value, &info) == nullptr && info != nullptr) {
            size_t got = 0;
            if (api->GetTensorShapeElementCount(info, &got) == nullptr) floats = got;
            api->ReleaseTensorTypeAndShapeInfo(info);
        }
        if (data == nullptr || floats == 0) {
            lastError_ = "Empty output tensor " + std::to_string(i);
            for (RawValue* v : outputTensors) {
                if (v) api->ReleaseValue(v);
            }
            if (memoryInfo) api->ReleaseMemoryInfo(memoryInfo);
            outputs.clear();
            return false;
        }
        outputs[i].assign(data, data + floats);
    }
    for (RawValue* v : outputTensors) {
        if (v) api->ReleaseValue(v);
    }
    if (memoryInfo) api->ReleaseMemoryInfo(memoryInfo);
    return true;
}

}  // namespace stt::sign
