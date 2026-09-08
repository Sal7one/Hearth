#pragma once
#include "backend_abi.h"
#include "../common/json_utils.h"
#include <array>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace stt::speech {
struct SpeechConfig {
    std::string backend;
    std::array<std::string, 7> paths; // model, frontend, encoder, decoder, tokenizer, language, reserved
    HearthSpeechConfig value{};
    explicit SpeechConfig(const std::string& json);
    SpeechConfig(const SpeechConfig&) = delete;
    SpeechConfig& operator=(const SpeechConfig&) = delete;
};
const HearthSpeechBackend& loadBackend(const std::string& id);

class SpeechNativeSession {
public:
    SpeechNativeSession(const HearthSpeechBackend& api, const HearthSpeechConfig& config);
    ~SpeechNativeSession();
    std::string push(const float* samples, size_t count, int64_t offset);
    std::string finish();
    void reset();
    std::mutex mutex; // JNI calls hold both a lifetime lease and this operation lock.
private:
    void check(int code);
    std::string drain();
    const HearthSpeechBackend& api_;
    void* instance_ = nullptr;
    bool finished_ = false;
    std::string failure_, lastPartialText_, lastPartialLanguage_;
    int64_t accepted_ = 0, utterance_ = 1, revision_ = 0;
};
}
