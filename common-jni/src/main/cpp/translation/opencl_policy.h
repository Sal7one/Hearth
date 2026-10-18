#pragma once
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <iomanip>
#include <sstream>
#include <stdexcept>
#include <string>
namespace transiber {
// The pinned OpenCL backend names its selected GPU GPUOpenCL and its device
// model buffer OpenCL. CPU, host, KV and compute allocations do not prove model
// offload. requireAdreno() restricts the registry to one actual Adreno device.
inline std::string openclExecutionInfo(const std::string& device, const std::string& deviceName,
    const std::string& summary, const std::string& modelBuffers) {
    const auto start = summary.find("offloaded ");
    int placed = 0, total = 0;
    if (device.find("Adreno") == std::string::npos || deviceName != "GPUOpenCL" || start == std::string::npos ||
        std::sscanf(summary.c_str() + start, "offloaded %d/%d layers to GPU", &placed, &total) != 2 ||
        placed <= 0 || total <= 0 || placed > total)
        throw std::runtime_error("OpenCL translation did not confirm Adreno model layer placement. Select Vulkan or CPU.");
    std::istringstream lines(modelBuffers);
    std::string line;
    double gpuMiB = 0;
    while (std::getline(lines, line)) {
        char name[128]{};
        double mib = 0;
        int consumed = 0;
        if (std::sscanf(line.c_str(), "load_tensors: %127s model buffer size = %lf MiB %n", name, &mib, &consumed) != 2 ||
            consumed <= 0 || std::string(name) != "OpenCL" || !std::isfinite(mib) || mib <= 0 ||
            !std::all_of(line.begin() + consumed, line.end(), [](unsigned char value) { return std::isspace(value) != 0; })) continue;
        gpuMiB += mib;
    }
    if (!std::isfinite(gpuMiB) || gpuMiB <= 0)
        throw std::runtime_error("OpenCL translation did not allocate model buffers on Adreno. Select Vulkan or CPU.");
    std::ostringstream info;
    info << "OpenCL · " << device << " · loader assigned " << placed << '/' << total <<
        " layers; GPU model buffers " << std::fixed << std::setprecision(2) << gpuMiB <<
        " MiB. Adreno kernels enabled; unsupported operations may use CPU.";
    return info.str();
}
}
