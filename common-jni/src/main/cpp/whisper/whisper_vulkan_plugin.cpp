#include "whisper_runtime_api.h"
#include "ggml-vulkan.h"
#include "ggml-backend.h"
#include <mutex>
#include <stdexcept>
#include <string>

namespace {
const char* describeDevice() {
    static std::mutex mutex;
    static std::string description;
    std::lock_guard<std::mutex> lock(mutex);
    if (!description.empty()) return description.c_str();
    if (ggml_backend_vk_get_device_count() < 1)
        throw std::runtime_error("Whisper Vulkan found no compatible GPU. Choose CPU to retry.");
    auto backend = ggml_backend_vk_init(0);
    if (!backend)
        throw std::runtime_error("Whisper Vulkan could not initialize GPU 0. Choose CPU to retry.");
    char name[256]{};
    ggml_backend_vk_get_device_description(0, name, sizeof(name));
    ggml_backend_free(backend);
    description = std::string("Vulkan GPU: ") + name + " + CPU";
    return description.c_str();
}
}

extern "C" __attribute__((visibility("default")))
const HearthWhisperApi* hearth_whisper_vulkan_api_v1() {
    static const HearthWhisperApi api = {
        1, sizeof(HearthWhisperApi), HEARTH_WHISPER_REVISION,
#define HEARTH_WHISPER_ADDRESS(name) &name,
        HEARTH_WHISPER_FUNCTIONS(HEARTH_WHISPER_ADDRESS)
#undef HEARTH_WHISPER_ADDRESS
        &describeDevice
    };
    return &api;
}
