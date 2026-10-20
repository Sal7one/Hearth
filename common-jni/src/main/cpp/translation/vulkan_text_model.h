#pragma once
#include "text_model.h"
#include "vulkan_policy.h"
#include "ggml-backend.h"
namespace transiber {
class VulkanTextModel : public TextModel {
    std::string execution;
    static ggml_backend_dev_t gpuDevice() {
        auto device = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU);
        return device ? device : ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU);
    }
    static std::string checkedPath(const std::string& path) {
        initializeRuntime();
        nativeError.clear();
        // This registry belongs only to the separately loaded Vulkan DSO. No
        // Vulkan driver is touched by the default CPU library or CPU sessions.
        const auto device = gpuDevice();
        if (!device || !llama_supports_gpu_offload())
            throw failure("No compatible Vulkan GPU is available for local translation. Select CPU to continue.");
        return path;
    }
public:
    explicit VulkanTextModel(const std::string& path, int promptMode) :
        TextModel(checkedPath(path), 2, 99, promptMode) {
        const auto device = gpuDevice();
        const char* description = device ? ggml_backend_dev_description(device) : nullptr;
        const char* name = device ? ggml_backend_dev_name(device) : nullptr;
        execution = vulkanExecutionInfo(description ? description : "", name ? name : "",
            nativeOffloadSummary, nativeModelBufferSummary);
    }
    const std::string& executionInfo() const { return execution; }
};
}
