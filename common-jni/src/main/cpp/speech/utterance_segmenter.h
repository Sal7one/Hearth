#pragma once
#include "../common/audio_gate.h"
#include <deque>
#include <functional>
#include <stdexcept>
#include <vector>

namespace stt::speech {
// Energy endpointing, not a neural VAD. Always analyze 20ms frames so decisions
// do not depend on the recorder's read size. Keep 200ms pre-roll for consonants.
// Bounds one utterance; continuous speech splits without replaying prior audio.
class UtteranceSegmenter {
public:
    using Consumer = std::function<void(const std::vector<float>&, int64_t)>;
    UtteranceSegmenter(int maximumMs, int silenceMs, float thresholdDb)
        : maximum_(maximumMs * 16), silence_(silenceMs * 16), gate_(configuration(thresholdDb)) {
        if (maximumMs < 1000 || maximumMs > 15000 || silenceMs < 200 || silenceMs > 2000)
            throw std::invalid_argument("Invalid utterance segmentation limits");
        frame_.reserve(320); utterance_.reserve(maximum_); preRoll_.reserve(3200);
    }
    void push(const float* samples, size_t count, const Consumer& emit) {
        for (size_t i = 0; i < count; ++i) {
            frame_.push_back(samples[i]);
            if (frame_.size() == 320) consumeFrame(emit);
        }
    }
    void finish(const Consumer& emit) {
        if (!frame_.empty()) consumeFrame(emit);
        flush(emit);
        preRoll_.clear();
    }
    void reset() { frame_.clear(); utterance_.clear(); preRoll_.clear(); active_ = false; quiet_ = 0; position_ = 0; gate_.reset(); }
private:
    static AudioGateConfig configuration(float db) {
        if (!std::isfinite(db) || db < -100 || db > -10) throw std::invalid_argument("Invalid silence threshold");
        AudioGateConfig c;
        c.rmsThresholdDb = db; c.minActiveSamples = 320; c.minSilenceSamples = 320;
        c.smoothingAlpha = 1; c.attackAlpha = 1; c.zeroCrossingMax = 1;
        return c;
    }
    void consumeFrame(const Consumer& emit) {
        position_ += frame_.size();
        const bool speech = gate_.analyzeFloat(frame_.data(), frame_.size()).isActive;
        if (speech && !active_) { active_ = true; utterance_.assign(preRoll_.begin(), preRoll_.end()); preRoll_.clear(); }
        if (active_) {
            utterance_.insert(utterance_.end(), frame_.begin(), frame_.end());
            quiet_ = speech ? 0 : quiet_ + frame_.size();
            if (utterance_.size() >= maximum_ || quiet_ >= silence_) flush(emit);
        } else {
            preRoll_.insert(preRoll_.end(), frame_.begin(), frame_.end());
            if (preRoll_.size() > 3200) preRoll_.erase(preRoll_.begin(), preRoll_.end() - 3200);
        }
        frame_.clear();
    }
    void flush(const Consumer& emit) {
        if (!utterance_.empty()) emit(utterance_, position_);
        utterance_.clear(); active_ = false; quiet_ = 0;
    }
    size_t maximum_, silence_, quiet_ = 0;
    int64_t position_ = 0;
    bool active_ = false;
    AudioGate gate_;
    std::vector<float> frame_, utterance_, preRoll_;
};
}
