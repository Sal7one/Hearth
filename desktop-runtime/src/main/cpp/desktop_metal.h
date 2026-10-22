#pragma once
#include "desktop_gpu.h"

// Compiled support and a usable runtime device are separate requirements.
inline ggml_backend_dev_t desktopMetalDevice() {
    return desktopGpuDevice(DesktopGpuMode::METAL,0);
}
inline void desktopRequireMetal() {
    (void)desktopRequireGpu(DesktopGpuMode::METAL,0);
}
inline bool desktopMetalAvailable() {
    // Do not initialize ggml's Metal registry here: the newer llama registry JITs every kernel.
    // Capability means compiled support + hardware presence; selected model creation still
    // requires successful backend initialization and actual model offload.
    return desktopGpuAvailable(DesktopGpuMode::METAL,0);
}
