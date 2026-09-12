#include "backend_guard.h"
#include "../backend_versions.h"
#include "../endpoint_budget.h"
#include "nemo_speech/asr.h"
#include <memory>

namespace stt::speech {
namespace {
void check(nemo_speech_asr_status status) {
    if (status != NEMO_SPEECH_ASR_OK) {
        const char* error = nemo_speech_asr_last_error();
        // Capture before any cleanup call overwrites upstream's thread-local error.
        throw std::runtime_error(error && *error ? error : "NeMo ASR failed with status " + std::to_string(status));
    }
}
struct Nemo {
    nemo_speech_asr_recognizer* recognizer = nullptr;
    nemo_speech_asr_stream* stream = nullptr;
    nemo_speech_asr_result* result = nullptr;
    std::string language;
    int silenceMs;
    EndpointBudget endpointBudget;
    explicit Nemo(const HearthSpeechConfig& c) : language(c.language), silenceMs(c.silence_ms), endpointBudget(c.max_utterance_ms) {
        nemo_speech_asr_backend_config backend{}; backend.size = sizeof(backend); backend.gpu = -1;
        nemo_speech_asr_model_config model{}; model.size = sizeof(model); model.path = c.model;
        nemo_speech_asr_streaming_config streaming{}; streaming.size = sizeof(streaming);
        streaming.chunk_size = 0.16f; streaming.ctc_left_padding = 1.92f; streaming.ctc_right_padding = 1.92f;
        streaming.rnnt_right_context = c.right_context;
        nemo_speech_asr_endpointing_config endpoint{}; endpoint.size = sizeof(endpoint);
        endpoint.enable = true; endpoint.vad_based = false; endpoint.stop_history_eou_ms = c.silence_ms;
        nemo_speech_asr_recognizer_config cfg{}; cfg.size = sizeof(cfg);
        cfg.backend = &backend; cfg.model = &model; cfg.streaming = &streaming; cfg.endpointing = &endpoint;
        try { check(nemo_speech_asr_create(&cfg, &recognizer)); start(); }
        catch (...) { cleanup(); throw; }
    }
    ~Nemo() { cleanup(); }
    void cleanup() {
        if (result) nemo_speech_asr_result_destroy(result);
        if (stream) nemo_speech_asr_stream_close(stream);
        if (recognizer) nemo_speech_asr_destroy(recognizer);
        result = nullptr; stream = nullptr; recognizer = nullptr;
    }
    void start() {
        endpointBudget.reset();
        auto opts = nemo_speech_asr_recognition_options_default();
        opts.language_code = language.c_str(); opts.interim_results = true;
        opts.enable_word_time_offsets = false; opts.stop_history_eou_ms = silenceMs;
        check(nemo_speech_asr_streaming_recognize(recognizer, &opts, &stream));
    }
};
int create(const HearthSpeechConfig* c, void** out) { return guarded([&] {
    if (!out) throw std::invalid_argument("Missing output handle");
    *out = nullptr; requireConfig(c); *out = new Nemo(*c);
}); }
void destroy(void* p) { delete static_cast<Nemo*>(p); }
int push(void* p, const float* a, size_t n) { return guarded([&] {
    check(nemo_speech_asr_stream_push_f32(static_cast<Nemo*>(p)->stream, a, n, 16000));
}); }
int next(void* p, HearthSpeechResult* out, int* available) { return guarded([&] {
    auto& n = *static_cast<Nemo*>(p);
    if (n.result) { nemo_speech_asr_result_destroy(n.result); n.result = nullptr; }
    check(nemo_speech_asr_stream_next(n.stream, &n.result));
    *available = n.result != nullptr;
    if (!*available) return;
    const bool hasText = nemo_speech_asr_result_alternative_count(n.result) != 0;
    const auto languageCount = hasText ? nemo_speech_asr_result_language_count(n.result, 0) : 0;
    // Never label a mixed-language final as entirely its first detected language.
    const char* lang = languageCount > 1 ? "mul" : languageCount == 1
        ? nemo_speech_asr_result_language_code(n.result, 0, 0)
        : n.language != "auto" ? n.language.c_str() : "";
    *out = {sizeof(*out), hasText ? nemo_speech_asr_result_transcript(n.result, 0) : "", lang,
        nemo_speech_asr_result_is_final(n.result) ? 1 : 0,
        static_cast<int64_t>(nemo_speech_asr_result_audio_processed(n.result) * 16000.0)};
    if (n.endpointBudget.observe(out->text && *out->text, out->is_final != 0, out->audio_end_samples))
        check(nemo_speech_asr_stream_force_endpoint(n.stream));
}); }
int finish(void* p) { return guarded([&] { check(nemo_speech_asr_stream_finish(static_cast<Nemo*>(p)->stream)); }); }
int reset(void* p) { return guarded([&] {
    auto& n = *static_cast<Nemo*>(p);
    if (n.result) { nemo_speech_asr_result_destroy(n.result); n.result = nullptr; }
    if (n.stream) { nemo_speech_asr_stream_close(n.stream); n.stream = nullptr; }
    n.start();
}); }
const HearthSpeechBackend api{HEARTH_SPEECH_ABI_VERSION, sizeof(HearthSpeechBackend), "nemotron_3_5", HEARTH_NEMO_REVISION,
    create, destroy, push, next, finish, reset, lastError};
}
}
extern "C" __attribute__((visibility("default"))) const HearthSpeechBackend* hearth_speech_backend_v1() { return &stt::speech::api; }
