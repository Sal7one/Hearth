#pragma once
#include <dlfcn.h>
#include <stdexcept>
#include <string>

struct VoskModel;
struct VoskRecognizer;
namespace stt {
// Keep the legacy static C++ runtime in Vosk out of common_jni's dependency
// scope. Only these C functions cross the boundary. Retain the DSO for process
// life, including after failed symbol resolution (native TLS may refer to it).
//
// Android resolves the soname "libvosk.so" from the APK. Desktop adapters that
// ship a sibling dylib must pin an explicit absolute path before the first
// voskApi() call; the default stays unchanged so Android behavior is identical.
inline std::string& voskLibraryPath() {
    static std::string path = "libvosk.so";
    return path;
}
inline void setVoskLibraryPath(const std::string& path) {
    if (path.empty()) throw std::invalid_argument("Vosk runtime path is empty");
    voskLibraryPath() = path;
}
struct VoskApi {
    VoskModel* (*vosk_model_new)(const char* model_path) = nullptr;
    void (*vosk_model_free)(VoskModel* model) = nullptr;
    VoskRecognizer* (*vosk_recognizer_new)(VoskModel* model, float sample_rate) = nullptr;
    void (*vosk_recognizer_free)(VoskRecognizer* recognizer) = nullptr;
    int (*vosk_recognizer_accept_waveform)(VoskRecognizer* recognizer, const char* data, int length) = nullptr;
    int (*vosk_recognizer_accept_waveform_s)(VoskRecognizer* recognizer, const short* data, int length) = nullptr;
    int (*vosk_recognizer_accept_waveform_f)(VoskRecognizer* recognizer, const float* data, int length) = nullptr;
    const char* (*vosk_recognizer_result)(VoskRecognizer* recognizer) = nullptr;
    const char* (*vosk_recognizer_partial_result)(VoskRecognizer* recognizer) = nullptr;
    const char* (*vosk_recognizer_final_result)(VoskRecognizer* recognizer) = nullptr;
    void (*vosk_recognizer_reset)(VoskRecognizer* recognizer) = nullptr;
    void (*vosk_set_log_level)(int log_level) = nullptr;
    explicit VoskApi(const char* path = nullptr) {
        const std::string resolved = path ? std::string(path) : voskLibraryPath();
        void* handle = dlopen(resolved.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!handle) {
            const char* error = dlerror();
            throw std::runtime_error("dlopen " + resolved + " failed: " + (error ? error : "unknown dlopen error"));
        }
        vosk_model_new = symbol<decltype(vosk_model_new)>(handle, "vosk_model_new");
        vosk_model_free = symbol<decltype(vosk_model_free)>(handle, "vosk_model_free");
        vosk_recognizer_new = symbol<decltype(vosk_recognizer_new)>(handle, "vosk_recognizer_new");
        vosk_recognizer_free = symbol<decltype(vosk_recognizer_free)>(handle, "vosk_recognizer_free");
        vosk_recognizer_accept_waveform = symbol<decltype(vosk_recognizer_accept_waveform)>(handle, "vosk_recognizer_accept_waveform");
        vosk_recognizer_accept_waveform_s = symbol<decltype(vosk_recognizer_accept_waveform_s)>(handle, "vosk_recognizer_accept_waveform_s");
        vosk_recognizer_accept_waveform_f = symbol<decltype(vosk_recognizer_accept_waveform_f)>(handle, "vosk_recognizer_accept_waveform_f");
        vosk_recognizer_result = symbol<decltype(vosk_recognizer_result)>(handle, "vosk_recognizer_result");
        vosk_recognizer_partial_result = symbol<decltype(vosk_recognizer_partial_result)>(handle, "vosk_recognizer_partial_result");
        vosk_recognizer_final_result = symbol<decltype(vosk_recognizer_final_result)>(handle, "vosk_recognizer_final_result");
        vosk_recognizer_reset = symbol<decltype(vosk_recognizer_reset)>(handle, "vosk_recognizer_reset");
        vosk_set_log_level = symbol<decltype(vosk_set_log_level)>(handle, "vosk_set_log_level");
    }
private:
    template<class T> static T symbol(void* handle, const char* name) {
        dlerror();
        void* address = dlsym(handle, name);
        const char* error = dlerror();
        if (error) throw std::runtime_error(error);
        if (!address) throw std::runtime_error(std::string("Null Vosk symbol: ") + name);
        return reinterpret_cast<T>(address);
    }
};
inline const VoskApi& voskApi() { static const VoskApi api; return api; }
} // namespace stt
