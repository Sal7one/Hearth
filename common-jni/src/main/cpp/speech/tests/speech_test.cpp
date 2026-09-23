#include "../speech_session.h"
#include "../qwen_language.h"
#include "../endpoint_budget.h"
#include "../utterance_segmenter.h"
#include <cassert>
#include <cstring>
#include <iostream>
#include <limits>

using namespace stt::speech;
namespace {
int checks = 0, destroyed = 0;
void check(bool ok) { ++checks; if (!ok) std::cerr << "Failed check " << checks << "\n"; assert(ok); }
template<class F> void fails(F f, const std::string& substring) {
    try { f(); check(false); } catch (const std::exception& e) { check(std::string(e.what()).find(substring) != std::string::npos); }
}
struct Fake { int pending = 0; int64_t offset = 0; bool fail = false; };
int create(const HearthSpeechConfig*, void** out) { *out = new Fake; return 0; }
void destroy(void* p) { ++destroyed; delete static_cast<Fake*>(p); }
int push(void* p, const float*, size_t n) { auto& f = *static_cast<Fake*>(p); f.offset += n; f.pending = 2; return 0; }
int next(void* p, HearthSpeechResult* out, int* available) {
    auto& f = *static_cast<Fake*>(p); *available = f.pending > 0;
    if (*available) { --f.pending; *out = {sizeof(*out), "重复 مرحبا", "zh-CN", f.pending == 0, f.offset}; }
    return 0;
}
int finish(void* p) { static_cast<Fake*>(p)->pending = 1; return 0; }
int reset(void* p) { *static_cast<Fake*>(p) = {}; return 0; }
const char* error() { return "原因: invalid graph 🧪"; }
HearthSpeechBackend api{1, sizeof(HearthSpeechBackend), "fake", "test", create, destroy, push, next, finish, reset, error};
using Segment = std::pair<std::vector<float>, int64_t>;
std::vector<Segment> segmented(const std::vector<float>& audio, size_t chunk) {
    UtteranceSegmenter s(1000, 200, -45);
    std::vector<Segment> out;
    auto emit = [&](const std::vector<float>& a, int64_t e) { out.emplace_back(a, e); };
    for (size_t i = 0; i < audio.size(); i += chunk) s.push(audio.data() + i, std::min(chunk, audio.size() - i), emit);
    s.finish(emit); s.finish(emit); return out;
}
}
int main() {
    EndpointBudget endpoint(4000);
    check(!endpoint.observe(false, false, 160000)); // silence never finalizes
    check(!endpoint.observe(true, false, 160000));
    check(!endpoint.observe(true, false, 223999));
    check(endpoint.observe(true, false, 224000)); // continuous Russian needs no pause
    check(!endpoint.observe(true, true, 224000));
    check(!endpoint.observe(true, false, 500000)); // real final begins a new budget
    endpoint.reset();
    check(!endpoint.observe(true, false, 0));
    check(qwenLanguageName("auto").empty());
    check(qwenLanguageName("ru") == "Russian");
    check(qwenLanguageName("zh") == "Chinese");
    check(qwenLanguageName("ar") == "Arabic");
    check(qwenLanguageName("yue") == "Cantonese");
    check(qwenLanguageName("fil") == "Filipino");
    fails([] { qwenLanguageName("ur"); }, "Unsupported Qwen source language");

    std::vector<float> audio(48000, 0.1f);
    std::fill(audio.begin(), audio.begin() + 4000, 0);
    std::fill(audio.begin() + 22000, audio.begin() + 26000, 0);
    auto reference = segmented(audio, 320);
    for (size_t chunk : {size_t(1), size_t(173), size_t(800), size_t(16000)}) check(segmented(audio, chunk) == reference);
    for (auto& [a, end] : reference) { check(a.size() <= 16000); check(end <= 48000); }
    check(segmented(std::vector<float>(32000, 0), 173).empty());
    const auto continuous = segmented(std::vector<float>(48000, 0.1f), 173);
    check(continuous.size() == 3);
    check(continuous[0].second == 16000 && continuous[2].second == 48000);
    check(continuous[0].first.size() + continuous[1].first.size() + continuous[2].first.size() == 48000);
    fails([] { UtteranceSegmenter s(0, 200, -45); }, "limits");
    fails([] { UtteranceSegmenter s(1000, 200, std::numeric_limits<float>::quiet_NaN()); }, "threshold");
    HearthSpeechConfig config{}; config.size = sizeof(config);
    {
        SpeechNativeSession session(api, config);
        float samples[320]{};
        auto a = session.push(samples, 320, 0);
        stt::JsonValue parsed; std::string decoded;
        check(stt::JsonUtils::parse(a, parsed));
        parsed.find("events")->arrayItems()[0].find("text")->asString(decoded);
        check(decoded == "重复 مرحبا");
        check(a.find("\"final\":true") != std::string::npos);
        check(a.find("\"utteranceId\":1") != std::string::npos);
        check(a.find("\"revision\":2") != std::string::npos);
        fails([&] { session.push(samples, 320, 0); }, "discontinuity");
        auto b = session.push(samples, 320, 320);
        check(b.find("\"utteranceId\":2") != std::string::npos);
        check(b.find("\"acceptedSamples\":640") != std::string::npos);
        session.finish();
        fails([&] { session.push(samples, 320, 640); }, "finished");
        fails([&] { session.finish(); }, "finished");
        session.reset();
        check(session.push(samples, 320, 0).find("\"acceptedSamples\":320") != std::string::npos);
        fails([&] { session.push(nullptr, 0, 320); }, "1..16000");
    }
    check(destroyed == 1);
    auto broken = api;
    broken.push = [](void*, const float*, size_t) { return 9; };
    {
        SpeechNativeSession session(broken, config); float s = 0;
        fails([&] { session.push(&s, 1, 0); }, "原因: invalid graph 🧪");
        fails([&] { session.finish(); }, "原因: invalid graph 🧪");
    }
    check(destroyed == 2);
    fails([] { loadBackend("not-a-backend"); }, "Unknown speech backend");
    fails([] { SpeechConfig c("{\"backend\":\"qwen3_asr\",\"backend\":\"nemotron_3_5\"}"); }, "Speech config");
    const std::string omni = R"({"backend":"omnilingual_ctc","model":"model.int8.onnx","frontend":"","encoder":"","decoder":"","tokenizer":"tokens.txt","language":"ar","numThreads":2,"rightContext":0,"maxUtteranceMs":4000,"silenceMs":400,"silenceThresholdDb":-45})";
    check(SpeechConfig(omni).backend == "omnilingual_ctc");
    auto missingTokens = omni;
    missingTokens.replace(missingTokens.find("tokens.txt"), std::strlen("tokens.txt"), "");
    fails([&] { SpeechConfig c(missingTokens); }, "requires ONNX model and tokens");
    auto unsupportedSource = omni;
    unsupportedSource.replace(unsupportedSource.find("\"ar\""), 4, "\"he\"");
    fails([&] { SpeechConfig c(unsupportedSource); }, "Unsupported Omnilingual source declaration");
    std::cout << "speech_test: " << checks << " checks PASS\n";
}
