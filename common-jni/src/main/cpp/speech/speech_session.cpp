#include "speech_session.h"
#include "backend_versions.h"
#include "qwen_language.h"
#include <cmath>
#include <cstring>
#include <dlfcn.h>
#include <map>
#include <limits>

namespace stt::speech {
namespace {
std::string stringField(const JsonValue& j, const char* key, bool required = true) {
    std::string s;
    const auto* v = j.find(key);
    if ((!v || !v->asString(s)) && required) throw std::invalid_argument(std::string("Missing speech field: ") + key);
    if (s.find('\0') != std::string::npos || s.size() > 4096) throw std::invalid_argument(std::string("Invalid speech field: ") + key);
    return s;
}
int intField(const JsonValue& j, const char* key, int lo, int hi) {
    int64_t n = 0; const auto* v = j.find(key);
    if (!v || !v->asInt64(n) || n < lo || n > hi) throw std::invalid_argument(std::string("Invalid speech field: ") + key);
    return static_cast<int>(n);
}
}
SpeechConfig::SpeechConfig(const std::string& json) {
    JsonValue j; std::string error;
    if (!JsonUtils::parse(json, j, &error) || !j.isObject()) throw std::invalid_argument("Speech config: " + error);
    if (!JsonUtils::validateObjectKeys(j, {"backend", "model", "frontend", "encoder", "decoder", "tokenizer", "language", "numThreads", "rightContext", "maxUtteranceMs", "silenceMs", "silenceThresholdDb"}, &error)) throw std::invalid_argument(error);
    backend = stringField(j, "backend");
    const char* names[] = {"model", "frontend", "encoder", "decoder", "tokenizer", "language"};
    for (size_t i = 0; i < 6; ++i) paths[i] = stringField(j, names[i]);
    value = {sizeof(value), paths[0].c_str(), paths[1].c_str(), paths[2].c_str(), paths[3].c_str(), paths[4].c_str(), paths[5].c_str(),
        intField(j, "numThreads", 1, 8), intField(j, "rightContext", 0, 13),
        intField(j, "maxUtteranceMs", 1000, 15000), intField(j, "silenceMs", 200, 2000), -45};
    if (value.max_utterance_ms % 20 || value.silence_ms % 20) throw std::invalid_argument("Speech segmentation must use 20ms frames");
    double db = 0; const auto* threshold = j.find("silenceThresholdDb");
    if (!threshold || !threshold->asDouble(db) || !std::isfinite(db) || db < -100 || db > -10) throw std::invalid_argument("Invalid speech silenceThresholdDb");
    value.silence_threshold_db = static_cast<float>(db);
    if (backend == "moonshine") {
        if (paths[0].empty() || paths[2].empty() || paths[3].empty() || (paths[5] != "auto" && paths[5] != "en"))
            throw std::invalid_argument("Moonshine requires tokens, encoder, merged decoder and English source");
    } else if (backend == "qwen3_asr") {
        for (int i = 1; i <= 4; ++i) if (paths[i].empty()) throw std::invalid_argument("Qwen requires frontend, encoder, decoder and tokenizer paths");
        qwenLanguageName(paths[5]); // Validate before loading the backend.
    } else if (backend == "omnilingual_ctc") {
        if (paths[0].empty() || paths[4].empty()) throw std::invalid_argument("Omnilingual CTC requires ONNX model and tokens paths");
        if (paths[5] != "auto" && paths[5] != "en" && paths[5] != "ar" && paths[5] != "ru" && paths[5] != "zh")
            throw std::invalid_argument("Unsupported Omnilingual source declaration: " + paths[5]);
    } else if (backend == "nemotron_3_5") {
        if (paths[0].empty() || paths[5].empty()) throw std::invalid_argument("Nemotron requires a model path and source language");
        const int r = value.right_context;
        if (r != 0 && r != 1 && r != 3 && r != 6 && r != 13) throw std::invalid_argument("Nemotron right context must be 0, 1, 3, 6 or 13");
    } else throw std::invalid_argument("Unknown speech backend: " + backend);
}

const HearthSpeechBackend& loadBackend(const std::string& id) {
    std::string name, revision;
    if (id == "qwen3_asr" || id == "moonshine" || id == "omnilingual_ctc") { name = "libhearth_qwen"; revision = HEARTH_QWEN_REVISION; }
    else if (id == "nemotron_3_5") { name = "libhearth_nemotron"; revision = HEARTH_NEMO_REVISION; }
    else throw std::invalid_argument("Unknown speech backend: " + id);
#ifdef __APPLE__
    name += ".dylib";
#else
    name += ".so";
#endif
    static std::mutex gate;
    // Like System.loadLibrary: successful runtimes stay loaded for process life.
    // Avoid unloading ORT/GGML while their thread-local destructors still exist.
    static std::map<std::string, const HearthSpeechBackend*> loaded;
    std::lock_guard<std::mutex> lock(gate);
    if (auto it = loaded.find(id); it != loaded.end()) return *it->second;
    void* library = dlopen(name.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!library) { const char* e = dlerror(); throw std::runtime_error(e ? e : "dlopen failed without an error string"); }
    const char* symbol = id == "moonshine" ? "hearth_moonshine_backend_v1" :
        id == "omnilingual_ctc" ? "hearth_omnilingual_backend_v1" : "hearth_speech_backend_v1";
    auto entry = reinterpret_cast<HearthSpeechEntry>(dlsym(library, symbol));
    const auto* api = entry ? entry() : nullptr;
    if (!api || api->abi_version != HEARTH_SPEECH_ABI_VERSION || api->size != sizeof(HearthSpeechBackend) ||
        !api->id || id != api->id || !api->revision || revision != api->revision || !api->create || !api->destroy ||
        !api->push || !api->next || !api->finish || !api->reset || !api->last_error) {
        dlclose(library); throw std::runtime_error("Speech backend ABI/revision mismatch: " + id);
    }
    loaded.emplace(id, api);
    return *api;
}
SpeechNativeSession::SpeechNativeSession(const HearthSpeechBackend& api, const HearthSpeechConfig& config) : api_(api) {
    const int code = api_.create(&config, &instance_);
    if (code || !instance_) {
        const std::string error = api_.last_error();
        if (instance_) api_.destroy(instance_);
        instance_ = nullptr;
        throw std::runtime_error(error.empty() ? "Speech backend create returned no instance" : error);
    }
}
SpeechNativeSession::~SpeechNativeSession() { if (instance_) api_.destroy(instance_); }
void SpeechNativeSession::check(int code) {
    if (code) {
        failure_ = api_.last_error();
        if (failure_.empty()) failure_ = "Speech backend returned status " + std::to_string(code);
        throw std::runtime_error(failure_);
    }
}
std::string SpeechNativeSession::push(const float* samples, size_t count, int64_t offset) {
    if (!failure_.empty()) throw std::runtime_error(failure_);
    if (finished_) throw std::logic_error("Speech input is already finished");
    if (offset != accepted_) throw std::invalid_argument("Speech audio discontinuity: expected sample " + std::to_string(accepted_) + ", received " + std::to_string(offset));
    if (!samples || count == 0 || count > 16000) throw std::invalid_argument("Speech push requires 1..16000 mono samples");
    if (accepted_ > std::numeric_limits<int64_t>::max() - static_cast<int64_t>(count)) throw std::overflow_error("Speech sample counter overflow");
    for (size_t i = 0; i < count; ++i) if (!std::isfinite(samples[i]) || std::abs(samples[i]) > 1) throw std::invalid_argument("Speech samples must be finite and normalized");
    check(api_.push(instance_, samples, count));
    accepted_ += static_cast<int64_t>(count);
    return drain();
}
std::string SpeechNativeSession::finish() {
    if (!failure_.empty()) throw std::runtime_error(failure_);
    if (finished_) throw std::logic_error("Speech input is already finished");
    check(api_.finish(instance_)); finished_ = true; return drain();
}
void SpeechNativeSession::reset() {
    // Reset is an explicit discontinuity and may recover a failed request.
    check(api_.reset(instance_)); accepted_ = 0; ++utterance_; revision_ = 0; finished_ = false; failure_.clear(); lastPartialText_.clear(); lastPartialLanguage_.clear();
}
std::string SpeechNativeSession::drain() {
    JsonValue::Array events;
    for (int i = 0; i <= 64; ++i) {
        HearthSpeechResult result{}; result.size = sizeof(result); int available = 0;
        check(api_.next(instance_, &result, &available));
        if (!available) return JsonUtils::stringify(JsonValue::object({{"events", JsonValue::array(std::move(events))}, {"acceptedSamples", accepted_}}));
        if (i == 64 || result.size != sizeof(result) || !result.text || !result.language || result.audio_end_samples < 0 || result.audio_end_samples > accepted_ + 16000) {
            failure_ = "Invalid or unbounded result from speech backend"; throw std::runtime_error(failure_);
        }
        if (std::strlen(result.text) > 32768 || std::strlen(result.language) > 128) { failure_ = "Speech backend transcript exceeds limit"; throw std::runtime_error(failure_); }
        if (!result.is_final && lastPartialText_ == result.text && lastPartialLanguage_ == result.language) continue;
        lastPartialText_ = result.is_final ? "" : result.text;
        lastPartialLanguage_ = result.is_final ? "" : result.language;
        events.push_back(JsonValue::object({{"text", result.text}, {"language", result.language},
            {"final", result.is_final != 0}, {"utteranceId", utterance_}, {"revision", ++revision_},
            {"audioEndSamples", result.audio_end_samples}}));
        if (result.is_final) { ++utterance_; revision_ = 0; }
    }
    throw std::logic_error("Unreachable speech drain state");
}
}
