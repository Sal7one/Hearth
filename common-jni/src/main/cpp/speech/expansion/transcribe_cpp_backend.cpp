#include "../backends/backend_guard.h"
#include "versions.h"
#include "../utterance_segmenter.h"
#include "transcribe.h"
#include "transcribe/voxtral_realtime.h"
#include <algorithm>
#include <array>
#include <cctype>
#include <deque>
#include <memory>
#include <mutex>

#ifndef HEARTH_TRANSCRIBE_ACCELERATION
#define HEARTH_TRANSCRIBE_ACCELERATION "cpu"
#endif

namespace stt::speech::expansion {
namespace {
thread_local std::string engineError;
void check(transcribe_status status) {
    if (status != TRANSCRIBE_OK) {
        const char* description = transcribe_status_string(status);
        throw std::runtime_error(std::string("transcribe.cpp: ") + (description ? description : "unknown status") +
            (engineError.empty() ? "" : ": " + engineError));
    }
}
void initialize() {
    static std::once_flag once;
    std::call_once(once, [] {
        // Install once before upstream creates models or worker threads. Storage
        // has thread lifetime; never point its global callback at an instance.
        transcribe_log_set([](transcribe_log_level level, const char* text, void*) {
            if (level == TRANSCRIBE_LOG_LEVEL_ERROR && text) engineError.assign(text, std::min<size_t>(std::strlen(text), 4096));
        }, nullptr);
        transcribe_backend_init_params params;
        transcribe_backend_init_params_init(&params);
        params.allowed_backends = TRANSCRIBE_BACKEND_MASK_CPU;
        if (std::string(HEARTH_TRANSCRIBE_ACCELERATION) == "vulkan") params.allowed_backends |= TRANSCRIBE_BACKEND_MASK_VULKAN;
        check(transcribe_init_backends_ex(&params));
    });
}
enum class Kind { Cohere, Fun, Voxtral };
struct Spec { const char *id, *arch, *variant, *label; };
Spec specification(Kind kind) {
    if (kind == Kind::Cohere) return {"cohere_asr", "cohere_asr", "cohere-transcribe-arabic-07-2026", "Cohere Arabic"};
    if (kind == Kind::Fun) return {"funasr_mlt_nano", "funasr_nano", "fun-asr-mlt-nano-2512", "Fun-ASR MLT Nano"};
    return {"voxtral_realtime", "voxtral_realtime", "voxtral-mini-4b-realtime-2602", "Voxtral Realtime"};
}
struct ModelDeleter { void operator()(transcribe_model* model) const { transcribe_model_free(model); } };
struct SessionDeleter { void operator()(transcribe_session* session) const { transcribe_session_free(session); } };
struct Recognizer {
    Kind kind;
    Spec spec;
    // Declaration order ensures session is freed before its borrowed model.
    std::unique_ptr<transcribe_model, ModelDeleter> model;
    std::unique_ptr<transcribe_session, SessionDeleter> session;
    UtteranceSegmenter segmenter;
    std::string language, execution;
    struct Text { std::string text, language; bool final; int64_t end; };
    std::deque<Text> ready;
    Text current;
    // Voxtral's pinned partial path reprocesses accumulated audio. Endpoint
    // every bounded window, rather than retaining a recording-length buffer.
    AudioGate gate;
    std::array<float, 320> frame{};
    std::deque<float> preroll;
    size_t frameCount = 0, windowSamples = 0, quiet = 0;
    int64_t position = 0;
    size_t maximum, silence;
    bool active = false;
    static AudioGateConfig gateConfig(float db) {
        AudioGateConfig c; c.rmsThresholdDb = db; c.minActiveSamples = c.minSilenceSamples = 320;
        c.smoothingAlpha = c.attackAlpha = 1; c.zeroCrossingMax = 1; return c;
    }
    Recognizer(Kind selected, const HearthSpeechConfig& c) : kind(selected), spec(specification(selected)),
        segmenter(c.max_utterance_ms, c.silence_ms, c.silence_threshold_db), language(c.language ? c.language : ""),
        gate(gateConfig(c.silence_threshold_db)), maximum(static_cast<size_t>(c.max_utterance_ms) * 16),
        silence(static_cast<size_t>(c.silence_ms) * 16) {
        requireConfig(&c);
        if (!c.model || !*c.model || !c.language || !c.acceleration || std::string(c.acceleration) != HEARTH_TRANSCRIBE_ACCELERATION)
            throw std::invalid_argument("transcribe.cpp model, language or acceleration mismatch");
        if (kind == Kind::Cohere && language != "ar" && language != "en")
            throw std::invalid_argument("Cohere Arabic requires ar or en; automatic language detection is unavailable");
        if (kind == Kind::Voxtral) {
            const std::string supported = " auto ar en fr es de ru zh ja it pt nl hi ko ";
            if (supported.find(" " + language + " ") == std::string::npos) throw std::invalid_argument("Unsupported Voxtral source declaration");
        }
        if (c.num_threads < 1 || c.num_threads > 8) throw std::invalid_argument("Speech thread budget must be 1..8");
        initialize();
        transcribe_model_load_params load; transcribe_model_load_params_init(&load);
        load.backend = std::string(c.acceleration) == "vulkan" ? TRANSCRIBE_BACKEND_VULKAN : TRANSCRIBE_BACKEND_CPU;
        transcribe_model* createdModel = nullptr;
        const auto loaded = transcribe_model_load_file(c.model, &load, &createdModel);
        model.reset(createdModel); check(loaded);
        if (!model) throw std::runtime_error("transcribe.cpp returned no model");
        const auto arch = transcribe_model_arch_string(model.get()), variant = transcribe_model_variant_string(model.get());
        if (!arch || spec.arch != std::string(arch) || !variant || spec.variant != std::string(variant))
            throw std::invalid_argument(std::string(spec.label) + " GGUF architecture/variant mismatch");
        transcribe_capabilities caps; transcribe_capabilities_init(&caps);
        check(transcribe_model_get_capabilities(model.get(), &caps));
        if (caps.native_sample_rate != 16000 || (kind == Kind::Voxtral && !caps.supports_streaming))
            throw std::invalid_argument("Speech GGUF capabilities do not match this adapter");
        transcribe_session_params params; transcribe_session_params_init(&params); params.n_threads = c.num_threads;
        transcribe_session* createdSession = nullptr;
        const auto opened = transcribe_session_init(model.get(), &params, &createdSession);
        session.reset(createdSession); check(opened);
        if (!session) throw std::runtime_error("transcribe.cpp returned no session");
        verifyBackend();
    }
    void verifyBackend() {
        const char* backend = transcribe_model_backend(model.get());
        std::string resolved = backend ? backend : "";
        std::transform(resolved.begin(), resolved.end(), resolved.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        if (resolved != HEARTH_TRANSCRIBE_ACCELERATION)
            throw std::runtime_error(std::string(spec.label) + " requested " + HEARTH_TRANSCRIBE_ACCELERATION +
                " but resolved to " + resolved + ". Select CPU explicitly to retry.");
        execution = std::string(spec.label) + " · frontend: CPU · primary compute: " + resolved +
            (kind == Kind::Voxtral ? " · bounded windowed partials" : " · utterance finals");
    }
    transcribe_run_params runOptions() const {
        transcribe_run_params params; transcribe_run_params_init(&params);
        params.task = TRANSCRIBE_TASK_TRANSCRIBE; params.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
        // Voxtral cannot force a language. A known source is only the caller's
        // declaration for downstream MT when this port reports no detected language.
        params.language = kind == Kind::Voxtral || language == "auto" ? nullptr : language.c_str(); return params;
    }
    void capture(bool final, int64_t end) {
        if (transcribe_was_truncated(session.get())) throw std::runtime_error("transcribe.cpp output truncated; transcript is incomplete");
        verifyBackend();
        if (ready.size() >= 32) throw std::runtime_error("Speech result queue full; drain after every push");
        const char* text = transcribe_full_text(session.get()), *source = transcribe_detected_language(session.get());
        ready.push_back({text ? text : "", source && *source ? source : language == "auto" ? "" : language, final, end});
    }
    void decode(const std::vector<float>& audio, int64_t end) {
        const auto options = runOptions(); check(transcribe_run(session.get(), audio.data(), static_cast<int>(audio.size()), &options));
        capture(true, end);
    }
    auto consumer() { return [this](const std::vector<float>& audio, int64_t end) { decode(audio, end); }; }
    void begin() {
        transcribe_stream_reset(session.get());
        const auto options = runOptions();
        transcribe_stream_params params; transcribe_stream_params_init(&params);
        transcribe_voxtral_realtime_stream_ext ext; transcribe_voxtral_realtime_stream_ext_init(&ext);
        ext.num_delay_tokens = 6; ext.min_decode_interval_ms = 500;
        if (!transcribe_model_accepts_ext_kind(model.get(), TRANSCRIBE_EXT_SLOT_STREAM, ext.ext.kind))
            throw std::invalid_argument("Voxtral GGUF does not accept the required streaming extension");
        params.family = &ext.ext; check(transcribe_stream_begin(session.get(), &options, &params));
        active = true; windowSamples = quiet = 0;
    }
    void feed(const float* audio, size_t count) {
        transcribe_stream_update update; transcribe_stream_update_init(&update);
        check(transcribe_stream_feed(session.get(), audio, static_cast<int>(count), &update));
        windowSamples += count;
        if (update.result_changed) capture(false, position);
    }
    void endpoint() {
        if (!active) return;
        transcribe_stream_update update; transcribe_stream_update_init(&update);
        check(transcribe_stream_finalize(session.get(), &update)); capture(true, position);
        transcribe_stream_reset(session.get()); active = false; windowSamples = quiet = 0; preroll.clear();
    }
    void consumeFrame(const float* audio, size_t count) {
        position += static_cast<int64_t>(count);
        const bool speech = gate.analyzeFloat(audio, count).isActive;
        if (!active && speech) {
            begin();
            if (!preroll.empty()) { std::vector<float> prior(preroll.begin(), preroll.end()); feed(prior.data(), prior.size()); preroll.clear(); }
        }
        if (active) {
            feed(audio, count); quiet = speech ? 0 : quiet + count;
            if (windowSamples >= maximum || quiet >= silence) endpoint();
        } else {
            preroll.insert(preroll.end(), audio, audio + count);
            while (preroll.size() > 3200) preroll.pop_front();
        }
    }
    void push(const float* audio, size_t count) {
        if (kind != Kind::Voxtral) { segmenter.push(audio, count, consumer()); return; }
        while (count) {
            const size_t take = std::min(count, frame.size() - frameCount);
            std::copy_n(audio, take, frame.data() + frameCount);
            frameCount += take; audio += take; count -= take;
            if (frameCount == frame.size()) { consumeFrame(frame.data(), frameCount); frameCount = 0; }
        }
    }
    void finish() {
        if (kind != Kind::Voxtral) { segmenter.finish(consumer()); return; }
        if (frameCount) { consumeFrame(frame.data(), frameCount); frameCount = 0; }
        endpoint(); preroll.clear();
    }
    void reset() {
        segmenter.reset(); transcribe_stream_reset(session.get()); gate.reset();
        frameCount = windowSamples = quiet = 0; position = 0; active = false; preroll.clear(); ready.clear(); current = {};
    }
};
template<Kind K> int create(const HearthSpeechConfig* c, void** out) { return guarded([&] {
    if (!out) throw std::invalid_argument("Missing output handle"); *out = nullptr;
    requireConfig(c); engineError.clear(); *out = new Recognizer(K, *c);
}); }
void destroy(void* p) { delete static_cast<Recognizer*>(p); }
int push(void* p, const float* a, size_t n) { return guarded([&] { engineError.clear(); static_cast<Recognizer*>(p)->push(a, n); }); }
int next(void* p, HearthSpeechResult* r, int* available) { return guarded([&] {
    auto& t = *static_cast<Recognizer*>(p); *available = !t.ready.empty(); if (!*available) return;
    t.current = std::move(t.ready.front()); t.ready.pop_front();
    *r = {sizeof(*r), t.current.text.c_str(), t.current.language.c_str(), t.current.final ? 1 : 0, t.current.end};
}); }
int finish(void* p) { return guarded([&] { engineError.clear(); static_cast<Recognizer*>(p)->finish(); }); }
int reset(void* p) { return guarded([&] { engineError.clear(); static_cast<Recognizer*>(p)->reset(); }); }
const char* execution(void* p) { return static_cast<Recognizer*>(p)->execution.c_str(); }
const HearthSpeechBackend cohere{HEARTH_SPEECH_ABI_VERSION, sizeof(HearthSpeechBackend), "cohere_asr", HEARTH_TRANSCRIBE_CPP_REVISION,
    create<Kind::Cohere>, destroy, push, next, finish, reset, lastError, execution};
const HearthSpeechBackend funasr{HEARTH_SPEECH_ABI_VERSION, sizeof(HearthSpeechBackend), "funasr_mlt_nano", HEARTH_TRANSCRIBE_CPP_REVISION,
    create<Kind::Fun>, destroy, push, next, finish, reset, lastError, execution};
const HearthSpeechBackend voxtral{HEARTH_SPEECH_ABI_VERSION, sizeof(HearthSpeechBackend), "voxtral_realtime", HEARTH_TRANSCRIBE_CPP_REVISION,
    create<Kind::Voxtral>, destroy, push, next, finish, reset, lastError, execution};
}
}
extern "C" __attribute__((visibility("default"))) const HearthSpeechBackend* hearth_cohere_backend_v1() { return &stt::speech::expansion::cohere; }
extern "C" __attribute__((visibility("default"))) const HearthSpeechBackend* hearth_funasr_backend_v1() { return &stt::speech::expansion::funasr; }
extern "C" __attribute__((visibility("default"))) const HearthSpeechBackend* hearth_voxtral_backend_v1() { return &stt::speech::expansion::voxtral; }
