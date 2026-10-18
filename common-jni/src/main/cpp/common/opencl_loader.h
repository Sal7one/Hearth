#pragma once
#ifndef CL_TARGET_OPENCL_VERSION
#define CL_TARGET_OPENCL_VERSION 300
#endif
#define CL_USE_DEPRECATED_OPENCL_1_2_APIS
#include <CL/cl.h>
#include <dlfcn.h>
#include <cstdlib>
#include <string>
#include <stdexcept>
#include <vector>

namespace hearth::opencl {
// The optional manifest declaration exposes a vendor's PUBLIC libOpenCL.so.
// Never search vendor paths or bypass the Android linker namespace. This
// handle stays alive as long as the private backend's cached driver objects.
inline void* driver() {
    static void* handle = [] {
#ifndef HEARTH_OPENCL_DRIVER_NAME
#define HEARTH_OPENCL_DRIVER_NAME "libOpenCL.so"
#endif
        void* value = dlopen(HEARTH_OPENCL_DRIVER_NAME, RTLD_NOW | RTLD_LOCAL);
        if (!value) {
            const char* error = dlerror();
            throw std::runtime_error(std::string("OpenCL driver is unavailable to this app: ") +
                (error ? error : "dlopen failed") + ". Select Vulkan or CPU to continue.");
        }
        return value;
    }();
    return handle;
}
template<class Function> inline Function resolve(const char* name) {
    dlerror();
    void* value = dlsym(driver(), name);
    const char* error = dlerror();
    if (!value || error)
        throw std::runtime_error(std::string("OpenCL driver does not provide ") + name +
            (error ? std::string(": ") + error : "") + ". Select Vulkan or CPU to continue.");
    return reinterpret_cast<Function>(value);
}
inline void check(cl_int code, const char* operation) {
    if (code != CL_SUCCESS)
        throw std::runtime_error(std::string("OpenCL ") + operation + " failed (" + std::to_string(code) +
            "). Select Vulkan or CPU to continue.");
}
inline std::string deviceString(cl_device_id device, cl_device_info field) {
    size_t bytes = 0;
    check(clGetDeviceInfo(device, field, 0, nullptr, &bytes), "device information");
    if (bytes == 0 || bytes > 65536) throw std::runtime_error("Invalid OpenCL device information size");
    std::vector<char> data(bytes + 1, '\0');
    check(clGetDeviceInfo(device, field, bytes, data.data(), nullptr), "device information");
    return std::string(data.data());
}
// Query actual devices before llama touches its backend registry. Advertising a
// Qualcomm handset name alone does not establish an exposed, usable GPU driver.
inline std::string requireAdreno() {
    // Establish cleanup entry points before any backend context can own GPU
    // resources. A truncated OEM library must fail before a destructor needs
    // to resolve a missing symbol while another exception is already active.
    for (const char* name : {"clReleaseEvent", "clReleaseKernel", "clReleaseMemObject", "clReleaseProgram", "clFinish", "clFlush"})
        resolve<void (*)()>(name);
    cl_uint count = 0;
    check(clGetPlatformIDs(0, nullptr, &count), "platform enumeration");
    if (!count || count > 16) throw std::runtime_error("No compatible OpenCL platform is available. Select Vulkan or CPU.");
    std::vector<cl_platform_id> platforms(count);
    check(clGetPlatformIDs(count, platforms.data(), nullptr), "platform enumeration");
    for (cl_uint platform = 0; platform < count; ++platform) {
        cl_uint devices = 0;
        const auto result = clGetDeviceIDs(platforms[platform], CL_DEVICE_TYPE_GPU, 0, nullptr, &devices);
        if (result == CL_DEVICE_NOT_FOUND) continue;
        check(result, "GPU enumeration");
        if (!devices || devices > 16) continue;
        std::vector<cl_device_id> ids(devices);
        check(clGetDeviceIDs(platforms[platform], CL_DEVICE_TYPE_GPU, devices, ids.data(), nullptr), "GPU enumeration");
        for (auto id : ids) {
            const auto name = deviceString(id, CL_DEVICE_NAME);
            if (name.find("Adreno") == std::string::npos) continue;
            cl_bool available = CL_FALSE, compiler = CL_FALSE;
            check(clGetDeviceInfo(id, CL_DEVICE_AVAILABLE, sizeof(available), &available, nullptr), "GPU availability");
            check(clGetDeviceInfo(id, CL_DEVICE_COMPILER_AVAILABLE, sizeof(compiler), &compiler, nullptr), "GPU compiler availability");
            if (!available || !compiler) throw std::runtime_error("Adreno OpenCL GPU or kernel compiler is unavailable. Select Vulkan or CPU.");
            const auto extensions = deviceString(id, CL_DEVICE_EXTENSIONS);
            if (extensions.find("cl_khr_fp16") == std::string::npos)
                throw std::runtime_error("Adreno OpenCL does not support FP16. Select Vulkan or CPU.");
            // Both backends consume this selector. Restrict enumeration to the
            // actual Adreno device; never allow a CPU OpenCL platform to win.
            const auto selected = std::to_string(platform);
            if (setenv("GGML_OPENCL_PLATFORM", selected.c_str(), 1) != 0 ||
                setenv("GGML_OPENCL_DEVICE", name.c_str(), 1) != 0)
                throw std::runtime_error("Could not select the Adreno OpenCL GPU");
            return name + " · " + deviceString(id, CL_DEVICE_VERSION) + " · driver " + deviceString(id, CL_DRIVER_VERSION);
        }
    }
    throw std::runtime_error("No accessible Adreno OpenCL GPU was found. Select Vulkan or CPU to continue.");
}
}
