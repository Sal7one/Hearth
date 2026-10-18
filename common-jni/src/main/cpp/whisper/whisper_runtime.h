#ifndef HEARTH_WHISPER_RUNTIME_H
#define HEARTH_WHISPER_RUNTIME_H

#include "whisper_runtime_api.h"
#include <dlfcn.h>
#include <mutex>
#include <stdexcept>
#include <string>

namespace stt {
class WhisperRuntime {
public:
    static const HearthWhisperApi& cpu() {
        static const HearthWhisperApi api = hearthWhisperCpuApi();
        return api;
    }
    static const HearthWhisperApi& vulkan() {
        // Invoked only on explicit opt-in. Keep the DSO loaded for process
        // lifetime: backend registries, worker callbacks, and other sessions
        // may retain addresses after one model has been released.
        static std::mutex mutex;
        static const HearthWhisperApi* api = nullptr;
        std::lock_guard<std::mutex> lock(mutex);
        if (api) return *api;
#if defined(__APPLE__)
        const char* name = "libhearth_whisper_vulkan.dylib";
#else
        const char* name = "libhearth_whisper_vulkan.so";
#endif
        void* library = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        if (!library) {
            const char* error = dlerror();
            throw std::runtime_error(std::string("Whisper Vulkan runtime is unavailable: ") +
                                     (error ? error : "loader failed") + ". Choose CPU to retry.");
        }
        auto get = reinterpret_cast<const HearthWhisperApi* (*)()>(
            dlsym(library, "hearth_whisper_vulkan_api_v1"));
        const HearthWhisperApi* candidate = get ? get() : nullptr;
        if (!hearthWhisperApiValid(candidate)) {
            dlclose(library);
            throw std::runtime_error("Whisper Vulkan runtime version or source revision mismatch. Choose CPU to retry.");
        }
        // Probe only the explicit GPU path. Failure is retriable after a
        // driver update and cannot poison the CPU default.
        // Once validated, retain the module even when driver probing fails;
        // backend registries can already own Vulkan callbacks at that point.
        api = candidate;
        (void)api->deviceDescription();
        return *api;
    }
};
}
#endif
