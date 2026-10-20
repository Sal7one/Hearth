#include "desktop_jni.h"
#include "desktop_metal.h"
#include "lease_registry.h"
#include "whisper.h"
#include <atomic>
#include <cmath>
#include <mutex>
#include <cstring>
#include <vector>

namespace {
thread_local bool initializedMetal = false;
thread_local bool failedMetal = false;
void captureLog(ggml_log_level, const char* text, void*) {
    if (!text) return;
    if (std::strstr(text, "whisper_backend_init_gpu: using Metal backend")) initializedMetal = true;
    if (std::strstr(text, "whisper_backend_init_gpu: failed to initialize Metal backend")) failedMetal = true;
}
struct Model {
    whisper_context* context = nullptr;
    std::atomic<bool> cancelled{false};
    std::mutex inference;
    std::string language;
    int threads;
    std::string executionInfo;
    Model(const std::string& path, std::string lang, int count, bool metal) : language(std::move(lang)), threads(count) {
        if (threads < 1 || threads > 32) throw std::invalid_argument("Whisper threads must be 1–32");
        if (language != "auto" && whisper_lang_id(language.c_str()) < 0) throw std::invalid_argument("Unsupported Whisper language: " + language);
        static std::once_flag logging;
        std::call_once(logging, [] { whisper_log_set(captureLog, nullptr); });
        if (metal) desktopRequireMetal();
        initializedMetal = false; failedMetal = false;
        auto params = whisper_context_default_params();
        params.use_gpu = metal;
        context = whisper_init_from_file_with_params(path.c_str(), params);
        if (!context) throw std::runtime_error("Whisper could not load the selected pinned model: " + path);
        if (language != "auto" && language != "en" && !whisper_is_multilingual(context)) {
            whisper_free(context); context = nullptr;
            throw std::invalid_argument("The selected Whisper model is English-only; choose a multilingual model for " + language);
        }
        if (metal && (!initializedMetal || failedMetal)) {
            whisper_free(context); context = nullptr;
            throw std::runtime_error("Whisper requested Metal, but the loaded model did not initialize a Metal backend");
        }
        executionInfo = std::string(metal ? "Metal GPU" : "CPU") + "; threads=" + std::to_string(threads);
    }
    ~Model() { if (context) whisper_free(context); }
};
stt::concurrency::LeaseRegistry<Model> models;
}
extern "C" JNIEXPORT jint JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_protocolVersion(JNIEnv*, jobject) { return 2; }
extern "C" JNIEXPORT jboolean JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_metalAvailable(JNIEnv*, jobject) { return desktopMetalAvailable(); }
extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_create(JNIEnv* env, jobject, jbyteArray path, jbyteArray language, jint threads, jboolean metal) {
    try { return models.insert(std::make_unique<Model>(desktopUtf8(env, path), desktopUtf8(env, language, 32), threads, metal)); }
    catch (const std::exception& e) { desktopFail(env, e.what()); return 0; }
    catch (...) { desktopFail(env, "Unknown Whisper initialization failure"); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_transcribe(JNIEnv* env, jobject, jlong handle, jfloatArray pcm) {
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
        params.language = model->language == "auto" ? "auto" : model->language.c_str();
        params.abort_callback = [](void* owner) { return static_cast<Model*>(owner)->cancelled.load(); };
        params.abort_callback_user_data = model.operator->();
        const auto status = whisper_full(model->context, params, samples.data(), samples.size());
        if (model->cancelled.load()) throw std::runtime_error("Whisper recognition cancelled");
        if (status != 0) throw std::runtime_error("Whisper inference failed with status " + std::to_string(status));
        std::string text;
        for (int i = 0; i < whisper_full_n_segments(model->context); ++i) {
            const auto* segment = whisper_full_get_segment_text(model->context, i);
            if (!segment) throw std::runtime_error("Whisper returned an invalid text segment");
            text += segment;
        }
        return desktopBytes(env, text);
    } catch (const std::exception& e) { desktopFail(env, e.what()); return nullptr; }
    catch (...) { desktopFail(env, "Unknown Whisper inference failure"); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_desktop_DesktopWhisperNative_executionInfo(JNIEnv* env, jobject, jlong handle) {
    try {
        auto model = models.acquire(handle); if (!model) throw std::invalid_argument("Whisper model is closed");
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
