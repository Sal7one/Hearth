#pragma once
#include "text_model.h"
#include "opencl_policy.h"
#include "../common/opencl_loader.h"
#include "ggml-backend.h"
namespace transiber {
class OpenClTextModel : public TextModel {
    std::string execution;
    static const std::string& gpuDescription() {
        static const std::string description = hearth::opencl::requireAdreno();
        return description;
    }
    static ggml_backend_dev_t gpuDevice() {
        auto device = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU);
        return device ? device : ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU);
    }
    static std::string checkedPath(const std::string& path) {
        gpuDescription();
        initializeRuntime();
        const auto device = gpuDevice();
        if (!device || !llama_supports_gpu_offload())
            throw failure("No compatible Adreno OpenCL GPU is available. Select Vulkan or CPU.");
        return path;
    }
public:
    explicit OpenClTextModel(const std::string& path, int promptMode) :
        TextModel(checkedPath(path), 2, 99, promptMode) {
        const auto device = gpuDevice();
        const char* name = device ? ggml_backend_dev_name(device) : nullptr;
        execution = openclExecutionInfo(gpuDescription(), name ? name : "", nativeOffloadSummary, nativeModelBufferSummary);
    }
    const std::string& executionInfo() const { return execution; }
};
}
