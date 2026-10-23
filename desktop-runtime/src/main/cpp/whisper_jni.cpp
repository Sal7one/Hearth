#include "desktop_jni.h"
#include "desktop_gpu.h"
#include "lease_registry.h"
#include "whisper.h"
#include <atomic>
#include <algorithm>
#include <cmath>
#include <mutex>
#include <memory>
#include <cstring>
#include <cstdio>
#include <cctype>
#include <vector>

namespace {
struct GpuLoadProof {
    std::string deviceName, bufferName, allocation;
    bool initialized=false, failed=false, weights=false;
};
thread_local GpuLoadProof* loadingGpu=nullptr;
void captureLog(ggml_log_level, const char* text, void*) {
    if (!text || !loadingGpu) return;
    auto& proof=*loadingGpu;
    if (std::strstr(text,("whisper_backend_init_gpu: using "+proof.deviceName+" backend").c_str())) proof.initialized=true;
    if (std::strstr(text,("whisper_backend_init_gpu: failed to initialize "+proof.deviceName+" backend").c_str())) proof.failed=true;
    char buffer[128]{}; double mb=0;
    if (std::sscanf(text,"whisper_model_load: %127s total size = %lf MB",buffer,&mb)==2 &&
        proof.bufferName==buffer && mb>0) {
        proof.weights=true; proof.allocation.assign(text,std::min<size_t>(std::strlen(text),512));
    }
}
struct Model {
    whisper_context* context = nullptr;
    std::atomic<bool> cancelled{false};
    std::mutex inference;
    std::string language;
    int threads;
    std::string executionInfo;
    std::string placement;
    std::string detectedLanguage;
    unsigned languageDetections=0;
    unsigned completedInference=0;
    Model(const std::string& path, std::string lang, int count, int backend, int deviceIndex) : language(std::move(lang)), threads(count) {
        if (threads < 1 || threads > 32) throw std::invalid_argument("Whisper threads must be 1–32");
        if (language != "auto" && whisper_lang_id(language.c_str()) < 0) throw std::invalid_argument("Unsupported Whisper language: " + language);
        static std::once_flag logging;
        std::call_once(logging, [] { whisper_log_set(captureLog, nullptr); });
        const auto mode=desktopGpuMode(backend);
        auto selected=desktopRequireGpu(mode,deviceIndex);
        GpuLoadProof proof;
        if (selected) {
            proof.deviceName=ggml_backend_dev_name(selected);
            proof.bufferName=ggml_backend_buft_name(ggml_backend_dev_buffer_type(selected));
        }
        struct LoggingScope {
            GpuLoadProof* previous;
            explicit LoggingScope(GpuLoadProof* value) : previous(loadingGpu) { loadingGpu=value; }
            ~LoggingScope() { loadingGpu=previous; }
        } scope(selected ? &proof : nullptr);
        auto params = whisper_context_default_params();
        params.use_gpu = selected!=nullptr;
        params.gpu_device = selected ? desktopGpuGlobalIndex(selected) : 0;
        std::unique_ptr<whisper_context,decltype(&whisper_free)> loaded(whisper_init_from_file_with_params(path.c_str(), params),whisper_free);
        if (!loaded) throw std::runtime_error("Whisper could not load the selected pinned model: " + path);
        if (language != "auto" && language != "en" && !whisper_is_multilingual(loaded.get())) {
            throw std::invalid_argument("The selected Whisper model is English-only; choose a multilingual model for " + language);
        }
        if (selected && (!proof.initialized || proof.failed || !proof.weights)) {
            throw std::runtime_error(std::string("Whisper requested ")+desktopGpuLabel(mode)+", but the selected device did not initialize and receive model weights");
        }
        placement = selected ? std::string(desktopGpuLabel(mode))+" GPU; device="+std::to_string(deviceIndex)+"; "+desktopGpuDeviceDescription(selected)+"; "+proof.allocation : "CPU";
        placement += "; threads="+std::to_string(threads);
        executionInfo=placement+"; completedInference=0";
        context=loaded.release();
    }
    ~Model() { if (context) whisper_free(context); }
};
stt::concurrency::LeaseRegistry<Model> models;
}
extern "C" JNIEXPORT int hearth_desktop_compiled_backends() { return desktopCompiledBackends(); }
extern "C" JNIEXPORT jint JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_protocolVersion(JNIEnv*, jobject) { return 4; }
extern "C" JNIEXPORT jboolean JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_metalAvailable(JNIEnv*, jobject) { return desktopGpuAvailable(DesktopGpuMode::METAL,0); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_gpuAvailable(JNIEnv* env, jobject, jint backend,jint deviceIndex) {
    try { return desktopGpuAvailable(desktopGpuMode(backend),deviceIndex); }
    catch (const std::exception& error) { desktopFail(env,error.what()); return false; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_gpuDevices(JNIEnv* env,jobject,jint backend) {
    try { return desktopBytes(env,desktopGpuDeviceListJson(desktopGpuMode(backend))); }
    catch (const std::exception& error) { desktopFail(env,error.what()); return nullptr; }
}
extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_create(JNIEnv* env, jobject, jbyteArray path, jbyteArray language, jint threads, jboolean metal) {
    try { return models.insert(std::make_unique<Model>(desktopUtf8(env, path), desktopUtf8(env, language, 32), threads,metal ? 1 : 0,0)); }
    catch (const std::exception& e) { desktopFail(env, e.what()); return 0; }
    catch (...) { desktopFail(env, "Unknown Whisper initialization failure"); return 0; }
}
extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_createWithBackend(JNIEnv* env,jobject,jbyteArray path,jbyteArray language,jint threads,jint backend,jint deviceIndex) {
    try { return models.insert(std::make_unique<Model>(desktopUtf8(env,path),desktopUtf8(env,language,32),threads,backend,deviceIndex)); }
    catch (const std::exception& e) { desktopFail(env, e.what()); return 0; }
    catch (...) { desktopFail(env, "Unknown Whisper initialization failure"); return 0; }
}
namespace {
jbyteArray transcribe(JNIEnv* env, jlong handle, jfloatArray pcm, bool live) {
    try {
        auto model = models.acquire(handle);
        if (!model) throw std::invalid_argument("Whisper model is closed");
        if (!pcm || env->GetArrayLength(pcm) < 1 || env->GetArrayLength(pcm) > 30 * 16000) throw std::invalid_argument("Whisper accepts 1–30 seconds of 16 kHz mono PCM");
        std::vector<float> samples(env->GetArrayLength(pcm));
        env->GetFloatArrayRegion(pcm, 0, samples.size(), samples.data());
        if (env->ExceptionCheck()) return nullptr;
        for (auto value : samples) if (!std::isfinite(value) || value < -1 || value > 1) throw std::invalid_argument("Invalid Whisper PCM sample");
        std::lock_guard<std::mutex> lock(model->inference);
        if (model->cancelled.load()) throw std::runtime_error("Whisper recognition cancelled");
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = model->threads; params.translate = false; params.no_context = true; params.no_timestamps = true;
        params.print_progress = false; params.print_realtime = false; params.print_timestamps = false; params.print_special = false;
        // Match Android's resident live-session cache. Files retain per-window
        // Auto detection, including files that switch languages between chunks.
        // An empty/silent live result never locks in a guessed language.
        const bool detect = model->language == "auto" && (!live || model->detectedLanguage.empty());
        params.language = model->language == "auto"
            ? (detect ? "auto" : model->detectedLanguage.c_str()) : model->language.c_str();
        if (detect) ++model->languageDetections;
        if (live) {
            // Retries at rising temperatures are useful for files, but can run
            // several decodes for one overlay window. Live uses one greedy pass.
            params.temperature_inc = 0.0f;
            // Default 4s windows match Android's 96-token budget. Preserve a
            // proportional budget for deliberately configured longer windows.
            const int windows = (static_cast<int>(samples.size()) + 4 * 16000 - 1) / (4 * 16000);
            params.max_tokens = std::min(448, 96 * windows);
        }
        params.abort_callback = [](void* owner) { return static_cast<Model*>(owner)->cancelled.load(); };
        params.abort_callback_user_data = model.operator->();
        const auto status = whisper_full(model->context, params, samples.data(), samples.size());
        if (model->cancelled.load()) throw std::runtime_error("Whisper recognition cancelled");
        if (status != 0) throw std::runtime_error("Whisper inference failed with status " + std::to_string(status));
        std::string text;
        for (int i = 0; i < whisper_full_n_segments(model->context); ++i) {
            const int tokens = whisper_full_n_tokens(model->context, i);
            if (live && tokens > params.max_tokens &&
                whisper_full_get_token_id(model->context, i, tokens - 1) != whisper_token_eot(model->context)) {
                throw std::runtime_error("Whisper reached the live caption token limit. Choose a shorter speech window or transcribe the audio as a file.");
            }
            const auto* segment = whisper_full_get_segment_text(model->context, i);
            if (!segment) throw std::runtime_error("Whisper returned an invalid text segment");
            text += segment;
        }
        if (live && detect && std::any_of(text.begin(), text.end(), [](unsigned char value) { return !std::isspace(value); })) {
            const int detected = whisper_full_lang_id(model->context);
            if (detected >= 0) {
                if (const auto* name = whisper_lang_str(detected)) model->detectedLanguage = name;
            }
        }
        model->executionInfo=model->placement+"; completedInference="+std::to_string(++model->completedInference)
            +"; decode="+(live ? "live" : "file")+"; maxTokens="+std::to_string(params.max_tokens)
            +"; languageDetections="+std::to_string(model->languageDetections)
            +"; detectedLanguage="+model->detectedLanguage;
        return desktopBytes(env, text);
    } catch (const std::exception& e) { desktopFail(env, e.what()); return nullptr; }
    catch (...) { desktopFail(env, "Unknown Whisper inference failure"); return nullptr; }
}
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_transcribe(JNIEnv* env, jobject, jlong handle, jfloatArray pcm) {
    return transcribe(env, handle, pcm, false);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_transcribeLive(JNIEnv* env, jobject, jlong handle, jfloatArray pcm) {
    return transcribe(env, handle, pcm, true);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_executionInfo(JNIEnv* env, jobject, jlong handle) {
    try {
        auto model = models.acquire(handle); if (!model) throw std::invalid_argument("Whisper model is closed");
        std::lock_guard<std::mutex> lock(model->inference);
        return desktopBytes(env, model->executionInfo);
    } catch (const std::exception& e) { desktopFail(env, e.what()); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_language(JNIEnv* env, jobject, jlong handle) {
    try {
        auto model = models.acquire(handle); if (!model) throw std::invalid_argument("Whisper model is closed");
        std::lock_guard<std::mutex> lock(model->inference);
        const auto* language = whisper_lang_str(whisper_full_lang_id(model->context));
        return desktopBytes(env, language ? language : "");
    } catch (const std::exception& e) { desktopFail(env, e.what()); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_cancel(JNIEnv*, jobject, jlong handle) {
    auto model = models.acquire(handle); if (model) model->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_destroy(JNIEnv*, jobject, jlong handle) {
    auto retired = models.retire(handle); if (auto* model = retired.signalTarget()) model->cancelled.store(true);
    auto owner = std::move(retired).lockExclusive();
}
