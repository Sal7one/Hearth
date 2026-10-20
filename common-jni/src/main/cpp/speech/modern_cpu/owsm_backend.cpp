#include "../backends/backend_guard.h"
#include "../utterance_segmenter.h"
#include "versions.h"
#include "audiocpp.h"
#include <cstring>
#include <deque>
#include <memory>

namespace stt::speech {
namespace {
void check(audiocpp_status status, const char* operation) {
    if (status == AUDIOCPP_OK) return;
    // The C API error is thread-local and cleanup can clear it.
    const std::string detail = audiocpp_last_error();
    throw std::runtime_error(std::string("OWSM ") + operation + ": " +
        (detail.empty() ? audiocpp_status_string(status) : detail));
}
template<class T, void (*Release)(T*)> using Handle = std::unique_ptr<T, decltype(Release)>;
struct OWSM {
    Handle<audiocpp_registry, audiocpp_registry_free> registry{nullptr, audiocpp_registry_free};
    Handle<audiocpp_model, audiocpp_model_free> model{nullptr, audiocpp_model_free};
    Handle<audiocpp_session, audiocpp_session_free> session{nullptr, audiocpp_session_free};
    UtteranceSegmenter segmenter;
    std::string language;
    std::string execution;
    struct Text { std::string text, language; int64_t end; };
    std::deque<Text> ready;
    Text current;
    explicit OWSM(const HearthSpeechConfig& c)
        : segmenter(c.max_utterance_ms, c.silence_ms, c.silence_threshold_db) {
        if (!c.acceleration || std::strcmp(c.acceleration, "cpu")) throw std::invalid_argument("OWSM currently supports CPU only");
        if (!c.model || !*c.model || !c.language || c.num_threads < 1 || c.num_threads > 8) throw std::invalid_argument("Invalid OWSM model, language or thread count");
        const std::string source = c.language;
        if (source != "auto" && source != "ar" && source != "en") throw std::invalid_argument("OWSM source language must be Auto, Arabic or English");
        language = source == "ar" ? "ara" : source == "en" ? "eng" : "auto";
        audiocpp_registry* reg = nullptr;
        auto status = audiocpp_registry_create(nullptr, &reg); registry.reset(reg); check(status, "registry");
        audiocpp_model_config config{"owsm", nullptr, nullptr, "embedded"};
        audiocpp_model* loaded = nullptr;
        status = audiocpp_model_load(registry.get(), c.model, &config, nullptr, &loaded); model.reset(loaded); check(status, "model load");
        if (!model || std::string(audiocpp_model_family(model.get())) != "owsm" || !audiocpp_model_supports(model.get(), "asr", "offline"))
            throw std::runtime_error("OWSM requires the supported OWSM v4 Base ASR GGUF");
        const audiocpp_backend_config backend{"cpu", 0, c.num_threads};
        audiocpp_session* opened = nullptr;
        status = audiocpp_session_create(model.get(), "asr", "offline", &backend, nullptr, &opened); session.reset(opened); check(status, "session create");
        if (!session) throw std::runtime_error("OWSM created no session");
        execution = "OWSM v4 Base · frontend: CPU · encoder: CPU · decoder: CPU · greedy beam 1 · threads: " + std::to_string(c.num_threads);
    }
    void decode(const std::vector<float>& audio, int64_t end) {
        if (ready.size() >= 32) throw std::runtime_error("OWSM result queue full; drain after each push");
        Handle<audiocpp_request, audiocpp_request_free> request(audiocpp_request_create(), audiocpp_request_free);
        if (!request) throw std::runtime_error("OWSM request allocation failed");
        check(audiocpp_request_set_audio(request.get(), audio.data(), audio.size(), 16000, 1), "audio input");
        check(audiocpp_request_set_option(request.get(), "language", language.c_str()), "source language");
        check(audiocpp_request_set_option(request.get(), "num_beams", "1"), "beam size");
        check(audiocpp_request_set_option(request.get(), "max_tokens", "374"), "token budget");
        check(audiocpp_request_set_option(request.get(), "condition_on_previous_text", "false"), "context isolation");
        check(audiocpp_request_set_option(request.get(), "return_timestamps", "false"), "transcription mode");
        audiocpp_result* raw = nullptr;
        const auto status = audiocpp_session_run(session.get(), request.get(), &raw);
        // Own even a non-null result returned together with a failure.
        Handle<audiocpp_result, audiocpp_result_free> result(raw, audiocpp_result_free);
        check(status, "decode");
        if (!result) throw std::runtime_error("OWSM returned no result");
        const char* text = nullptr; const char* detected = nullptr;
        check(audiocpp_result_text(result.get(), &text, &detected), "transcript");
        if (!text) throw std::runtime_error("OWSM returned a null transcript");
        const std::string code = detected ? detected : "";
        // Only actual output supplies a detected language. Auto is never guessed as English.
        const std::string iso = code == "ara" ? "ar" : code == "eng" ? "en" : "";
        ready.push_back({text, iso, end});
    }
    auto consumer() { return [this](const std::vector<float>& audio, int64_t end) { decode(audio, end); }; }
};
int create(const HearthSpeechConfig* c, void** out) { return guarded([&] {
    if (!out) throw std::invalid_argument("Missing OWSM output handle");
    *out = nullptr; requireConfig(c);
    *out = new OWSM(*c);
}); }
void destroy(void* p) { delete static_cast<OWSM*>(p); }
int push(void* p, const float* a, size_t n) { return guarded([&] { auto& s = *static_cast<OWSM*>(p); s.segmenter.push(a,n,s.consumer()); }); }
int next(void* p, HearthSpeechResult* r, int* available) { return guarded([&] {
    if (!r || !available || r->size != sizeof(*r)) throw std::invalid_argument("OWSM result ABI mismatch");
    auto& s = *static_cast<OWSM*>(p); *available = !s.ready.empty(); if (!*available) return;
    s.current = std::move(s.ready.front()); s.ready.pop_front();
    *r = {sizeof(*r), s.current.text.c_str(), s.current.language.c_str(), 1, s.current.end};
}); }
int finish(void* p) { return guarded([&] { auto& s = *static_cast<OWSM*>(p); s.segmenter.finish(s.consumer()); }); }
int reset(void* p) { return guarded([&] { auto& s = *static_cast<OWSM*>(p); s.segmenter.reset(); s.ready.clear(); s.current = {}; }); }
const HearthSpeechBackend api{HEARTH_SPEECH_ABI_VERSION, sizeof(HearthSpeechBackend), "owsm", HEARTH_OWSM_REVISION,
    create,destroy,push,next,finish,reset,lastError,[](void* p) { return static_cast<OWSM*>(p)->execution.c_str(); }};
}
}
extern "C" __attribute__((visibility("default"))) const HearthSpeechBackend* hearth_owsm_backend_v1() { return &stt::speech::api; }
