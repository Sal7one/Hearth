#include "../vulkan_policy.h"
#include <iostream>
int main() {
    int checks = 0;
    const std::string assigned = "load_tensors: offloaded 25/25 layers to GPU\n";
    const std::string allocated = "load_tensors:      Vulkan0 model buffer size =    12.50 MiB\n";
    for (const auto& input : {"", "load_tensors: offloaded 0/25 layers to GPU\n",
             "load_tensors: offloaded 30/25 layers to GPU\n", "offloaded -1/25 layers to GPU",
             "offloading 24 repeating layers to GPU"}) {
        try { transiber::vulkanExecutionInfo("Test GPU", "Vulkan0", input, allocated); return 1; }
        catch (const std::runtime_error&) { ++checks; }
    }
    const auto result = transiber::vulkanExecutionInfo("Adreno test", "Vulkan0", assigned, allocated);
    if (result.find("Adreno test") == std::string::npos || result.find("loader assigned 25/25") == std::string::npos ||
        result.find("GPU model buffers 12.50 MiB") == std::string::npos ||
        result.find("Unsupported operations may use CPU") == std::string::npos) return 1;
    ++checks;
    const auto partial = transiber::vulkanExecutionInfo("Mali test", "Vulkan0",
        "load_tensors: offloaded 12/25 layers to GPU\n", allocated + allocated +
        "load_tensors:          CPU model buffer size = 100.00 MiB\n" +
        "load_tensors:  Vulkan_Host model buffer size = 100.00 MiB\n" +
        "load_tensors:      Vulkan1 model buffer size = 100.00 MiB\n");
    if (partial.find("loader assigned 12/25") == std::string::npos ||
        partial.find("GPU model buffers 25.00 MiB") == std::string::npos) return 1;
    ++checks;
    for (const std::string buffer : {"", "load_tensors: Vulkan0 model buffer size = 0.00 MiB\n",
             "load_tensors: CPU model buffer size = 12.50 MiB\n",
             "load_tensors: Vulkan_Host model buffer size = 12.50 MiB\n",
             "load_tensors: Vulkan1 model buffer size = 12.50 MiB\n",
             "load_tensors: Vulkan0 KV buffer size = 12.50 MiB\n",
             "load_tensors: Vulkan0 compute buffer size = 12.50 MiB\n",
             "load_tensors: Vulkan0 model buffer size = -1.00 MiB\n",
             "load_tensors: Vulkan0 model buffer size = nan MiB\n",
             "load_tensors: Vulkan0 model buffer size = inf MiB\n",
             "load_tensors: Vulkan0 model buffer size = 12.50 bogus\n"}) {
        try { transiber::vulkanExecutionInfo("Test GPU", "Vulkan0", assigned, buffer); return 1; }
        catch (const std::runtime_error&) { ++checks; }
    }
    for (const std::string name : {"", "CPU", "Vulkan_Host", "Vulkan0_Host", "Vulkan-1", "Vulkan"}) {
        try { transiber::vulkanExecutionInfo("Test GPU", name, assigned, allocated); return 1; }
        catch (const std::runtime_error&) { ++checks; }
    }
    try { transiber::vulkanExecutionInfo("", "Vulkan0", assigned, allocated); return 1; }
    catch (const std::runtime_error&) { ++checks; }
    std::cout << "Vulkan execution status: " << checks << " checks passed\n";
}
