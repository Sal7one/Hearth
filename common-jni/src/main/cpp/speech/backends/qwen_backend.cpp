#include "backend_guard.h"
#include "../backend_versions.h"
#include "../utterance_segmenter.h"
#include "../qwen_language.h"
#include "sherpa-onnx/c-api/c-api.h"
#include <deque>
#include <memory>

namespace stt::speech {
namespace {
struct Qwen {
    const SherpaOnnxOfflineRecognizer* recognizer = nullptr;
    UtteranceSegmenter segmenter;
    struct Text { std::string text, language; int64_t end; };
    std::deque<Text> ready;
    Text current;
    std::string sourceCode, languageName;
    explicit Qwen(const HearthSpeechConfig& c, bool moonshine = false)
        : segmenter(c.max_utterance_ms, c.silence_ms, c.silence_threshold_db),
          sourceCode(moonshine ? "en" : c.language), languageName(moonshine ? "" : qwenLanguageName(sourceCode)) {
        SherpaOnnxOfflineRecognizerConfig cfg{};
        cfg.feat_config.sample_rate = 16000;
        cfg.feat_config.feature_dim = 128;
        cfg.model_config.num_threads = c.num_threads;
        cfg.model_config.provider = "cpu";
        if (moonshine) {
            cfg.model_config.tokens = c.model;
            cfg.model_config.moonshine.encoder = c.encoder;
            cfg.model_config.moonshine.merged_decoder = c.decoder;
        } else {
        cfg.model_config.model_type = "qwen3_asr";
        auto& q = cfg.model_config.qwen3_asr;
        q.conv_frontend = c.conv_frontend; q.encoder = c.encoder;
        q.decoder = c.decoder; q.tokenizer = c.tokenizer;
        q.max_total_len = 512; q.max_new_tokens = 128;
        q.temperature = 0.000001f; q.top_p = 0.8f; q.seed = 42;
        }
        recognizer = SherpaOnnxCreateOfflineRecognizer(&cfg);
        if (!recognizer) throw std::runtime_error("SherpaOnnxCreateOfflineRecognizer returned null (upstream C API supplies no error string)");
    }
    ~Qwen() { if (recognizer) SherpaOnnxDestroyOfflineRecognizer(recognizer); }
    void decode(const std::vector<float>& audio, int64_t end) {
        if (ready.size() >= 32) throw std::runtime_error("Qwen result queue full; drain results after each push");
        auto stream = std::unique_ptr<const SherpaOnnxOfflineStream, decltype(&SherpaOnnxDestroyOfflineStream)>(
            SherpaOnnxCreateOfflineStream(recognizer), SherpaOnnxDestroyOfflineStream);
        if (!stream) throw std::runtime_error("SherpaOnnxCreateOfflineStream returned null");
        if (!languageName.empty()) SherpaOnnxOfflineStreamSetOption(stream.get(), "language", languageName.c_str());
        SherpaOnnxAcceptWaveformOffline(stream.get(), 16000, audio.data(), static_cast<int32_t>(audio.size()));
        SherpaOnnxDecodeOfflineStream(recognizer, stream.get());
        auto result = std::unique_ptr<const SherpaOnnxOfflineRecognizerResult, decltype(&SherpaOnnxDestroyOfflineRecognizerResult)>(
            SherpaOnnxGetOfflineStreamResult(stream.get()), SherpaOnnxDestroyOfflineRecognizerResult);
        if (!result) throw std::runtime_error("SherpaOnnxGetOfflineStreamResult returned null");
        ready.push_back({result->text ? result->text : "", sourceCode != "auto" ? sourceCode : (result->lang ? result->lang : ""), end});
    }
    auto consumer() { return [this](const std::vector<float>& audio, int64_t end) { decode(audio, end); }; }
};
int create(const HearthSpeechConfig* c, void** out) { return guarded([&] {
    if (!out) throw std::invalid_argument("Missing output handle");
    *out = nullptr; requireConfig(c); *out = new Qwen(*c);
}); }
int createMoonshine(const HearthSpeechConfig* c, void** out) { return guarded([&] {
    if (!out) throw std::invalid_argument("Missing output handle");
    *out = nullptr; requireConfig(c); *out = new Qwen(*c, true);
}); }
void destroy(void* p) { delete static_cast<Qwen*>(p); }
int push(void* p, const float* a, size_t n) { return guarded([&] {
    auto& q = *static_cast<Qwen*>(p); q.segmenter.push(a, n, q.consumer());
}); }
int next(void* p, HearthSpeechResult* r, int* available) { return guarded([&] {
    auto& q = *static_cast<Qwen*>(p); *available = !q.ready.empty();
    if (!*available) return;
    q.current = std::move(q.ready.front()); q.ready.pop_front();
    *r = {sizeof(*r), q.current.text.c_str(), q.current.language.c_str(), 1, q.current.end};
}); }
int finish(void* p) { return guarded([&] { auto& q = *static_cast<Qwen*>(p); q.segmenter.finish(q.consumer()); }); }
int reset(void* p) { return guarded([&] { auto& q = *static_cast<Qwen*>(p); q.segmenter.reset(); q.ready.clear(); q.current = {}; }); }
const HearthSpeechBackend moonshineApi{HEARTH_SPEECH_ABI_VERSION, sizeof(HearthSpeechBackend), "moonshine", HEARTH_QWEN_REVISION,
    createMoonshine, destroy, push, next, finish, reset, lastError};
const HearthSpeechBackend api{HEARTH_SPEECH_ABI_VERSION, sizeof(HearthSpeechBackend), "qwen3_asr", HEARTH_QWEN_REVISION,
    create, destroy, push, next, finish, reset, lastError};
}
}
extern "C" __attribute__((visibility("default"))) const HearthSpeechBackend* hearth_speech_backend_v1() { return &stt::speech::api; }

extern "C" __attribute__((visibility("default"))) const HearthSpeechBackend* hearth_moonshine_backend_v1() { return &stt::speech::moonshineApi; }
