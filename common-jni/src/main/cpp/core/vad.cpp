#include "vad.h"
#include "logging.h"
#include <cmath>
#include <algorithm>
#include <limits>
#include <stdexcept>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#define VAD_USE_NEON 1
#else
#define VAD_USE_NEON 0
#endif

namespace stt {

// ============================================================================
// VadDetector (Energy-based)
// ============================================================================

VadDetector::VadDetector() : VadDetector(Config{}) {}

VadDetector::VadDetector(const Config& config) : config_(config) {
    std::string error;
    if (!validateConfig(config_, &error)) {
        throw std::invalid_argument(error);
    }
    const std::int64_t windowProduct =
        static_cast<std::int64_t>(config_.windowMs) * config_.sampleRate;
    windowSamples_ = static_cast<int>(windowProduct / 1000);

    // Pre-allocate energy history for smoothing (100ms worth)
    const int historySize = 100 / config_.windowMs;
    energyHistory_.resize(
        static_cast<std::size_t>(std::max(historySize, 5)),
        -100.0f
    );
    energySum_ = -100.0f * static_cast<float>(energyHistory_.size());
}

VadDetector::~VadDetector() = default;

bool VadDetector::validateConfig(const Config& config, std::string* error) {
    auto reject = [error](const char* message) {
        if (error) *error = message;
        return false;
    };
    if (!std::isfinite(config.speechThresholdDb) ||
        config.speechThresholdDb < -160.0F || config.speechThresholdDb > 0.0F) {
        return reject("speechThresholdDb must be finite and in [-160,0]");
    }
    if (!std::isfinite(config.silenceThresholdDb) ||
        config.silenceThresholdDb < -160.0F || config.silenceThresholdDb > 0.0F) {
        return reject("silenceThresholdDb must be finite and in [-160,0]");
    }
    if (config.speechThresholdDb <= config.silenceThresholdDb) {
        return reject("speechThresholdDb must be greater than silenceThresholdDb");
    }
    if (config.minSpeechMs < 0 || config.minSpeechMs > 3'600'000) {
        return reject("minSpeechMs must be in [0,3600000]");
    }
    if (config.minSilenceMs < 0 || config.minSilenceMs > 3'600'000) {
        return reject("minSilenceMs must be in [0,3600000]");
    }
    if (config.windowMs <= 0 || config.windowMs > 10'000) {
        return reject("windowMs must be in [1,10000]");
    }
    if (config.sampleRate <= 0 || config.sampleRate > 384'000) {
        return reject("sampleRate must be in [1,384000]");
    }
    const std::int64_t windowProduct =
        static_cast<std::int64_t>(config.windowMs) * config.sampleRate;
    const std::int64_t windowSamples = windowProduct / 1000;
    if (windowSamples < 1 ||
        windowSamples > std::numeric_limits<int>::max()) {
        return reject("windowMs and sampleRate must produce a positive window size");
    }
    if (error) error->clear();
    return true;
}

// NEON-accelerated mean-square for float samples.
float VadDetector::calculateEnergy(const float* samples, int count) {
    if (count <= 0) return 0.0f;
#if VAD_USE_NEON
    float32x4_t acc = vdupq_n_f32(0.0f);
    int i = 0;
    for (; i + 4 <= count; i += 4) {
        float32x4_t v = vld1q_f32(samples + i);
        acc = vmlaq_f32(acc, v, v);
    }
#if defined(__aarch64__)
    float sum = vaddvq_f32(acc);
#else
    float32x2_t pair = vadd_f32(vget_low_f32(acc), vget_high_f32(acc));
    pair = vpadd_f32(pair, pair);
    float sum = vget_lane_f32(pair, 0);
#endif
    for (; i < count; ++i) sum += samples[i] * samples[i];
    return sum / static_cast<float>(count);
#else
    float sum = 0.0f;
    for (int i = 0; i < count; ++i) sum += samples[i] * samples[i];
    return sum / static_cast<float>(count);
#endif
}

// NEON-accelerated mean-square for int16 samples, avoiding any float copy.
// Returns normalised value equivalent to converting int16 -> float/32768.
float VadDetector::calculateEnergyI16(const int16_t* samples, int count) {
    if (count <= 0) return 0.0f;
#if VAD_USE_NEON
    int64x2_t acc = vdupq_n_s64(0);
    int i = 0;
    for (; i + 8 <= count; i += 8) {
        int16x8_t v = vld1q_s16(samples + i);
        int16x4_t lo = vget_low_s16(v);
        int16x4_t hi = vget_high_s16(v);
        int32x4_t sqLo = vmull_s16(lo, lo);
        int32x4_t sqHi = vmull_s16(hi, hi);
        acc = vaddq_s64(acc, vpaddlq_s32(sqLo));
        acc = vaddq_s64(acc, vpaddlq_s32(sqHi));
    }
    int64_t sum = vgetq_lane_s64(acc, 0) + vgetq_lane_s64(acc, 1);
    for (; i < count; ++i) {
        int32_t s = samples[i];
        sum += s * s;
    }
#else
    int64_t sum = 0;
    for (int i = 0; i < count; ++i) {
        int32_t s = samples[i];
        sum += s * s;
    }
#endif
    // 32768^2 = 1073741824.
    const double mean = static_cast<double>(sum) / static_cast<double>(count);
    return static_cast<float>(mean / 1073741824.0);
}

float VadDetector::dbFromEnergy(float energy) {
    if (energy < 1e-10f) return -100.0f;
    return 10.0f * std::log10(energy);
}

void VadDetector::processWindow(float energyDb, int64_t windowStartMs) {
    // Incremental running sum: O(1) per window instead of O(N).
    const size_t H = energyHistory_.size();
    float& slot = energyHistory_[energyHistoryPos_];
    energySum_ += energyDb - slot;
    slot = energyDb;
    energyHistoryPos_ = (energyHistoryPos_ + 1) % H;
    if (!historyPrimed_ && energyHistoryPos_ == 0) historyPrimed_ = true;

    const float smoothedDb = energySum_ / static_cast<float>(H);
    currentEnergyDb_ = smoothedDb;

    const bool isSpeechNow  = smoothedDb > config_.speechThresholdDb;
    const bool isSilenceNow = smoothedDb < config_.silenceThresholdDb;

    if (!inSpeech_ && isSpeechNow) {
        inSpeech_ = true;
        speechStartMs_ = windowStartMs;
        silenceStartMs_ = 0;
    } else if (inSpeech_) {
        if (isSilenceNow) {
            if (silenceStartMs_ == 0) silenceStartMs_ = windowStartMs;
            int64_t silenceDuration = windowStartMs - silenceStartMs_;
            if (silenceDuration >= config_.minSilenceMs) {
                int64_t speechDuration = silenceStartMs_ - speechStartMs_;
                if (speechDuration >= config_.minSpeechMs) {
                    VadSegment seg;
                    seg.startMs = speechStartMs_;
                    seg.endMs = silenceStartMs_;
                    seg.confidence = 0.9f;
                    seg.isSpeech = true;
                    segments_.push_back(seg);
                    if (callback_) callback_(seg);
                }
                inSpeech_ = false;
                silenceStartMs_ = 0;
            }
        } else {
            silenceStartMs_ = 0;
        }
    }
}

void VadDetector::processWindowI16(const int16_t* s, int n) {
    const float energy   = calculateEnergyI16(s, n);
    const float energyDb = dbFromEnergy(energy);
    const int64_t ms = (totalSamplesProcessed_ * 1000) / config_.sampleRate;
    processWindow(energyDb, ms);
    totalSamplesProcessed_ += n;
}

void VadDetector::processWindowF32(const float* s, int n) {
    const float energy   = calculateEnergy(s, n);
    const float energyDb = dbFromEnergy(energy);
    const int64_t ms = (totalSamplesProcessed_ * 1000) / config_.sampleRate;
    processWindow(energyDb, ms);
    totalSamplesProcessed_ += n;
}

std::vector<VadSegment> VadDetector::process(const int16_t* samples, int count) {
    segments_.clear();
    reset();
    for (int i = 0; i < count; i += windowSamples_) {
        const int n = std::min(windowSamples_, count - i);
        processWindowI16(samples + i, n);
    }
    if (inSpeech_) {
        VadSegment seg;
        seg.startMs = speechStartMs_;
        seg.endMs = (totalSamplesProcessed_ * 1000) / config_.sampleRate;
        seg.confidence = 0.8f;
        seg.isSpeech = true;
        if (seg.endMs - seg.startMs >= config_.minSpeechMs) segments_.push_back(seg);
    }
    return segments_;
}

std::vector<VadSegment> VadDetector::process(const float* samples, int count) {
    segments_.clear();
    reset();
    for (int i = 0; i < count; i += windowSamples_) {
        const int n = std::min(windowSamples_, count - i);
        processWindowF32(samples + i, n);
    }
    if (inSpeech_) {
        VadSegment seg;
        seg.startMs = speechStartMs_;
        seg.endMs = (totalSamplesProcessed_ * 1000) / config_.sampleRate;
        seg.confidence = 0.8f;
        seg.isSpeech = true;
        if (seg.endMs - seg.startMs >= config_.minSpeechMs) segments_.push_back(seg);
    }
    return segments_;
}

void VadDetector::pushAudio(const int16_t* samples, int count) {
    for (int i = 0; i < count; i += windowSamples_) {
        const int n = std::min(windowSamples_, count - i);
        processWindowI16(samples + i, n);
    }
}

void VadDetector::pushAudio(const float* samples, int count) {
    for (int i = 0; i < count; i += windowSamples_) {
        const int n = std::min(windowSamples_, count - i);
        processWindowF32(samples + i, n);
    }
}

std::vector<VadSegment> VadDetector::getSegments() {
    return segments_;
}

VadSegment VadDetector::finalize() {
    VadSegment seg{};
    if (inSpeech_) {
        seg.startMs = speechStartMs_;
        seg.endMs = (totalSamplesProcessed_ * 1000) / config_.sampleRate;
        seg.confidence = 0.8f;
        seg.isSpeech = true;
        if (seg.endMs - seg.startMs >= config_.minSpeechMs) {
            segments_.push_back(seg);
            if (callback_) callback_(seg);
        }
        inSpeech_ = false;
    }
    return seg;
}

void VadDetector::reset() {
    inSpeech_ = false;
    speechStartMs_ = 0;
    silenceStartMs_ = 0;
    totalSamplesProcessed_ = 0;
    currentEnergyDb_ = -100.0f;
    std::fill(energyHistory_.begin(), energyHistory_.end(), -100.0f);
    energyHistoryPos_ = 0;
    energySum_ = -100.0f * static_cast<float>(energyHistory_.size());
    historyPrimed_ = false;
    segments_.clear();
}

} // namespace stt
