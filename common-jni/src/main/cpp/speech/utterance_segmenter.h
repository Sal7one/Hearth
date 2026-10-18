#pragma once
#include "../common/audio_gate.h"
#include <array>
#include <cstring>
#include <functional>
#include <stdexcept>
#include <vector>

namespace stt::speech {
// Energy endpointing, not a neural VAD. Always analyze 20ms frames so decisions
// do not depend on the recorder's read size. Keep 200ms pre-roll for consonants.
// Bounds one utterance; continuous speech splits without replaying prior audio.
// Keep 200ms after the last active frame for decoder context; the remaining
// endpoint silence advances the source clock without wasting encoder work.
class UtteranceSegmenter {
public:
    using Consumer = std::function<void(const std::vector<float>&, int64_t)>;
    UtteranceSegmenter(int maximumMs, int silenceMs, float thresholdDb)
        : gate_(configuration(thresholdDb)) {
        if (maximumMs < 1000 || maximumMs > 15000 || silenceMs < 200 || silenceMs > 2000)
            throw std::invalid_argument("Invalid utterance segmentation limits");
        maximum_ = static_cast<size_t>(maximumMs) * 16;
        silence_ = static_cast<size_t>(silenceMs) * 16;
        utterance_.reserve(maximum_);
    }
    void push(const float* samples, size_t count, const Consumer& emit) {
        if (!samples && count) throw std::invalid_argument("Missing utterance audio");
        while (count) {
            // Recorder reads often contain whole 20ms frames. Analyze those
            // directly instead of pushing every sample through a vector.
            if (frameCount_ == 0 && count >= frame_.size()) {
                consumeFrame(samples, frame_.size(), emit);
                samples += frame_.size(); count -= frame_.size();
            } else {
                const size_t take = std::min(count, frame_.size() - frameCount_);
                std::memcpy(frame_.data() + frameCount_, samples, take * sizeof(float));
                frameCount_ += take; samples += take; count -= take;
                if (frameCount_ == frame_.size()) {
                    consumeFrame(frame_.data(), frameCount_, emit);
                    frameCount_ = 0;
                }
            }
        }
    }
    void finish(const Consumer& emit) {
        if (frameCount_) {
            consumeFrame(frame_.data(), frameCount_, emit);
            frameCount_ = 0;
        }
        flush(emit);
        preRollCount_ = preRollWrite_ = 0;
    }
    void reset() { frameCount_ = preRollCount_ = preRollWrite_ = 0; utterance_.clear(); active_ = false; quiet_ = 0; position_ = 0; gate_.reset(); }
private:
    static AudioGateConfig configuration(float db) {
        if (!std::isfinite(db) || db < -100 || db > -10) throw std::invalid_argument("Invalid silence threshold");
        AudioGateConfig c;
        c.rmsThresholdDb = db; c.minActiveSamples = 320; c.minSilenceSamples = 320;
        c.smoothingAlpha = 1; c.attackAlpha = 1; c.zeroCrossingMax = 1;
        return c;
    }
    void consumeFrame(const float* samples, size_t count, const Consumer& emit) {
        position_ += count;
        const bool speech = gate_.analyzeFloat(samples, count).isActive;
        if (speech && !active_) {
            active_ = true;
            // Read the fixed pre-roll ring in time order. Keep the utterance
            // allocation: swapping vectors used to replace the max-window
            // reservation with a small pre-roll allocation at every onset.
            const size_t start = (preRollWrite_ + preRoll_.size() - preRollCount_) % preRoll_.size();
            const size_t first = std::min(preRollCount_, preRoll_.size() - start);
            utterance_.insert(utterance_.end(), preRoll_.data() + start, preRoll_.data() + start + first);
            utterance_.insert(utterance_.end(), preRoll_.data(), preRoll_.data() + preRollCount_ - first);
            preRollCount_ = preRollWrite_ = 0;
        }
        if (active_) {
            utterance_.insert(utterance_.end(), samples, samples + count);
            quiet_ = speech ? 0 : quiet_ + count;
            if (utterance_.size() >= maximum_ || quiet_ >= silence_) flush(emit);
        } else {
            const size_t first = std::min(count, preRoll_.size() - preRollWrite_);
            std::memcpy(preRoll_.data() + preRollWrite_, samples, first * sizeof(float));
            if (count > first) std::memcpy(preRoll_.data(), samples + first, (count - first) * sizeof(float));
            preRollWrite_ = (preRollWrite_ + count) % preRoll_.size();
            preRollCount_ = std::min(preRoll_.size(), preRollCount_ + count);
        }
    }
    void flush(const Consumer& emit) {
        if (quiet_ > kContextSamples) utterance_.resize(utterance_.size() - (quiet_ - kContextSamples));
        if (!utterance_.empty()) emit(utterance_, position_);
        utterance_.clear(); active_ = false; quiet_ = 0;
    }
    size_t maximum_, silence_, quiet_ = 0;
    int64_t position_ = 0;
    bool active_ = false;
    AudioGate gate_;
    static constexpr size_t kContextSamples = 3200;
    std::array<float, 320> frame_{};
    std::array<float, kContextSamples> preRoll_{};
    size_t frameCount_ = 0, preRollCount_ = 0, preRollWrite_ = 0;
    std::vector<float> utterance_;
};
}
