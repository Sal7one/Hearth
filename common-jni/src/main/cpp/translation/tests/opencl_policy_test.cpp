#include "../opencl_policy.h"
#include <iostream>
int main() {
    int checks = 0;
    const std::string assigned = "load_tensors: offloaded 25/25 layers to GPU\n";
    const std::string allocated = "load_tensors:      OpenCL model buffer size =    12.50 MiB\n";
    for (const auto& input : {"", "load_tensors: offloaded 0/25 layers to GPU\n",
             "load_tensors: offloaded 30/25 layers to GPU\n", "offloaded -1/25 layers to GPU",
             "offloading 24 repeating layers to GPU"}) {
        try { transiber::openclExecutionInfo("Adreno test", "GPUOpenCL", input, allocated); return 1; }
        catch (const std::runtime_error&) { ++checks; }
    }
    const auto result = transiber::openclExecutionInfo("Adreno test", "GPUOpenCL", assigned, allocated);
    if (result.find("Adreno test") == std::string::npos || result.find("loader assigned 25/25") == std::string::npos ||
        result.find("GPU model buffers 12.50 MiB") == std::string::npos ||
        result.find("unsupported operations may use CPU") == std::string::npos) return 1;
    ++checks;
    const auto partial = transiber::openclExecutionInfo("Adreno 730", "GPUOpenCL",
        "load_tensors: offloaded 12/25 layers to GPU\n", allocated + allocated +
        "load_tensors:          CPU model buffer size = 100.00 MiB\n" +
        "load_tensors:  Vulkan_Host model buffer size = 100.00 MiB\n" +
        "load_tensors:      Vulkan1 model buffer size = 100.00 MiB\n");
    if (partial.find("loader assigned 12/25") == std::string::npos ||
        partial.find("GPU model buffers 25.00 MiB") == std::string::npos) return 1;
    ++checks;
    for (const std::string buffer : {"", "load_tensors: OpenCL model buffer size = 0.00 MiB\n",
             "load_tensors: CPU model buffer size = 12.50 MiB\n",
             "load_tensors: Vulkan_Host model buffer size = 12.50 MiB\n",
             "load_tensors: Vulkan1 model buffer size = 12.50 MiB\n",
             "load_tensors: OpenCL KV buffer size = 12.50 MiB\n",
             "load_tensors: OpenCL compute buffer size = 12.50 MiB\n",
             "load_tensors: OpenCL model buffer size = -1.00 MiB\n",
             "load_tensors: OpenCL model buffer size = nan MiB\n",
             "load_tensors: OpenCL model buffer size = inf MiB\n",
             "load_tensors: OpenCL model buffer size = 12.50 bogus\n"}) {
        try { transiber::openclExecutionInfo("Adreno test", "GPUOpenCL", assigned, buffer); return 1; }
        catch (const std::runtime_error&) { ++checks; }
    }
    for (const std::string name : {"", "CPU", "Vulkan_Host", "OpenCL_Host", "OpenCL", "Vulkan0"}) {
        try { transiber::openclExecutionInfo("Adreno test", name, assigned, allocated); return 1; }
        catch (const std::runtime_error&) { ++checks; }
    }
    try { transiber::openclExecutionInfo("", "GPUOpenCL", assigned, allocated); return 1; }
    catch (const std::runtime_error&) { ++checks; }
    std::cout << "OpenCL execution status: " << checks << " checks passed\n";
}
