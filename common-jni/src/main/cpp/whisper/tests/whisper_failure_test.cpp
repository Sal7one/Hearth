#include "../whisper_engine.h"
#include "model_integrity.h"
#include "json_utils.h"
#include "whisper.h"
#include "../whisper_runtime_api.h"

#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <deque>
#include <functional>
#include <future>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <unistd.h>

// Exercise the production engine, including its background worker and verified
// model loader, against a deterministic C API. No weights or inference runtime.
struct whisper_context { int segments = 0; };
namespace {
int checks = 0;
std::mutex planMutex;
std::deque<int> statuses;
int successfulSegments = 1;
std::function<void()> duringDecode;
std::atomic<int> calls{0};
std::atomic<int> liveContexts{0};
struct DecodeParameters { int threads, audioContext; bool detectOnly, autoLanguage; };
std::vector<DecodeParameters> parameters;

void check(bool condition) {
    ++checks;
    if (!condition) std::cerr << "Failed Whisper check " << checks << '\n';
    assert(condition);
}
void plan(std::initializer_list<int> results, int segments = 1, std::function<void()> callback = {}) {
    std::lock_guard<std::mutex> lock(planMutex);
    statuses = results; successfulSegments = segments; duringDecode = std::move(callback); calls = 0;
    parameters.clear();
}
struct ModelFile {
    char path[64] = "/tmp/hearth-whisper-stub-XXXXXX";
    ModelFile() {
        const int fd = mkstemp(path);
        check(fd >= 0);
        check(write(fd, "stub", 4) == 4);
        close(fd);
    }
    ~ModelFile() { unlink(path); }
    stt::EngineConfig config(const std::string& language = "en") const {
        stt::EngineConfig c;
        c.modelPath = path; c.modelSha256 = stt::sha256ToHex(stt::Sha256::hash("stub"));
        c.language = language; c.enableVad = false; c.chunkDurationMs = 4000;
        return c;
    }
};
void partialFails(stt::WhisperEngine& engine, const std::string& message) {
    try { engine.getPartial(); check(false); }
    catch (const std::runtime_error& e) { check(e.what() == message); }
}
}

extern "C" {
whisper_context_params whisper_context_default_params() { return {}; }
whisper_full_params whisper_full_default_params(whisper_sampling_strategy) { return {}; }
whisper_context* whisper_init_with_params(whisper_model_loader* loader, whisper_context_params) {
    char bytes[4];
    if (loader->read(loader->context, bytes, sizeof(bytes)) != sizeof(bytes)) return nullptr;
    ++liveContexts;
    return new whisper_context;
}
void whisper_free(whisper_context* ctx) { --liveContexts; delete ctx; }
void whisper_log_set(ggml_log_callback, void*) {}
int whisper_full(whisper_context* ctx, whisper_full_params params, const float*, int) {
    int code = 0;
    std::function<void()> callback;
    {
        std::lock_guard<std::mutex> lock(planMutex);
        if (!statuses.empty()) { code = statuses.front(); statuses.pop_front(); }
        // A failed decoder may leave previous segments behind. They must
        // never be serialized as the new request's successful transcript.
        // Match upstream: detect_language is a language-only request, while
        // language=nullptr with detect_language=false detects then transcribes.
        ctx->segments = code ? 1 : params.detect_language ? 0 : successfulSegments;
        parameters.push_back({params.n_threads, params.audio_ctx, params.detect_language, params.language == nullptr});
        callback = duringDecode;
    }
    ++calls;
    if (callback) callback();
    return code;
}
int whisper_full_n_segments(whisper_context* ctx) { return ctx->segments; }
int whisper_full_lang_id(whisper_context*) { return 0; }
int64_t whisper_full_get_segment_t0(whisper_context*, int) { return 0; }
int64_t whisper_full_get_segment_t1(whisper_context*, int) { return 100; }
const char* whisper_full_get_segment_text(whisper_context*, int) { return " recovered text"; }
int whisper_full_n_tokens(whisper_context*, int) { return 1; }
whisper_token_data whisper_full_get_token_data(whisper_context*, int, int) {
    whisper_token_data token{}; token.p = 0.9f; return token;
}
int whisper_lang_id(const char* language) { return std::string(language) == "en" ? 0 : -1; }
int whisper_lang_max_id() { return 0; }
const char* whisper_lang_str(int) { return "en"; }
int whisper_model_type(whisper_context*) { return 0; }
int whisper_pcm_to_mel(whisper_context*, const float*, int, int) { return 0; }
int whisper_lang_auto_detect(whisper_context*, int, int, float* probabilities) { probabilities[0] = 1; return 0; }
whisper_token whisper_token_lang(whisper_context*, int) { return 0; }
}

int main() {
    const ModelFile model;
    {
        auto api = hearthWhisperCpuApi();
        check(hearthWhisperApiValid(&api));
        check(!hearthWhisperApiValid(nullptr));
        auto wrong = api; wrong.version = 2; check(!hearthWhisperApiValid(&wrong));
        wrong = api; wrong.bytes -= 1; check(!hearthWhisperApiValid(&wrong));
        wrong = api; wrong.revision = "other"; check(!hearthWhisperApiValid(&wrong));
        wrong = api; wrong.whisper_free = nullptr; check(!hearthWhisperApiValid(&wrong));
        wrong = api; wrong.deviceDescription = nullptr; check(!hearthWhisperApiValid(&wrong));
        stt::WhisperEngine engine;
        auto config = model.config(); config.whisperAcceleration = "bad";
        check(!engine.initialize(config));
        check(engine.getLastError().find("Unknown Whisper acceleration") != std::string::npos);
        check(!engine.isInitialized());
        check(engine.initialize(model.config()));
        check(engine.getRuntimeDescription() == "CPU");
        engine.release();
        check(engine.getRuntimeDescription().empty());
        config.whisperAcceleration = "vulkan";
        check(!engine.initialize(config)); // Optional DSO deliberately absent in this host suite.
        check(engine.getLastError().find("Whisper Vulkan runtime is unavailable") != std::string::npos);
        check(!engine.isInitialized());
        check(engine.initialize(model.config())); // CPU remains usable after an explicit GPU failure.
        check(engine.getRuntimeDescription() == "CPU");
        stt::EngineConfig parsed;
        std::string error;
        check(stt::parseConfig("{}", parsed, &error) && parsed.whisperAcceleration == "cpu");
        check(stt::parseConfig(R"({"whisperAcceleration":"vulkan"})", parsed, &error));
        check(parsed.whisperAcceleration == "vulkan");
        check(!stt::parseConfig(R"({"whisperAcceleration":"auto"})", parsed, &error));
        check(parsed.whisperAcceleration == "vulkan"); // Failed parsing never partly mutates config.
        check(!stt::parseConfig(R"({"whisperAcceleration":true})", parsed, &error));
    }
    const std::vector<float> audio(320, 0.1f);
    {
        stt::WhisperEngine engine;
        check(engine.initialize(model.config()));
        plan({-17});
        check(engine.transcribeBatchFloat(audio.data(), audio.size(), 16000).empty());
        check(engine.getLastError() == "whisper_full (pass#1) failed: -17");
        partialFails(engine, engine.getLastError());
        // A new batch has its own timeline and may recover without reloading.
        plan({0});
        check(engine.transcribeBatchFloat(audio.data(), audio.size(), 16000).find("recovered text") != std::string::npos);
    }
    {
        stt::WhisperEngine engine;
        check(engine.initialize(model.config("auto")));
        plan({0});
        check(engine.transcribeBatchFloat(audio.data(), audio.size(), 16000).find("recovered text") != std::string::npos);
        check(calls == 1);
        check(parameters[0].autoLanguage && !parameters[0].detectOnly);
        check(parameters[0].audioContext == 0); // file quality keeps the full context
        plan({0}, 0);
        stt::JsonValue parsed; std::string text;
        check(stt::JsonUtils::parse(engine.transcribeBatchFloat(audio.data(), audio.size(), 16000), parsed));
        check(parsed.find("text") && parsed.find("text")->asString(text) && text.empty());
        check(calls == 1); // genuine silence must not force a speech retry
    }
    {
        stt::WhisperEngine engine;
        auto config = model.config("auto"); config.numThreads = 2;
        check(engine.initialize(config));
        std::promise<void> decoding;
        auto entered = decoding.get_future();
        plan({0}, 1, [&] { decoding.set_value(); });
        const std::vector<float> window(96000, 0.1f);
        const auto start = std::chrono::steady_clock::now();
        check(engine.pushAudioFloat(window.data(), window.size(), 16000) == 0);
        check(entered.wait_for(std::chrono::seconds(5)) == std::future_status::ready);
        const auto firstDecodeUs = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - start).count();
        check(engine.resetChecked()); // join before inspecting captured parameters
        check(calls == 1);
        check(parameters[0].threads == 2);
        check(parameters[0].audioContext == 316); // 6s / 20ms + 16 context steps
        check(parameters[0].autoLanguage && !parameters[0].detectOnly);

        std::promise<void> freshDecode;
        auto freshEntered = freshDecode.get_future();
        plan({0}, 1, [&] { freshDecode.set_value(); });
        const std::vector<float> firstSecond(16000, 0.1f);
        check(engine.pushAudioFloat(firstSecond.data(), firstSecond.size(), 16000) == 0);
        check(freshEntered.wait_for(std::chrono::seconds(5)) == std::future_status::ready);
        check(engine.resetChecked());
        check(calls == 1); // the previous 6s partial must not delay this timeline
        check(parameters[0].audioContext == 224); // conservative short-window minimum
        auto bestDispatchUs = firstDecodeUs;
        // Several independent timelines tolerate an occasional host scheduling
        // stall while catching the previous unconditional 200ms polling floor.
        for (int attempt = 0; attempt < 4; ++attempt) {
            std::promise<void> ready;
            auto readyFuture = ready.get_future();
            plan({0}, 1, [&] { ready.set_value(); });
            const auto pushed = std::chrono::steady_clock::now();
            check(engine.pushAudioFloat(firstSecond.data(), firstSecond.size(), 16000) == 0);
            check(readyFuture.wait_for(std::chrono::seconds(5)) == std::future_status::ready);
            bestDispatchUs = std::min(bestDispatchUs,
                std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - pushed).count());
            check(engine.resetChecked());
        }
        check(bestDispatchUs < 150000);
        std::cout << "whisper input-to-worker dispatch: " << bestDispatchUs << " us best of 5 (stub inference)\n";
    }
    {
        stt::WhisperEngine engine;
        check(engine.initialize(model.config()));
        plan({-37});
        check(engine.pushAudioFloat(audio.data(), audio.size(), 16000) == 0);
        check(engine.finalize().empty());
        const auto failure = engine.getLastError();
        check(failure == "whisper_full (pass#1) failed: -37");
        check(engine.finalize().empty());
        check(calls == 1);
        check(engine.pushAudioFloat(audio.data(), audio.size(), 16000) < 0);
        partialFails(engine, failure);
        check(engine.resetChecked());
        plan({0});
        check(engine.pushAudioFloat(audio.data(), audio.size(), 16000) == 0);
        check(engine.finalize().find("recovered text") != std::string::npos);
    }
    {
        stt::WhisperEngine engine;
        check(engine.initialize(model.config()));
        plan({-43});
        // A full promote window triggers failure on the worker, not this thread.
        const std::vector<float> window(160000, 0.1f);
        check(engine.pushAudioFloat(window.data(), window.size(), 16000) == 0);
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        bool failed = false;
        while (std::chrono::steady_clock::now() < deadline) {
            try { engine.getPartial(); }
            catch (const std::runtime_error& e) {
                check(std::string(e.what()) == "whisper_full (pass#1) failed: -43"); failed = true; break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        check(failed);
        check(engine.pushAudioFloat(audio.data(), audio.size(), 16000) < 0);
        check(engine.finalize().empty());
        check(calls == 1);
        check(engine.resetChecked());
        plan({0});
        check(engine.pushAudioFloat(audio.data(), audio.size(), 16000) == 0);
        check(engine.finalize().find("recovered text") != std::string::npos);
    }
    {
        stt::WhisperEngine engine;
        check(engine.initialize(model.config()));
        plan({0}, 0);
        const std::vector<float> silence(320, 0);
        const auto result = engine.transcribeBatchFloat(silence.data(), silence.size(), 16000);
        stt::JsonValue parsed;
        std::string text;
        check(stt::JsonUtils::parse(result, parsed));
        check(parsed.find("text") && parsed.find("text")->asString(text) && text.empty());
        check(engine.getPartial().empty());
        // A cancellation return from whisper_full remains cancellation.
        plan({-51}, 1, [&engine] { engine.cancel(); });
        check(engine.transcribeBatchFloat(audio.data(), audio.size(), 16000).find("\"cancelled\": true") != std::string::npos);
        check(engine.resetChecked());
        check(engine.getPartial().empty());
    }
    {
        stt::WhisperEngine engine;
        check(engine.initialize(model.config()));
        std::promise<void> decoding, release;
        auto entered = decoding.get_future();
        const auto released = release.get_future().share();
        plan({0}, 1, [&] { decoding.set_value(); released.wait(); });
        const std::vector<float> frame(16000, 0.1f);
        check(engine.pushAudioFloat(frame.data(), frame.size(), 16000) == 0);
        check(engine.pushAudioFloat(frame.data(), frame.size(), 16000) == 0);
        check(entered.wait_for(std::chrono::seconds(5)) == std::future_status::ready);
        // Pending includes the window the worker is decoding. Hold inference
        // while all remaining admitted frames fill the existing 60 s cap.
        for (int i = 2; i < 60; ++i)
            check(engine.pushAudioFloat(frame.data(), frame.size(), 16000) == 0);
        const int16_t extra = 1;
        check(engine.pushAudio(&extra, 1, 16000) < 0);
        const auto failure = engine.getLastError();
        check(failure == "Whisper streaming cannot keep up: 960000 samples pending; queue limit 960000");
        check(engine.pushAudioFloat(audio.data(), audio.size(), 16000) < 0);
        partialFails(engine, failure);
        release.set_value();
        // A late successful decoder result cannot discard/promote the failed
        // timeline or turn its finalization back into a success.
        check(engine.finalize().empty());
        check(engine.getLastError() == failure);
        check(calls == 1);
        partialFails(engine, failure);
        const auto contexts = liveContexts.load();
        check(engine.resetChecked());
        check(liveContexts == contexts);
        plan({0});
        check(engine.pushAudioFloat(audio.data(), audio.size(), 16000) == 0);
        check(engine.finalize().find("recovered text") != std::string::npos);
    }
    check(liveContexts == 0);
    std::cout << "whisper_failure_test: " << checks << " checks PASS\n";
}
