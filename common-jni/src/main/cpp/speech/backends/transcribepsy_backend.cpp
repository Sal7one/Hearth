#include "backend_guard.h"
#include "../backend_versions.h"
#include "../utterance_segmenter.h"
#include "parakeet/engine.h"
#include <deque>
#include <memory>
#include <cstring>
#ifdef HEARTH_TRANSCRIBEPSY_OPENCL
#include "../../common/opencl_loader.h"
#endif

#ifndef HEARTH_TRANSCRIBEPSY_ACCELERATION
#define HEARTH_TRANSCRIBEPSY_ACCELERATION "cpu"
#endif

namespace stt::speech {
namespace {
struct TranscribePsy {
    std::string device;
    parakeet::Engine engine;
    UtteranceSegmenter segmenter;
    struct Text { std::string text; int64_t end; };
    std::deque<Text> ready;
    Text current;
    // The eight-language checkpoint has no language-conditioning/detection
    // output. An explicit source is the caller's declaration; Auto stays unknown.
    std::string source;
    std::string execution;
    static parakeet::EngineOptions options(const HearthSpeechConfig& c) {
        parakeet::EngineOptions options;
        options.model_gguf_path = c.model;
        options.n_threads = c.num_threads;
        if (!c.acceleration || std::string(c.acceleration) != HEARTH_TRANSCRIBEPSY_ACCELERATION)
            throw std::invalid_argument("TranscribePsy runtime acceleration mismatch");
        options.n_gpu_layers = std::string(c.acceleration) == "cpu" ? 0 : 99;
        options.opencl_cache_dir = c.gpu_cache_directory ? c.gpu_cache_directory : "";
        return options;
    }
    explicit TranscribePsy(const HearthSpeechConfig& c)
        :
#ifdef HEARTH_TRANSCRIBEPSY_OPENCL
          device(hearth::opencl::requireAdreno()),
#endif
          engine(options(c)), segmenter(c.max_utterance_ms, c.silence_ms, c.silence_threshold_db),
          source(std::string(c.language) == "auto" ? "" : c.language) {
        if (engine.model_type() != "tdt")
            throw std::invalid_argument("TranscribePsy requires a FastConformer TDT GGUF model");
        const std::string requested = c.acceleration;
        const auto decoder = engine.backend_name();
        const auto encoder = engine.encoder_backend();
        if (requested != "cpu" && (engine.backend_device() != parakeet::BackendDevice::GPU ||
            (requested == "vulkan" && decoder.rfind("Vulkan", 0) != 0) ||
            (requested == "opencl" && decoder.rfind("OpenCL", 0) != 0) || encoder != decoder))
            throw std::runtime_error("TranscribePsy " + requested + " GPU was requested but resolved to encoder " + encoder +
                ", decoder " + decoder + ". Select CPU explicitly to retry.");
        execution = "TranscribePsy · mel: CPU · encoder: " + encoder + " · decoder: " + decoder;
        if (!device.empty()) execution += " · " + device;
    }
    void decode(const std::vector<float>& audio, int64_t end) {
        if (ready.size() >= 32) throw std::runtime_error("TranscribePsy result queue full; drain results after each push");
        // This is a complete, bounded utterance. Do not pretend that sliding
        // offline windows provide a cache-aware streaming decoder.
        auto result = engine.transcribe_samples(audio.data(), static_cast<int>(audio.size()), 16000);
        ready.push_back({std::move(result.text), end});
    }
    auto consumer() { return [this](const std::vector<float>& audio, int64_t end) { decode(audio, end); }; }
};
int create(const HearthSpeechConfig* c, void** out) { return guarded([&] {
    if (!out) throw std::invalid_argument("Missing output handle");
    *out = nullptr;
    requireConfig(c);
    *out = new TranscribePsy(*c);
}); }
void destroy(void* p) { delete static_cast<TranscribePsy*>(p); }
int push(void* p, const float* a, size_t n) { return guarded([&] {
    auto& t = *static_cast<TranscribePsy*>(p);
    t.segmenter.push(a, n, t.consumer());
}); }
int next(void* p, HearthSpeechResult* r, int* available) { return guarded([&] {
    auto& t = *static_cast<TranscribePsy*>(p);
    *available = !t.ready.empty();
    if (!*available) return;
    t.current = std::move(t.ready.front()); t.ready.pop_front();
    *r = {sizeof(*r), t.current.text.c_str(), t.source.c_str(), 1, t.current.end};
}); }
int finish(void* p) { return guarded([&] {
    auto& t = *static_cast<TranscribePsy*>(p); t.segmenter.finish(t.consumer());
}); }
int reset(void* p) { return guarded([&] {
    auto& t = *static_cast<TranscribePsy*>(p);
    t.segmenter.reset(); t.ready.clear(); t.current = {};
}); }
const HearthSpeechBackend api{HEARTH_SPEECH_ABI_VERSION, sizeof(HearthSpeechBackend), "transcribepsy", HEARTH_TRANSCRIBEPSY_REVISION,
    create, destroy, push, next, finish, reset, lastError,
    [](void* p) -> const char* { return static_cast<TranscribePsy*>(p)->execution.c_str(); }};
}
}
extern "C" __attribute__((visibility("default"))) const HearthSpeechBackend* hearth_speech_backend_v1() {
    return &stt::speech::api;
}
