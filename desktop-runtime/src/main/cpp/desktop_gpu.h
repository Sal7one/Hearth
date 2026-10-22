#pragma once
#include "ggml-backend.h"
#include <array>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>
#if HEARTH_DESKTOP_CUDA
#include <cuda_runtime_api.h>
#endif

enum class DesktopGpuMode : int { CPU=0, METAL=1, CUDA=2 };
#if HEARTH_DESKTOP_METAL
extern "C" bool hearth_desktop_metal_device_present();
extern "C" bool hearth_desktop_metal_device_description(char*, size_t);
#endif

inline int desktopCompiledBackends() {
    return 1
#if HEARTH_DESKTOP_METAL
        | 2
#endif
#if HEARTH_DESKTOP_CUDA
        | 4
#endif
        ;
}
inline DesktopGpuMode desktopGpuMode(int value) {
    if (value<0 || value>2) throw std::invalid_argument("Unknown desktop GPU backend; expected CPU (0), Metal (1), or CUDA (2)");
    return static_cast<DesktopGpuMode>(value);
}
inline const char* desktopGpuLabel(DesktopGpuMode mode) {
    switch (mode) { case DesktopGpuMode::CPU:return "CPU"; case DesktopGpuMode::METAL:return "Metal"; case DesktopGpuMode::CUDA:return "CUDA"; }
    throw std::invalid_argument("Unknown desktop GPU backend");
}
inline bool desktopGpuCompiled(DesktopGpuMode mode) {
    return (desktopCompiledBackends() & (1<<static_cast<int>(mode)))!=0;
}
inline bool desktopGpuRegistryMatches(DesktopGpuMode mode, ggml_backend_dev_t device) {
    const auto type=ggml_backend_dev_type(device);
    if (type!=GGML_BACKEND_DEVICE_TYPE_GPU && type!=GGML_BACKEND_DEVICE_TYPE_IGPU) return false;
    const auto registry=ggml_backend_dev_backend_reg(device);
    const auto* name=registry ? ggml_backend_reg_name(registry) : "";
    return mode==DesktopGpuMode::CUDA ? std::strcmp(name,"CUDA")==0 :
        mode==DesktopGpuMode::METAL && (std::strcmp(name,"Metal")==0 || std::strcmp(name,"MTL")==0);
}
inline ggml_backend_dev_t desktopGpuDevice(DesktopGpuMode mode,int index) {
    if (!desktopGpuCompiled(mode) || mode==DesktopGpuMode::CPU || index<0) return nullptr;
    // Both pinned Metal ports use the default physical Metal device. llama may expose
    // multiple virtual command-buffer devices; they are not additional physical GPUs.
    if (mode==DesktopGpuMode::METAL && index!=0) return nullptr;
    ggml_backend_load_all();
    int matching=0;
    for (size_t i=0;i<ggml_backend_dev_count();++i) {
        auto device=ggml_backend_dev_get(i);
        if (desktopGpuRegistryMatches(mode,device) && matching++==index) return device;
    }
    return nullptr;
}
inline int desktopGpuGlobalIndex(ggml_backend_dev_t selected) {
    int gpu=0;
    for (size_t i=0;i<ggml_backend_dev_count();++i) {
        auto device=ggml_backend_dev_get(i); auto type=ggml_backend_dev_type(device);
        if (type==GGML_BACKEND_DEVICE_TYPE_GPU || type==GGML_BACKEND_DEVICE_TYPE_IGPU) {
            if (device==selected) return gpu;
            ++gpu;
        }
    }
    throw std::runtime_error("The selected GPU disappeared from the ggml device registry");
}
inline int desktopCudaDeviceCount() {
#if HEARTH_DESKTOP_CUDA
    int count=0; return cudaGetDeviceCount(&count)==cudaSuccess ? count : 0;
#else
    return 0;
#endif
}
inline thread_local std::string desktopCudaFailure;
inline bool desktopCudaDeviceUsable(int index) {
#if HEARTH_DESKTOP_CUDA
    desktopCudaFailure.clear();
    int count=0;
    const auto enumerated=cudaGetDeviceCount(&count);
    if (enumerated!=cudaSuccess) { desktopCudaFailure=std::string("cudaGetDeviceCount: ")+cudaGetErrorString(enumerated); return false; }
    if (index<0 || index>=count) { desktopCudaFailure="device index is outside the "+std::to_string(count)+" visible NVIDIA devices"; return false; }
    int previous=-1;
    const bool restore=cudaGetDevice(&previous)==cudaSuccess;
    const auto selected=cudaSetDevice(index);
    // Force a CUDA context to exist. Device enumeration alone can report a device
    // whose driver/context cannot be used by this process.
    const auto initialized=selected==cudaSuccess ? cudaFree(nullptr) : selected;
    if (initialized!=cudaSuccess) desktopCudaFailure=std::string(selected==cudaSuccess ? "CUDA context initialization: " : "cudaSetDevice: ")+cudaGetErrorString(initialized);
    if (restore && previous>=0) (void)cudaSetDevice(previous);
    return initialized==cudaSuccess;
#else
    (void)index; return false;
#endif
}
inline ggml_backend_dev_t desktopRequireGpu(DesktopGpuMode mode,int index) {
    if (index<0) throw std::invalid_argument("GPU device index must be nonnegative");
    if (mode==DesktopGpuMode::CPU) {
        if (index!=0) throw std::invalid_argument("CPU does not have a GPU device index");
        return nullptr;
    }
    const std::string label=desktopGpuLabel(mode);
    if (!desktopGpuCompiled(mode)) throw std::runtime_error(label+" acceleration requested, but this desktop runtime was built without "+label);
    if (mode==DesktopGpuMode::CUDA && !desktopCudaDeviceUsable(index))
        throw std::runtime_error("CUDA acceleration requested, but NVIDIA driver/device "+std::to_string(index)+" cannot initialize: "+desktopCudaFailure);
    auto device=desktopGpuDevice(mode,index);
    if (!device) throw std::runtime_error(label+" acceleration requested, but GPU device "+std::to_string(index)+" is unavailable");
    auto backend=ggml_backend_dev_init(device,nullptr);
    if (!backend) throw std::runtime_error(label+" acceleration requested, but GPU device "+std::to_string(index)+" could not initialize");
    ggml_backend_free(backend);
    return device;
}
inline bool desktopGpuAvailable(DesktopGpuMode mode,int index=0) {
    if (!desktopGpuCompiled(mode)) return false;
    if (mode==DesktopGpuMode::CPU) return index==0;
    if (mode==DesktopGpuMode::METAL) {
        // Never JIT Metal kernels while rendering capability/settings UI.
#if HEARTH_DESKTOP_METAL
        return index==0 && hearth_desktop_metal_device_present();
#else
        return false;
#endif
    }
    try {
        if (index==-1) {
            for (int i=0;i<desktopCudaDeviceCount();++i) if (desktopGpuAvailable(mode,i)) return true;
            return false;
        }
        return desktopRequireGpu(mode,index)!=nullptr;
    } catch (...) { return false; }
}
inline std::string desktopGpuDeviceDescription(ggml_backend_dev_t device) {
    if (!device) return "CPU";
    const auto* name=ggml_backend_dev_name(device); const auto* description=ggml_backend_dev_description(device);
    return std::string(name ? name : "GPU")+" ("+(description ? description : "unknown device")+")";
}
inline std::string desktopGpuJsonString(const std::string& input) {
    std::string result="\"";
    for (unsigned char ch : input) {
        if (ch=='"' || ch=='\\') { result+='\\'; result+=static_cast<char>(ch); }
        else if (ch<32) { char escaped[7]; std::snprintf(escaped,sizeof(escaped),"\\u%04x",ch); result+=escaped; }
        else result+=static_cast<char>(ch);
    }
    return result+'"';
}
inline std::string desktopGpuDeviceListJson(DesktopGpuMode mode) {
    if (mode==DesktopGpuMode::CPU || !desktopGpuCompiled(mode)) return "[]";
    if (mode==DesktopGpuMode::METAL) {
#if HEARTH_DESKTOP_METAL
        char description[1024]{};
        if (!hearth_desktop_metal_device_description(description,sizeof(description))) return "[]";
        return "[{\"index\":0,\"name\":\"Metal\",\"description\":"+desktopGpuJsonString(description)+",\"memoryBytes\":null}]";
#else
        return "[]";
#endif
    }
    std::string json="[";
    for (int i=0;i<desktopCudaDeviceCount();++i) {
        if (!desktopGpuAvailable(mode,i)) continue;
        auto device=desktopGpuDevice(mode,i); if (!device) continue;
        size_t free=0,total=0; ggml_backend_dev_memory(device,&free,&total);
        if (json.size()>1) json+=',';
        json+="{\"index\":"+std::to_string(i)+",\"name\":"+desktopGpuJsonString(ggml_backend_dev_name(device))+
            ",\"description\":"+desktopGpuJsonString(ggml_backend_dev_description(device))+",\"memoryBytes\":"+std::to_string(total)+"}";
    }
    return json+"]";
}

/** Own this base before TextModel so mp.devices never points into a destroyed temporary. */
class DesktopGpuPlacement {
    DesktopGpuMode mode_;
    int index_;
    std::array<ggml_backend_dev_t,2> devices_{{nullptr,nullptr}};
public:
    DesktopGpuPlacement(int mode,int index) : mode_(desktopGpuMode(mode)), index_(index) { devices_[0]=desktopRequireGpu(mode_,index_); }
    DesktopGpuMode mode() const { return mode_; }
    int deviceIndex() const { return index_; }
    int layers() const { return mode_==DesktopGpuMode::CPU ? 0 : 99; }
    ggml_backend_dev_t* devices() { return mode_==DesktopGpuMode::CPU ? nullptr : devices_.data(); }
    ggml_backend_dev_t device() const { return devices_[0]; }
    std::string description() const { return desktopGpuDeviceDescription(devices_[0]); }
};
