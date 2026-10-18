#pragma once
#include <stdexcept>
#include <string>
namespace parakeet {
struct EngineOptions { std::string model_gguf_path; int n_threads = 0; int n_gpu_layers = 0; std::string opencl_cache_dir; };
struct EngineResult { std::string text; };
enum class BackendDevice { CPU, GPU };
class Engine {
public:
    inline static int live = 0, calls = 0;
    inline static bool failLoad = false, failDecode = false;
    inline static std::string type = "tdt", text = "Bonjour.";
    inline static std::string decoder = "CPU", encoder = "CPU";
    inline static BackendDevice device = BackendDevice::CPU;
    inline static EngineOptions lastOptions;
    explicit Engine(const EngineOptions& options) {
        if (failLoad) throw std::runtime_error("fixture GGUF load error");
        lastOptions = options; ++live;
    }
    ~Engine() { --live; }
    std::string model_type() const { return type; }
    BackendDevice backend_device() const { return device; }
    std::string backend_name() const { return decoder; }
    std::string encoder_backend() const { return encoder; }
    EngineResult transcribe_samples(const float* samples, int count, int sampleRate) {
        ++calls;
        if (failDecode) throw std::runtime_error("fixture encoder error");
        if (!samples || count <= 0 || sampleRate != 16000) throw std::runtime_error("invalid decode geometry");
        return {text};
    }
};
}
