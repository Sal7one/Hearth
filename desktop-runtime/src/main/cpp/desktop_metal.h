#pragma once
#include "ggml-backend.h"
#include <cstring>
#include <stdexcept>
#include <mutex>

#if HEARTH_DESKTOP_METAL
extern "C" bool hearth_desktop_metal_device_present();
#endif

// Compiled support and a usable runtime device are separate requirements.
inline ggml_backend_dev_t desktopMetalDevice() {
#if HEARTH_DESKTOP_METAL
    ggml_backend_load_all();
    for (size_t index = 0; index < ggml_backend_dev_count(); ++index) {
        auto device = ggml_backend_dev_get(index);
        const auto type = ggml_backend_dev_type(device);
        const auto registry = ggml_backend_dev_backend_reg(device);
        // The pinned Whisper registry calls it Metal; the newer pinned llama registry calls it MTL.
        const auto name = registry ? ggml_backend_reg_name(registry) : "";
        if ((type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) &&
            (std::strcmp(name, "Metal") == 0 || std::strcmp(name, "MTL") == 0)) return device;
    }
#endif
    return nullptr;
}
inline void desktopRequireMetal() {
#if HEARTH_DESKTOP_METAL
    auto device = desktopMetalDevice();
    if (!device) throw std::runtime_error("Metal acceleration requested, but no Metal GPU is available");
    auto backend = ggml_backend_dev_init(device, nullptr);
    if (!backend) throw std::runtime_error("Metal acceleration requested, but the Metal GPU could not initialize");
    ggml_backend_free(backend);
#else
    throw std::runtime_error("Metal acceleration requested, but this desktop runtime was built for CPU only");
#endif
}
inline bool desktopMetalAvailable() {
    // Do not initialize ggml's Metal registry here: the newer llama registry JITs every kernel.
    // Capability means compiled support + hardware presence; selected model creation still
    // requires successful backend initialization and actual model offload.
#if HEARTH_DESKTOP_METAL
    return hearth_desktop_metal_device_present();
#else
    return false;
#endif
}
