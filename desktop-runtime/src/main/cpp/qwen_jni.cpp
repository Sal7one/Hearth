#include "desktop_jni.h"
#include "lease_registry.h"
#include "backend_abi.h"
#include "qwen_language.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

// The Android backend is compiled into this dylib unchanged; it owns sherpa-onnx.
extern "C" const HearthSpeechBackend* hearth_speech_backend_v1();

namespace {
// The pinned desktop package is the upstream sherpa-onnx Qwen3-ASR layout.
constexpr const char* kFrontend = "conv_frontend.onnx";
constexpr const char* kEncoder = "encoder.int8.onnx";
constexpr const char* kDecoder = "decoder.int8.onnx";
constexpr const char* kTokenizer = "tokenizer";
constexpr size_t kSliceSamples = 16000;             // One second; the backend queue is bounded at 32 results.
constexpr size_t kMaximumSamples = 30 * 16000;      // Desktop windows are 4–20 s.

struct Qwen {
    const HearthSpeechBackend* backend = nullptr;
    void* session = nullptr;
    std::mutex inference;
    std::atomic<bool> cancelled{false};
    std::string executionInfo;
    std::string lastLanguage;

    Qwen(const std::string& directory, const std::string& language, int threads) {
        if (threads < 1 || threads > 32) throw std::invalid_argument("Qwen3-ASR threads must be 1–32");
        if (language.size() > 32) throw std::invalid_argument("Qwen3-ASR language is too long");
        // Reject an unknown language before touching any model file.
        (void)stt::speech::qwenLanguageName(language);

        namespace fs = std::filesystem;
        const fs::path root(directory);
        const fs::path frontend = root / kFrontend;
        const fs::path encoder = root / kEncoder;
        const fs::path decoder = root / kDecoder;
        const fs::path tokenizer = root / kTokenizer;
        auto requireFile = [](const fs::path& path, const char* role) {
            if (!fs::is_regular_file(path)) throw std::runtime_error(std::string("Missing Qwen3-ASR ") + role + ": " + path.string());
        };
        requireFile(frontend, "conv frontend");
        requireFile(encoder, "encoder");
        requireFile(decoder, "decoder");
        if (!fs::is_directory(tokenizer)) throw std::runtime_error("Missing Qwen3-ASR tokenizer directory: " + tokenizer.string());

        // HearthSpeechConfig borrows these strings only for the create call.
        const std::string frontendPath = frontend.string();
        const std::string encoderPath = encoder.string();
        const std::string decoderPath = decoder.string();
        const std::string tokenizerPath = tokenizer.string();
        backend = hearth_speech_backend_v1();
        if (!backend || backend->abi_version != HEARTH_SPEECH_ABI_VERSION) throw std::runtime_error("Qwen3-ASR speech backend ABI mismatch");
        HearthSpeechConfig config{};
        config.size = sizeof(config);
        config.model = directory.c_str();
        config.conv_frontend = frontendPath.c_str();
        config.encoder = encoderPath.c_str();
        config.decoder = decoderPath.c_str();
        config.tokenizer = tokenizerPath.c_str();
        config.language = language.c_str();
        config.num_threads = threads;
        config.right_context = 0;
        config.max_utterance_ms = 15000;
        config.silence_ms = 600;
        config.silence_threshold_db = -45.0f;
        config.acceleration = "cpu";
        config.gpu_cache_directory = "";
        if (backend->create(&config, &session) != 0) {
            throw std::runtime_error(backend->last_error() ? backend->last_error() : "Qwen3-ASR recognizer creation failed");
        }
        const char* info = backend->execution_info(session);
        executionInfo = info ? info : "CPU · ONNX Runtime";
        lastLanguage = language == "auto" ? "" : language;
    }

    ~Qwen() { if (session) backend->destroy(session); }

    std::string transcribe(const std::vector<float>& pcm) {
        if (pcm.empty()) throw std::invalid_argument("Qwen3-ASR requires non-empty 16 kHz audio");
        if (pcm.size() > kMaximumSamples) throw std::invalid_argument("Qwen3-ASR accepts at most 30 seconds per window");
        for (float value : pcm) {
            if (!std::isfinite(value) || value < -1.0f || value > 1.0f) throw std::invalid_argument("Qwen3-ASR received an invalid PCM sample");
        }
        std::lock_guard<std::mutex> lock(inference);
        auto checkCancelled = [&] {
            if (cancelled.load()) throw std::runtime_error("Qwen3-ASR recognition cancelled");
        };
        // Keep a cancellation that arrived before this window: the caller is stopping the session.
        checkCancelled();
        // Each desktop call is one independent window; discard any tail left by an earlier failure.
        if (backend->reset(session) != 0) throw std::runtime_error(backend->last_error());
        std::string text;
        std::string detected;
        auto drain = [&] {
            while (true) {
                HearthSpeechResult result{};
                int available = 0;
                if (backend->next(session, &result, &available) != 0) throw std::runtime_error(backend->last_error());
                if (!available) return;
                if (result.text && *result.text) {
                    if (!text.empty()) text += ' ';
                    text += result.text;
                }
                if (result.language && *result.language) detected = result.language;
            }
        };
        // The backend keeps at most 32 undrained results; one-second slices keep that unreachable.
        for (size_t offset = 0; offset < pcm.size(); offset += kSliceSamples) {
            checkCancelled();
            const size_t count = std::min(kSliceSamples, pcm.size() - offset);
            if (backend->push(session, pcm.data() + offset, count) != 0) throw std::runtime_error(backend->last_error());
            drain();
        }
        checkCancelled();
        if (backend->finish(session) != 0) throw std::runtime_error(backend->last_error());
        drain();
        if (!detected.empty()) lastLanguage = detected;
        if (backend->reset(session) != 0) throw std::runtime_error(backend->last_error());
        cancelled.store(false);
        return text;
    }

    void cancel() { cancelled.store(true); }

    std::string language() {
        std::lock_guard<std::mutex> lock(inference);
        return lastLanguage;
    }
};

stt::concurrency::LeaseRegistry<Qwen> models;
}

extern "C" JNIEXPORT jint JNICALL Java_com_sal7one_common_1jni_desktop_DesktopQwenNative_protocolVersion(JNIEnv*, jobject) { return 1; }

extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_desktop_DesktopQwenNative_create(
    JNIEnv* env, jobject, jbyteArray directory, jbyteArray language, jint threads) {
    try {
        const auto handle = models.insert(std::make_unique<Qwen>(desktopUtf8(env, directory, 4096), desktopUtf8(env, language, 32), threads));
        if (!handle) throw std::runtime_error("Qwen3-ASR handle registry exhausted");
        return handle;
    } catch (const std::exception& e) { desktopFail(env, e.what()); return 0; }
    catch (...) { desktopFail(env, "Unknown Qwen3-ASR initialization failure"); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_desktop_DesktopQwenNative_transcribe(
    JNIEnv* env, jobject, jlong handle, jfloatArray pcm) {
    try {
        auto model = models.acquire(handle);
        if (!model) throw std::invalid_argument("Qwen3-ASR model is closed or invalid");
        if (!pcm) throw std::invalid_argument("Qwen3-ASR requires a PCM buffer");
        const auto count = env->GetArrayLength(pcm);
        if (count <= 0) throw std::invalid_argument("Qwen3-ASR requires non-empty 16 kHz audio");
        std::vector<float> samples(static_cast<size_t>(count));
        env->GetFloatArrayRegion(pcm, 0, count, samples.data());
        if (env->ExceptionCheck()) return nullptr;
        std::string text = model->transcribe(samples);
        return desktopBytes(env, text);
    } catch (const std::exception& e) { desktopFail(env, e.what()); return nullptr; }
    catch (...) { desktopFail(env, "Unknown Qwen3-ASR inference failure"); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_desktop_DesktopQwenNative_cancel(
    JNIEnv*, jobject, jlong handle) {
    auto model = models.acquire(handle);
    if (model) model->cancel();
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_desktop_DesktopQwenNative_language(
    JNIEnv* env, jobject, jlong handle) {
    try {
        auto model = models.acquire(handle);
        if (!model) throw std::invalid_argument("Qwen3-ASR model is closed or invalid");
        return desktopBytes(env, model->language());
    } catch (const std::exception& e) { desktopFail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_desktop_DesktopQwenNative_executionInfo(
    JNIEnv* env, jobject, jlong handle) {
    try {
        auto model = models.acquire(handle);
        if (!model) throw std::invalid_argument("Qwen3-ASR model is closed or invalid");
        return desktopBytes(env, model->executionInfo);
    } catch (const std::exception& e) { desktopFail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_desktop_DesktopQwenNative_destroy(
    JNIEnv*, jobject, jlong handle) {
    // Prefer stopping between slices, then wait for the in-flight decode (sherpa cannot abort it).
    auto retired = models.retire(handle);
    if (auto* model = retired.signalTarget()) model->cancel();
    auto owner = std::move(retired).lockExclusive();
}
