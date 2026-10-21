#include "desktop_jni.h"
#include "lease_registry.h"
#include "backend_abi.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using HearthEntry = const HearthSpeechBackend* (*)();

constexpr size_t kSliceSamples = 16000;
constexpr size_t kMaximumSamples = 30 * 16000;

struct Backend {
    void* library = nullptr;
    const HearthSpeechBackend* api = nullptr;
    void* session = nullptr;
    std::mutex inference;
    std::atomic<bool> cancelled{false};
    std::string executionInfo;
    std::string lastLanguage;

    Backend(const std::string& libraryPath, const std::string& expectedId, const std::string& entrySymbol,
            const std::string& model, const std::string& encoder, const std::string& decoder, const std::string& tokenizer,
            const std::string& convFrontend, const std::string& language, int threads, int rightContext, int maximumMs, int silenceMs, float thresholdDb) {
        if (threads < 1 || threads > 32) throw std::invalid_argument("Speech threads must be 1–32");
        if (language.size() > 32) throw std::invalid_argument("Speech language is too long");
        if (entrySymbol.empty() || entrySymbol.size() > 128) throw std::invalid_argument("Speech backend entry symbol is invalid");
        library = dlopen(libraryPath.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!library) {
            const char* error = dlerror();
            throw std::runtime_error("Could not load speech backend " + libraryPath + ": " + (error ? error : "unknown dlopen error"));
        }
        const auto entry = reinterpret_cast<HearthEntry>(dlsym(library, entrySymbol.c_str()));
        if (!entry) throw std::runtime_error("Speech backend does not export " + entrySymbol + ": " + libraryPath);
        api = entry();
        if (!api || api->abi_version != HEARTH_SPEECH_ABI_VERSION || api->size != sizeof(HearthSpeechBackend)) {
            throw std::runtime_error("Speech backend ABI mismatch: " + libraryPath);
        }
        if (!api->id || expectedId != api->id) {
            throw std::runtime_error("Speech backend id mismatch: expected " + expectedId + ", found " + (api->id ? api->id : "null"));
        }
        if (!api->create || !api->destroy || !api->push || !api->next || !api->finish || !api->reset || !api->last_error || !api->execution_info) {
            throw std::runtime_error("Speech backend is missing required ABI operations");
        }
        HearthSpeechConfig config{};
        config.size = sizeof(config);
        config.model = model.c_str();
        config.conv_frontend = convFrontend.c_str();
        config.encoder = encoder.c_str();
        config.decoder = decoder.c_str();
        config.tokenizer = tokenizer.c_str();
        config.language = language.c_str();
        config.num_threads = threads;
        config.right_context = rightContext;
        config.max_utterance_ms = maximumMs;
        config.silence_ms = silenceMs;
        config.silence_threshold_db = thresholdDb;
        config.acceleration = "cpu";
        config.gpu_cache_directory = "";
        if (api->create(&config, &session) != 0 || !session) {
            throw std::runtime_error(api->last_error() ? api->last_error() : "Speech backend creation failed");
        }
        const char* info = api->execution_info(session);
        executionInfo = info ? info : "CPU";
        lastLanguage = language == "auto" ? "" : language;
    }

    ~Backend() {
        if (api && session) api->destroy(session);
        if (library) dlclose(library);
    }

    std::string transcribe(const std::vector<float>& samples) {
        if (samples.empty()) throw std::invalid_argument("Speech requires non-empty 16 kHz audio");
        if (samples.size() > kMaximumSamples) throw std::invalid_argument("Speech accepts at most 30 seconds per window");
        for (float value : samples) {
            if (!std::isfinite(value) || value < -1.0f || value > 1.0f) throw std::invalid_argument("Speech received an invalid PCM sample");
        }
        std::lock_guard<std::mutex> lock(inference);
        auto checkCancelled = [&] {
            if (cancelled.load()) throw std::runtime_error("Speech recognition cancelled");
        };
        checkCancelled();
        if (api->reset(session) != 0) throw std::runtime_error(api->last_error());
        std::string text;
        std::string detected;
        auto drain = [&] {
            while (true) {
                HearthSpeechResult result{};
                result.size = sizeof(result);
                int available = 0;
                if (api->next(session, &result, &available) != 0) throw std::runtime_error(api->last_error());
                if (!available) return;
                const bool final = result.is_final != 0;
                if (final && result.text && *result.text) {
                    if (!text.empty()) text += ' ';
                    text += result.text;
                }
                if (result.language && *result.language) detected = result.language;
            }
        };
        for (size_t offset = 0; offset < samples.size(); offset += kSliceSamples) {
            checkCancelled();
            const size_t count = std::min(kSliceSamples, samples.size() - offset);
            if (api->push(session, samples.data() + offset, count) != 0) throw std::runtime_error(api->last_error());
            drain();
        }
        checkCancelled();
        if (api->finish(session) != 0) throw std::runtime_error(api->last_error());
        drain();
        if (!detected.empty()) lastLanguage = detected;
        if (api->reset(session) != 0) throw std::runtime_error(api->last_error());
        cancelled.store(false);
        return text;
    }

    void cancel() { cancelled.store(true); }

    std::string language() {
        std::lock_guard<std::mutex> lock(inference);
        return lastLanguage;
    }
};

stt::concurrency::LeaseRegistry<Backend> backends;
}

extern "C" JNIEXPORT jint JNICALL Java_com_sal7one_common_1jni_desktop_DesktopBackendSpeechNative_protocolVersion(JNIEnv*, jobject) { return 1; }

extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_desktop_DesktopBackendSpeechNative_create(
    JNIEnv* env, jobject, jbyteArray library, jbyteArray backendId, jbyteArray entrySymbol, jbyteArray model,
    jbyteArray encoder, jbyteArray decoder, jbyteArray tokenizer, jbyteArray convFrontend, jbyteArray language,
    jint threads, jint rightContext, jint maximumMs, jint silenceMs, jfloat thresholdDb) {
    try {
        const auto handle = backends.insert(std::make_unique<Backend>(
            desktopUtf8(env, library, 4096), desktopUtf8(env, backendId, 64), desktopUtf8(env, entrySymbol, 128),
            desktopUtf8(env, model, 4096), desktopUtf8(env, encoder, 4096), desktopUtf8(env, decoder, 4096),
            desktopUtf8(env, tokenizer, 4096), desktopUtf8(env, convFrontend, 4096), desktopUtf8(env, language, 32),
            threads, rightContext, maximumMs, silenceMs, thresholdDb));
        if (!handle) throw std::runtime_error("Speech backend handle registry exhausted");
        return handle;
    } catch (const std::exception& e) { desktopFail(env, e.what()); return 0; }
    catch (...) { desktopFail(env, "Unknown speech backend initialization failure"); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_desktop_DesktopBackendSpeechNative_transcribe(
    JNIEnv* env, jobject, jlong handle, jfloatArray pcm) {
    try {
        auto model = backends.acquire(handle);
        if (!model) throw std::invalid_argument("Speech backend is closed or invalid");
        if (!pcm) throw std::invalid_argument("Speech requires a PCM buffer");
        const auto count = env->GetArrayLength(pcm);
        if (count <= 0) throw std::invalid_argument("Speech requires non-empty 16 kHz audio");
        std::vector<float> samples(static_cast<size_t>(count));
        env->GetFloatArrayRegion(pcm, 0, count, samples.data());
        if (env->ExceptionCheck()) return nullptr;
        return desktopBytes(env, model->transcribe(samples));
    } catch (const std::exception& e) { desktopFail(env, e.what()); return nullptr; }
    catch (...) { desktopFail(env, "Unknown speech backend inference failure"); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_desktop_DesktopBackendSpeechNative_cancel(
    JNIEnv*, jobject, jlong handle) {
    auto model = backends.acquire(handle);
    if (model) model->cancel();
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_desktop_DesktopBackendSpeechNative_language(
    JNIEnv* env, jobject, jlong handle) {
    try {
        auto model = backends.acquire(handle);
        if (!model) throw std::invalid_argument("Speech backend is closed or invalid");
        return desktopBytes(env, model->language());
    } catch (const std::exception& e) { desktopFail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sal7one_common_1jni_desktop_DesktopBackendSpeechNative_executionInfo(
    JNIEnv* env, jobject, jlong handle) {
    try {
        auto model = backends.acquire(handle);
        if (!model) throw std::invalid_argument("Speech backend is closed or invalid");
        return desktopBytes(env, model->executionInfo);
    } catch (const std::exception& e) { desktopFail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_desktop_DesktopBackendSpeechNative_destroy(
    JNIEnv*, jobject, jlong handle) {
    auto retired = backends.retire(handle);
    if (auto* model = retired.signalTarget()) model->cancel();
    auto owner = std::move(retired).lockExclusive();
}
