#pragma once
#include <cstdio>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <iomanip>
#include <sstream>
#include <stdexcept>
#include <string>
namespace transiber {
// The loader's layer message reflects its requested assignment. Confirm a
// positive allocation of the selected Vulkan device's actual MODEL buffers as
// independent evidence; CPU/Vulkan_Host/KV/compute buffers do not establish it.
inline std::string vulkanExecutionInfo(const std::string& device, const std::string& deviceName,
    const std::string& summary, const std::string& modelBuffers) {
    const auto start = summary.find("offloaded ");
    int placed = 0, total = 0;
    if (device.empty() || start == std::string::npos ||
        std::sscanf(summary.c_str() + start, "offloaded %d/%d layers to GPU", &placed, &total) != 2 ||
        placed <= 0 || total <= 0 || placed > total)
        throw std::runtime_error("Vulkan translation did not confirm a model layer assignment. Select CPU to continue.");
    constexpr size_t prefixLength = 6; // "Vulkan", followed only by a device index.
    if (deviceName.compare(0, prefixLength, "Vulkan") != 0 || deviceName.size() <= prefixLength ||
        !std::all_of(deviceName.begin() + prefixLength, deviceName.end(),
            [](unsigned char value) { return std::isdigit(value) != 0; }))
        throw std::runtime_error("Vulkan translation selected an invalid GPU device name. Select CPU to continue.");
    std::istringstream lines(modelBuffers);
    std::string line;
    double gpuMiB = 0;
    while (std::getline(lines, line)) {
        char name[128]{};
        double mib = 0;
        int consumed = 0;
        if (std::sscanf(line.c_str(), "load_tensors: %127s model buffer size = %lf MiB %n", name, &mib, &consumed) != 2 ||
            consumed <= 0 || std::string(name) != deviceName || !std::isfinite(mib) || mib <= 0 ||
            !std::all_of(line.begin() + consumed, line.end(), [](unsigned char value) { return std::isspace(value) != 0; })) continue;
        gpuMiB += mib;
    }
    if (!std::isfinite(gpuMiB) || gpuMiB <= 0)
        throw std::runtime_error("Vulkan translation did not allocate model buffers on the selected GPU. Select CPU to continue.");
    std::ostringstream info;
    info << "Vulkan · " << device << " · loader assigned " << placed << '/' << total <<
        " layers; GPU model buffers " << std::fixed << std::setprecision(2) << gpuMiB <<
        " MiB. Unsupported operations may use CPU.";
    return info.str();
}
}
