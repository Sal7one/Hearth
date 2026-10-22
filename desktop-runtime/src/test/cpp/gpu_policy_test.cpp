#include "desktop_gpu_policy.h"
#include <cassert>
int main() {
    const std::string assignment = "load_tensors: offloaded 25/25 layers to GPU\n";
    const std::string metal = "load_tensors: MTL0_Mapped model buffer size = 993.80 MiB\n";
    assert(desktopGpuExecutionInfo("Apple M4 Pro", "MTL0", assignment, metal).find("993.80") != std::string::npos);
    assert(!desktopGpuExecutionInfo("NVIDIA RTX", "CUDA0", assignment,
        "load_tensors: CUDA0 model buffer size = 993.80 MiB\n").empty());
    for (const auto& buffers : {"", "load_tensors: CPU model buffer size = 993.80 MiB\n",
         "load_tensors: CUDA0_Host model buffer size = 993.80 MiB\n",
         "load_tensors: CUDA1 model buffer size = 993.80 MiB\n",
         "load_tensors: CUDA0 model buffer size = 0 MiB\n",
         "load_tensors: CUDA0 model buffer size = nan MiB\n",
         "load_tensors: CUDA0 model buffer size = 1 MiB forged"}) {
        bool failed=false;
        try { desktopGpuExecutionInfo("NVIDIA RTX", "CUDA0", assignment, buffers); }
        catch (const std::runtime_error&) { failed=true; }
        assert(failed);
    }
    for (const auto& summary : {"", "offloaded 0/25 layers to GPU", "offloaded 30/25 layers to GPU"}) {
        bool failed=false;
        try { desktopGpuExecutionInfo("Apple M4 Pro", "MTL0", summary, metal); }
        catch (const std::runtime_error&) { failed=true; }
        assert(failed);
    }
}
