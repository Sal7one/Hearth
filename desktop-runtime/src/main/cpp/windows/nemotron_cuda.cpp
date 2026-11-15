#include "nemotron_cuda.h"

#include "backend_abi.h"
#include "ggml-backend.h"

#include <cuda_runtime.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <vector>

namespace {

// The matrix kernels that stand in for cuBLAS are compiled as compute_80 PTX, which a
// driver can only translate for Ampere (RTX 30-series) and newer.
constexpr int kMinimumComputeMajor = 8;
constexpr size_t kMaximumDevices = 64;

struct Device {
    int index;
    std::string name, description;
    size_t memory, free;
    int major, minor;
    bool usable() const { return major >= kMinimumComputeMajor; }
};

std::vector<Device> devices() {
    std::vector<Device> found;
    for (size_t i = 0; i < ggml_backend_dev_count() && found.size() < kMaximumDevices; ++i) {
        const ggml_backend_dev_t device = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(device);
        if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) continue;
        // NeMo picks the Nth GPU in this registry order, so the position in `found` is the index
        // it expects. CUDA is the only GPU backend compiled in, and names its devices CUDA<id>.
        const char* name = ggml_backend_dev_name(device);
        char* end = nullptr;
        const long id = name && std::strncmp(name, "CUDA", 4) == 0 ? std::strtol(name + 4, &end, 10) : -1;
        if (id < 0 || !end || *end) throw std::runtime_error("The GPU registry holds a device that is not a CUDA device");
        cudaDeviceProp properties{};
        const cudaError_t status = cudaGetDeviceProperties(&properties, static_cast<int>(id));
        if (status != cudaSuccess) throw std::runtime_error(std::string("CUDA could not describe ") + name + ": " + cudaGetErrorString(status));
        size_t free = 0, total = 0;
        ggml_backend_dev_memory(device, &free, &total);
        const char* description = ggml_backend_dev_description(device);
        found.push_back({static_cast<int>(found.size()), name, description && *description ? description : name, total, free, properties.major, properties.minor});
    }
    return found;
}

std::string capability(const Device& device) { return std::to_string(device.major) + "." + std::to_string(device.minor); }

std::string json(const std::string& text) {
    std::string out = "\"";
    for (const unsigned char c : text) {
        if (c == '"' || c == '\\') { out += '\\'; out += static_cast<char>(c); }
        else if (c < 0x20) { char escaped[8]; std::snprintf(escaped, sizeof(escaped), "\\u%04x", c); out += escaped; }
        else out += static_cast<char>(c);
    }
    return out + "\"";
}

// 0 with the text in `buffer`, or 2 when it does not fit. The buffer always ends in NUL.
int deliver(const std::string& text, char* buffer, size_t capacity) {
    if (!buffer || capacity == 0) return 2;
    const size_t count = text.size() < capacity ? text.size() : capacity - 1;
    std::memcpy(buffer, text.data(), count);
    buffer[count] = '\0';
    return count == text.size() ? 0 : 2;
}

}  // namespace

namespace hearth::nemotron {

int selectDevice(const HearthSpeechConfig& config, std::string& execution) {
    const std::string requested = config.acceleration ? config.acceleration : "";
    int index = 0;
    if (requested.rfind("cuda:", 0) == 0 && requested.size() > 5 && requested.size() <= 8 &&
        requested.find_first_not_of("0123456789", 5) == std::string::npos) {
        index = std::atoi(requested.c_str() + 5);
    } else if (requested != "cuda") {
        throw std::invalid_argument("This Nemotron library runs on NVIDIA CUDA only; \"" + requested + "\" was requested");
    }
    for (const auto& device : devices()) {
        if (device.index != index) continue;
        if (!device.usable())
            throw std::runtime_error(device.description + " has CUDA compute capability " + capability(device) +
                "; Nemotron on CUDA needs " + std::to_string(kMinimumComputeMajor) + ".0 (RTX 30-series) or newer");
        execution = "CUDA \xC2\xB7 " + device.description + " \xC2\xB7 NeMo GGML";
        return index;
    }
    throw std::runtime_error("NVIDIA CUDA device " + std::to_string(index) + " was requested but is not present");
}

}  // namespace hearth::nemotron

// The GPUs this library can run on, as a JSON array of {index, name, description, memoryBytes, freeBytes}.
// No model is loaded. Returns 0 with the JSON in `buffer`; 1 with the reason there instead;
// 2 when the buffer is too small.
extern "C" __declspec(dllexport) int hearth_speech_gpu_devices_v1(char* buffer, size_t capacity) {
    try {
        std::string list = "[";
        for (const auto& device : devices()) {
            if (!device.usable()) continue;
            if (list.size() > 1) list += ',';
            list += "{\"index\":" + std::to_string(device.index) + ",\"name\":" + json(device.name) +
                ",\"description\":" + json(device.description) + ",\"memoryBytes\":" + std::to_string(device.memory) +
                ",\"freeBytes\":" + std::to_string(device.free) + "}";
        }
        return deliver(list + "]", buffer, capacity);
    } catch (const std::exception& failure) {
        return deliver(failure.what(), buffer, capacity) == 0 ? 1 : 2;
    } catch (...) {
        return deliver("Unknown CUDA device inventory failure", buffer, capacity) == 0 ? 1 : 2;
    }
}
