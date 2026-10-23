#pragma once
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <sstream>
#include <stdexcept>
#include <string>

// A requested layer count is insufficient: require the loader's real assignment
// and a positive MODEL allocation on the selected device. Host/KV/compute buffers
// do not establish GPU model placement. Successful inference is checked separately.
inline std::string desktopGpuExecutionInfo(const std::string& device, const std::string& name,
    const std::string& summary, const std::string& buffers) {
    const auto marker = summary.find("offloaded ");
    int placed = 0, total = 0;
    if (device.empty() || name.empty() || marker == std::string::npos ||
        std::sscanf(summary.c_str() + marker, "offloaded %d/%d layers to GPU", &placed, &total) != 2 ||
        placed <= 0 || total <= 0 || placed > total)
        throw std::runtime_error("GPU translation did not confirm an actual model layer assignment. Select CPU to continue.");
    std::istringstream lines(buffers);
    std::string line;
    double allocated = 0;
    while (std::getline(lines, line)) {
        char buffer[128]{};
        double mib = 0;
        int consumed = 0;
        if (std::sscanf(line.c_str(), "load_tensors: %127s model buffer size = %lf MiB %n", buffer, &mib, &consumed) != 2 ||
            consumed <= 0 || !std::isfinite(mib) || mib <= 0 ||
            (name != buffer && name + "_Mapped" != buffer) ||
            !std::all_of(line.begin() + consumed, line.end(), [](unsigned char c) { return std::isspace(c); })) continue;
        allocated += mib;
    }
    if (!std::isfinite(allocated) || allocated <= 0)
        throw std::runtime_error("GPU translation did not allocate model buffers on the selected device. Select CPU to continue.");
    return device + "; " + summary + buffers;
}
