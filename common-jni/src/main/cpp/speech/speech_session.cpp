#include "speech_session.h"
#include "backend_versions.h"
#include "expansion/versions.h"
#include "modern_cpu/versions.h"
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
    if (!JsonUtils::validateObjectKeys(j, {"backend", "acceleration", "gpuCacheDirectory", "model", "frontend", "encoder", "decoder", "tokenizer", "language", "numThreads", "rightContext", "maxUtteranceMs", "silenceMs", "silenceThresholdDb"}, &error)) throw std::invalid_argument(error);
    backend = stringField(j, "backend");
    acceleration = j.find("acceleration") ? stringField(j, "acceleration") : "cpu";
    if (acceleration != "cpu" && acceleration != "vulkan" && acceleration != "opencl")
        throw std::invalid_argument("Unsupported speech acceleration: " + acceleration);
    if (acceleration != "cpu" && backend != "transcribepsy" &&
        !(acceleration == "vulkan" && (backend == "cohere_asr" || backend == "funasr_mlt_nano" || backend == "voxtral_realtime")))
        throw std::invalid_argument("Unsupported speech backend acceleration");
    const char* names[] = {"model", "frontend", "encoder", "decoder", "tokenizer", "language"};
    for (size_t i = 0; i < 6; ++i) paths[i] = stringField(j, names[i]);
    paths[6] = stringField(j, "gpuCacheDirectory", false);
    value = {sizeof(value), paths[0].c_str(), paths[1].c_str(), paths[2].c_str(), paths[3].c_str(), paths[4].c_str(), paths[5].c_str(),
        intField(j, "numThreads", 1, 8), intField(j, "rightContext", 0, 13),
        intField(j, "maxUtteranceMs", 1000, 15000), intField(j, "silenceMs", 200, 2000), -45, acceleration.c_str(), paths[6].c_str()};
    if (value.max_utterance_ms % 20 || value.silence_ms % 20) throw std::invalid_argument("Speech segmentation must use 20ms frames");
    double db = 0; const auto* threshold = j.find("silenceThresholdDb");
    if (!threshold || !threshold->asDouble(db) || !std::isfinite(db) || db < -100 || db > -10) throw std::invalid_argument("Invalid speech silenceThresholdDb");
    value.silence_threshold_db = static_cast<float>(db);
    if (backend == "moonshine") {
        if (paths[0].empty() || paths[2].empty() || paths[3].empty())
            throw std::invalid_argument("Moonshine requires tokens, encoder and merged decoder paths");
        if (paths[5] != "auto" && paths[5] != "en" && paths[5] != "ar" && paths[5] != "es" &&
            paths[5] != "ja" && paths[5] != "ko" && paths[5] != "uk" && paths[5] != "vi" && paths[5] != "zh")
            throw std::invalid_argument("Unsupported Moonshine source declaration: " + paths[5]);
    } else if (backend == "qwen3_asr") {
        for (int i = 1; i <= 4; ++i) if (paths[i].empty()) throw std::invalid_argument("Qwen requires frontend, encoder, decoder and tokenizer paths");
        qwenLanguageName(paths[5]); // Validate before loading the backend.
    } else if (backend == "omnilingual_ctc") {
        if (paths[0].empty() || paths[4].empty()) throw std::invalid_argument("Omnilingual CTC requires ONNX model and tokens paths");
        if (paths[5] != "auto" && paths[5] != "en" && paths[5] != "ar" && paths[5] != "ru" && paths[5] != "zh")
            throw std::invalid_argument("Unsupported Omnilingual source declaration: " + paths[5]);
    } else if (backend == "phonon") {
        for (int i = 0; i <= 3; ++i) if (paths[i].empty())
            throw std::invalid_argument("Phonon requires tokens, joiner, encoder and decoder paths");
        const std::string supported = " auto en es fr de it pt nl pl ru uk ro cs sk bg hr sv da fi et lv lt hu sl mt el ";
        if (paths[5].find_first_of(" \t\r\n") != std::string::npos ||
            supported.find(" " + paths[5] + " ") == std::string::npos)
            throw std::invalid_argument("Unsupported Phonon source declaration: " + paths[5]);
    } else if (backend == "transcribepsy") {
        if (paths[0].empty()) throw std::invalid_argument("TranscribePsy requires a GGUF model path");
        if (paths[5] != "auto" && paths[5] != "en" && paths[5] != "nl" && paths[5] != "fr" &&
            paths[5] != "de" && paths[5] != "it" && paths[5] != "pl" && paths[5] != "pt" && paths[5] != "es")
            throw std::invalid_argument("Unsupported TranscribePsy source declaration: " + paths[5]);
    } else if (backend == "cohere_asr" || backend == "funasr_mlt_nano" || backend == "voxtral_realtime") {
        if (paths[0].empty()) throw std::invalid_argument("Native multilingual speech requires a GGUF model path");
        if (backend == "cohere_asr" && paths[5] != "ar" && paths[5] != "en")
            throw std::invalid_argument("Cohere Arabic requires ar or en; Auto is unavailable");
        if (backend == "voxtral_realtime") {
            const std::string supported = " auto ar en fr es de ru zh ja it pt nl hi ko ";
            if (supported.find(" " + paths[5] + " ") == std::string::npos) throw std::invalid_argument("Unsupported Voxtral source declaration");
        }
        if (backend == "funasr_mlt_nano") {
            const std::string supported = " auto ar en zh yue ja ko vi id th ms fil hi bg hr cs da nl et fi el hu ga lv lt mt pl pt ro sk sl sv ";
            if (supported.find(" " + paths[5] + " ") == std::string::npos) throw std::invalid_argument("Unsupported Fun-ASR MLT source language");
        }
    } else if (backend == "owsm" || backend == "audar_mtmd") {
        if (paths[0].empty() || (backend == "audar_mtmd" && paths[2].empty()))
            throw std::invalid_argument("OWSM/Audar requires its complete GGUF package");
        if (paths[5] != "auto" && paths[5] != "ar" && paths[5] != "en")
            throw std::invalid_argument("OWSM/Audar source language must be Auto, Arabic or English");
    } else if (backend == "nemotron_3_5") {
        if (paths[0].empty() || paths[5].empty()) throw std::invalid_argument("Nemotron requires a model path and source language");
        const int r = value.right_context;
        if (r != 0 && r != 1 && r != 3 && r != 6 && r != 13) throw std::invalid_argument("Nemotron right context must be 0, 1, 3, 6 or 13");
    } else throw std::invalid_argument("Unknown speech backend: " + backend);
}

const HearthSpeechBackend& loadBackend(const std::string& id, const std::string& acceleration) {
    if (acceleration != "cpu" && acceleration != "vulkan" && acceleration != "opencl")
        throw std::invalid_argument("Unsupported speech acceleration: " + acceleration);
    if (acceleration != "cpu" && id != "transcribepsy" &&
        !(acceleration == "vulkan" && (id == "cohere_asr" || id == "funasr_mlt_nano" || id == "voxtral_realtime")))
        throw std::invalid_argument("Unsupported speech backend acceleration");
    std::string name, revision;
    if (id == "qwen3_asr" || id == "moonshine" || id == "omnilingual_ctc" || id == "phonon") { name = "libhearth_qwen"; revision = HEARTH_QWEN_REVISION; }
    else if (id == "transcribepsy") { name = "libhearth_transcribepsy"; revision = HEARTH_TRANSCRIBEPSY_REVISION; }
    else if (id == "cohere_asr" || id == "funasr_mlt_nano" || id == "voxtral_realtime") { name = "libhearth_transcribe_cpp"; revision = HEARTH_TRANSCRIBE_CPP_REVISION; }
    else if (id == "owsm") { name = "libhearth_owsm"; revision = HEARTH_OWSM_REVISION; }
    else if (id == "audar_mtmd") { name = "libhearth_audar"; revision = HEARTH_AUDAR_REVISION; }
    else if (id == "nemotron_3_5") { name = "libhearth_nemotron"; revision = HEARTH_NEMO_REVISION; }
    else throw std::invalid_argument("Unknown speech backend: " + id);
    if (acceleration != "cpu") name += "_" + acceleration;
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
    const auto key = id + ":" + acceleration;
    if (auto it = loaded.find(key); it != loaded.end()) return *it->second;
    void* library = dlopen(name.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!library) { const char* e = dlerror(); throw std::runtime_error(e ? e : "dlopen failed without an error string"); }
    const char* symbol = id == "moonshine" ? "hearth_moonshine_backend_v1" :
        id == "omnilingual_ctc" ? "hearth_omnilingual_backend_v1" :
        id == "phonon" ? "hearth_phonon_backend_v1" :
        id == "cohere_asr" ? "hearth_cohere_backend_v1" :
        id == "funasr_mlt_nano" ? "hearth_funasr_backend_v1" :
        id == "owsm" ? "hearth_owsm_backend_v1" :
        id == "audar_mtmd" ? "hearth_audar_backend_v1" :
        id == "voxtral_realtime" ? "hearth_voxtral_backend_v1" : "hearth_speech_backend_v1";
    auto entry = reinterpret_cast<HearthSpeechEntry>(dlsym(library, symbol));
    const auto* api = entry ? entry() : nullptr;
    if (!api || api->abi_version != HEARTH_SPEECH_ABI_VERSION || api->size != sizeof(HearthSpeechBackend) ||
        !api->id || id != api->id || !api->revision || revision != api->revision || !api->create || !api->destroy ||
        !api->push || !api->next || !api->finish || !api->reset || !api->last_error || !api->execution_info) {
        dlclose(library); throw std::runtime_error("Speech backend ABI/revision mismatch: " + id);
    }
    loaded.emplace(key, api);
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
std::string SpeechNativeSession::executionInfo() const {
    const char* info = api_.execution_info(instance_);
    if (!info || !*info || std::strlen(info) > 4096) throw std::runtime_error("Speech backend returned invalid execution information");
    return info;
}
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
