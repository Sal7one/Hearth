// Desktop Vosk backend: HearthSpeechBackend ABI v2 around the Android VoskEngine.
//
// The pinned official libvosk.dylib is dlopen'd by an explicit absolute sibling
// path; this dylib never links Vosk at build time. The Android engine remains the
// only Vosk wrapper: this file adapts the windowed bridge protocol onto
// VoskEngine::transcribeBatch (the int16 batch path Android uses).
#include "common/json_utils.h"
#include "common/model_integrity.h"
#include "speech/backends/backend_guard.h"
#include "vosk/vosk_api.h"
#include "vosk/vosk_engine.h"

#include <dlfcn.h>
#include <sys/stat.h>

#include <algorithm>
#include <cmath>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

extern "C" const HearthSpeechBackend* hearth_speech_backend_v1();

namespace stt::speech {
namespace {

// The official v0.3.42 macOS release asset this adapter is pinned to. Kept here
// (not in backend_versions.h) because the Vosk runtime is a release artifact, not
// a source checkout.
constexpr const char* kVoskVersion = "0.3.42";
constexpr const char* kVoskRevision =
    "vosk-osx-0.3.42/65395f196c9d0583d79949142b25560acaf9c295f36284e18433097f3adb0ea1";

constexpr size_t kSampleRate = 16000;
// The JNI bridge rejects windows above 30 s; the adapter enforces the same cap
// so a direct ABI caller cannot grow the buffer without a bound.
constexpr size_t kMaximumSamples = 30 * kSampleRate;

struct ModelPin {
    const char* language;
    const char* checkpoint;
    const char* treeSha256;
};

// model-tree-sha256-v1 pins, identical to the Android catalog in
// app/src/main/java/com/sal7one/transiber/models/VoskDownloads.kt and to the
// desktop catalog cards. Vosk does not detect language: the digest identifies the
// model, and the caller's declared language must agree with it.
constexpr ModelPin kModelPins[] = {
    {"en", "vosk-model-small-en-us-0.15", "1855931c4d84fa0f731a5a7906c1356837335d49ee7f2ca6a6a32389bac7f8e8"},
    {"de", "vosk-model-small-de-0.15", "b8a470cd032c88ea1bad4b173071a30c014ffa345f45a41499747fdac69a8743"},
    {"fr", "vosk-model-small-fr-0.22", "42427727ea015131d1f3f54c21bfeb772945b8682a87b6165dc61d9aa3d4d3c4"},
    {"es", "vosk-model-small-es-0.42", "aaf9b97e2b0efadfdbaabb97900e620884908a554fd1f5072339d3f1458ad129"},
    {"ru", "vosk-model-small-ru-0.22", "4f1bd9e45f0a1c1fd669b6cb75ae1bcded09ed9e22de60247bb57a40814f0f07"},
    {"zh", "vosk-model-small-cn-0.22", "ebbc20212be98c8cf0c5076915ebf2854106a7bf5ca376b11110f28b042e4555"},
};

const ModelPin& requirePinnedModel(const std::string& modelPath) {
    const ModelPathDigestResult digest = digestPath(modelPath);
    if (!digest) {
        throw std::runtime_error(std::string("Vosk model tree could not be hashed: ") +
                                 modelIntegrityErrorMessage(digest.error));
    }
    if (digest.kind != ModelPathKind::DIRECTORY_TREE) {
        throw std::runtime_error("Vosk model must be an extracted directory tree");
    }
    const std::string observed = sha256ToHex(digest.digest);
    for (const ModelPin& pin : kModelPins) {
        if (observed == pin.treeSha256) return pin;
    }
    throw std::runtime_error("Vosk model tree is not one of the pinned models (model-tree-sha256-v1 " +
                             observed + "). Reinstall the model from the catalog.");
}

std::string declaredLanguage(const HearthSpeechConfig& config) {
    const std::string language = config.language ? config.language : "";
    if (language == "auto" || language.empty()) return "";
    if (language.size() > 16) throw std::invalid_argument("Vosk language is invalid");
    return language;
}

// The pinned runtime is a sibling of this adapter. Kotlin loads both by absolute
// path from the verified bundle; dladdr keeps that lookup explicit and prevents a
// bare soname from being resolved through DYLD paths or the working directory.
const std::string& pinnedRuntimePath() {
    static const std::string path = [] {
        Dl_info info{};
        if (dladdr(reinterpret_cast<void*>(&hearth_speech_backend_v1), &info) == 0 ||
            info.dli_fname == nullptr) {
            throw std::runtime_error("Unable to locate libhearth_vosk.dylib; cannot resolve libvosk.dylib");
        }
        const std::string self(info.dli_fname);
        const size_t slash = self.find_last_of('/');
        if (slash == std::string::npos) {
            throw std::runtime_error(
                "libhearth_vosk.dylib must be loaded by an absolute path to resolve libvosk.dylib");
        }
        const std::string candidate = self.substr(0, slash + 1) + "libvosk.dylib";
        struct stat status {};
        if (::stat(candidate.c_str(), &status) != 0 || !S_ISREG(status.st_mode)) {
            throw std::runtime_error("Pinned Vosk runtime is missing next to libhearth_vosk.dylib: " + candidate);
        }
        return candidate;
    }();
    return path;
}

struct VoskBackend {
    std::string language;
    std::string execution;
    std::vector<float> audio;
    std::string pendingText;
    std::string currentText;
    std::vector<int16_t> pcm16;
    int64_t audioEndSamples = 0;
    bool resultReady = false;
    std::unique_ptr<VoskEngine> engine;

    void decode() {
        // Feed the int16 batch path, not transcribeBatchFloat: v0.3.42's
        // vosk_recognizer_accept_waveform_f copies floats verbatim into Kaldi's
        // waveform, i.e. it expects int16-scale samples, while this ABI (like the
        // rest of Hearth) carries normalized [-1, 1]. Passing normalized floats
        // produced an empty transcript for known speech (verified on hello.wav).
        // Converting to PCM16 is exact quantization and reuses Android's int16
        // path (VoskEngine::transcribeBatch -> feedBatchInt16, 0.5 s chunks).
        pcm16.resize(audio.size());
        for (size_t i = 0; i < audio.size(); ++i) {
            const float clamped = std::min(1.0f, std::max(-1.0f, audio[i]));
            pcm16[i] = static_cast<int16_t>(std::lround(clamped * 32767.0f));
        }
        const std::string json = engine->transcribeBatch(
            pcm16.data(), static_cast<int>(pcm16.size()), static_cast<int>(kSampleRate));
        if (json.empty()) {
            throw std::runtime_error("Vosk engine returned no transcript JSON: " + engine->getLastError());
        }
        JsonValue parsed;
        std::string parseError;
        if (!JsonUtils::parse(json, parsed, &parseError) || !parsed.isObject()) {
            throw std::runtime_error("Vosk engine returned an unparsable transcript: " + parseError);
        }
        const JsonValue* cancelled = parsed.find("cancelled");
        bool wasCancelled = false;
        if (cancelled != nullptr && cancelled->asBool(wasCancelled) && wasCancelled) {
            throw std::runtime_error("Vosk recognition was cancelled");
        }
        const JsonValue* text = parsed.find("text");
        if (text == nullptr || !text->asString(pendingText)) {
            throw std::runtime_error("Vosk engine transcript JSON has no text field");
        }
        audioEndSamples = static_cast<int64_t>(audio.size());
        resultReady = true;
    }
};

int create(const HearthSpeechConfig* config, void** out) {
    return guarded([&] {
        if (out == nullptr) throw std::invalid_argument("Missing output handle");
        *out = nullptr;
        requireConfig(config);
        if (config->model == nullptr || *config->model == '\0') {
            throw std::invalid_argument("Vosk model path is required");
        }
        if (config->num_threads < 1 || config->num_threads > 32) {
            throw std::invalid_argument("Vosk threads must be 1-32");
        }
        if (config->acceleration != nullptr && std::string(config->acceleration) != "cpu") {
            throw std::invalid_argument("Vosk runs on CPU only; GPU acceleration is not supported");
        }
        const std::string modelPath = config->model;
        const std::string language = declaredLanguage(*config);
        const ModelPin& pin = requirePinnedModel(modelPath);
        if (!language.empty() && language != pin.language) {
            throw std::invalid_argument("Vosk model tree is " + std::string(pin.language) +
                                        " but the requested language is " + language);
        }
        setVoskLibraryPath(pinnedRuntimePath());
        // Vosk's own stderr logger would bypass the host log sink; real failures
        // are still surfaced through initialize/last_error.
        voskApi().vosk_set_log_level(-1);
        auto engine = std::make_unique<VoskEngine>();
        EngineConfig engineConfig;
        engineConfig.modelPath = modelPath;
        engineConfig.modelSha256 = pin.treeSha256;
        engineConfig.language = pin.language;
        engineConfig.sampleRate = static_cast<int>(kSampleRate);
        engineConfig.numThreads = config->num_threads;
        engineConfig.audioProcessingMode = AudioProcessingMode::BATCH;
        if (!engine->initialize(engineConfig)) {
            const std::string error = engine->getLastError();
            throw std::runtime_error(error.empty() ? "Vosk engine initialization failed" : error);
        }
        auto backend = std::make_unique<VoskBackend>();
        backend->language = pin.language;
        backend->execution = std::string("CPU · Vosk ") + kVoskVersion + " (Kaldi) · tree-verified";
        backend->engine = std::move(engine);
        backend->audio.reserve(engineConfig.sampleRate * 30);
        *out = backend.release();
    });
}

void destroy(void* handle) { delete static_cast<VoskBackend*>(handle); }

int push(void* handle, const float* samples, size_t count) {
    return guarded([&] {
        auto& backend = *static_cast<VoskBackend*>(handle);
        if (samples == nullptr && count != 0) throw std::invalid_argument("Vosk received a null audio pointer");
        if (backend.audio.size() + count > kMaximumSamples) {
            throw std::invalid_argument("Vosk accepts at most 30 seconds per window");
        }
        for (size_t index = 0; index < count; ++index) {
            const float value = samples[index];
            if (!std::isfinite(value) || value < -1.0f || value > 1.0f) {
                throw std::invalid_argument("Vosk received an invalid PCM sample");
            }
        }
        backend.audio.insert(backend.audio.end(), samples, samples + count);
    });
}

int next(void* handle, HearthSpeechResult* result, int* available) {
    return guarded([&] {
        auto& backend = *static_cast<VoskBackend*>(handle);
        if (result == nullptr || available == nullptr) throw std::invalid_argument("Missing Vosk result buffers");
        *available = backend.resultReady ? 1 : 0;
        if (!backend.resultReady) return;
        backend.currentText = std::move(backend.pendingText);
        backend.pendingText.clear();
        backend.resultReady = false;
        *result = {sizeof(*result), backend.currentText.c_str(), backend.language.c_str(), 1,
                   backend.audioEndSamples};
    });
}

int finish(void* handle) {
    return guarded([&] {
        auto& backend = *static_cast<VoskBackend*>(handle);
        if (backend.audio.empty()) {
            // No audio is not a failure; the bridge never sends an empty window.
            backend.pendingText.clear();
            backend.audioEndSamples = 0;
            backend.resultReady = true;
            return;
        }
        backend.decode();
        backend.audio.clear();
    });
}

int reset(void* handle) {
    return guarded([&] {
        auto& backend = *static_cast<VoskBackend*>(handle);
        backend.audio.clear();
        backend.pendingText.clear();
        backend.currentText.clear();
        backend.audioEndSamples = 0;
        backend.resultReady = false;
        backend.engine->reset();
    });
}

const HearthSpeechBackend api{HEARTH_SPEECH_ABI_VERSION, sizeof(HearthSpeechBackend), "vosk", kVoskRevision,
    create, destroy, push, next, finish, reset, lastError,
    [](void* handle) -> const char* { return static_cast<VoskBackend*>(handle)->execution.c_str(); }};

} // namespace
} // namespace stt::speech

extern "C" __attribute__((visibility("default"))) const HearthSpeechBackend* hearth_speech_backend_v1() {
    return &stt::speech::api;
}
