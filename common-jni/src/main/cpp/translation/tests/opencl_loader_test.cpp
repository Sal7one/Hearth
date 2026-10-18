#include "../../common/opencl_loader.h"
#include <cstring>
#include <iostream>

#ifdef HEARTH_FAKE_OPENCL_DRIVER
namespace {
bool mode(const char* expected) { const auto* value = std::getenv("HEARTH_OPENCL_TEST_MODE"); return value && std::string(value) == expected; }
}
extern "C" cl_int clGetPlatformIDs(cl_uint entries, cl_platform_id* platforms, cl_uint* count) {
    if (mode("enumeration-error")) return CL_OUT_OF_RESOURCES;
    if (count) *count = mode("no-platform") ? 0 : mode("too-many-platforms") ? 17 : 1;
    if (entries && platforms) platforms[0] = reinterpret_cast<cl_platform_id>(1);
    return CL_SUCCESS;
}
extern "C" cl_int clGetDeviceIDs(cl_platform_id, cl_device_type type, cl_uint entries, cl_device_id* devices, cl_uint* count) {
    if (type != CL_DEVICE_TYPE_GPU || mode("no-gpu")) return CL_DEVICE_NOT_FOUND;
    if (count) *count = 1;
    if (entries && devices) devices[0] = reinterpret_cast<cl_device_id>(2);
    return CL_SUCCESS;
}
extern "C" cl_int clGetDeviceInfo(cl_device_id, cl_device_info field, size_t capacity, void* output, size_t* size) {
    const auto copy = [&](const void* value, size_t bytes) {
        if (size) *size = bytes;
        if (output) { if (capacity < bytes) return CL_INVALID_VALUE; std::memcpy(output, value, bytes); }
        return CL_SUCCESS;
    };
    if (field == CL_DEVICE_AVAILABLE || field == CL_DEVICE_COMPILER_AVAILABLE) {
        const cl_bool available = mode("no-compiler") ? CL_FALSE : CL_TRUE;
        return copy(&available, sizeof(available));
    }
    const char* text = "";
    if (field == CL_DEVICE_NAME) text = mode("non-adreno") ? "CPU OpenCL" : "Qualcomm Adreno 830";
    if (field == CL_DEVICE_VERSION) text = "OpenCL 3.0 fake";
    if (field == CL_DRIVER_VERSION) text = "fake-driver-47";
    if (field == CL_DEVICE_EXTENSIONS) text = mode("no-fp16") ? "cl_khr_subgroups" : "cl_khr_fp16 cl_khr_subgroups";
    if (mode("oversized-info")) { if (size) *size = 65537; return CL_SUCCESS; }
    return copy(text, std::strlen(text) + 1);
}
extern "C" cl_int clReleaseEvent(cl_event) { return CL_SUCCESS; }
extern "C" cl_int clReleaseKernel(cl_kernel) { return CL_SUCCESS; }
extern "C" cl_int clReleaseMemObject(cl_mem) { return CL_SUCCESS; }
extern "C" cl_int clReleaseProgram(cl_program) { return CL_SUCCESS; }
extern "C" cl_int clFinish(cl_command_queue) { return CL_SUCCESS; }
extern "C" cl_int clFlush(cl_command_queue) { return CL_SUCCESS; }
#else
int main(int argc, char**) {
    int checks = 0;
    if (argc > 1) {
        try { hearth::opencl::driver(); return 1; }
        catch (const std::runtime_error& error) {
            if (std::string(error.what()).find("OpenCL driver is unavailable to this app") == std::string::npos) return 1;
            std::cout << "Missing OpenCL driver surfaced without abort\n";
            return 0;
        }
    }
    const auto actual = hearth::opencl::requireAdreno();
    if (actual.find("Adreno 830") == std::string::npos || actual.find("fake-driver-47") == std::string::npos ||
        std::string(std::getenv("GGML_OPENCL_PLATFORM")) != "0" ||
        std::string(std::getenv("GGML_OPENCL_DEVICE")) != "Qualcomm Adreno 830") return 1;
    ++checks;
    for (const auto* mode : {"enumeration-error", "no-platform", "too-many-platforms", "no-gpu", "non-adreno", "no-compiler", "no-fp16", "oversized-info"}) {
        setenv("HEARTH_OPENCL_TEST_MODE", mode, 1);
        try { hearth::opencl::requireAdreno(); return 1; }
        catch (const std::runtime_error&) { ++checks; }
    }
    unsetenv("HEARTH_OPENCL_TEST_MODE");
    try { hearth::opencl::resolve<decltype(&clGetPlatformInfo)>("clGetPlatformInfo"); return 1; }
    catch (const std::runtime_error& error) {
        if (std::string(error.what()).find("clGetPlatformInfo") == std::string::npos) return 1;
        ++checks;
    }
    std::cout << "OpenCL dynamic loader: " << checks << " checks passed\n";
}
#endif
