#ifndef AUDIO_GATE_H
#define AUDIO_GATE_H

#include <cstdint>
#include <cmath>
#include <limits>
#include <vector>
#include <algorithm>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define STT_AUDIO_GATE_HAS_NEON 1
#else
#define STT_AUDIO_GATE_HAS_NEON 0
#endif

namespace stt {

/**
 * Audio gate configuration for voice activity detection.
 */
struct AudioGateConfig {
    float rmsThresholdDb = -45.0f;      // RMS threshold in dB (below = silence)
    float zeroCrossingMax = 0.55f;      // Max zero-crossing rate for speech
    int minActiveSamples = 3200;        // Minimum active samples (200ms at 16kHz)
    int minSilenceSamples = 3200;       // Minimum silence to close (200ms at 16kHz)
    float smoothingAlpha = 0.25f;       // Release smoothing (falling level)
    float attackAlpha = 0.5f;           // Attack smoothing (rising level)
    int sampleRate = 16000;
    
    // Precomputed from dB
    float rmsThresholdLinear() const {
        return std::pow(10.0f, rmsThresholdDb / 20.0f);
    }
};

/**
 * Result of audio gate analysis for a segment.
 */
struct AudioGateResult {
    bool isActive;              // true if speech detected
    float rmsLevel;             // RMS level (0-1)
    float rmsLevelDb;           // RMS level in dB
    float zeroCrossingRate;     // Zero-crossing rate (0-1)
    int activeSamples;          // Number of active samples
    int silentSamples;          // Number of silent samples
};

/**
 * High-performance audio gate for voice activity detection.
 * 
 * Use this to skip silent segments before expensive STT processing.
 * Saves 2-3x effective throughput on real-world audio.
 * 
 * Usage:
 * ```cpp
 * AudioGate gate(config);
 * 
 * for (auto& chunk : audioChunks) {
 *     auto result = gate.analyze(chunk.data(), chunk.size());
 *     if (result.isActive) {
 *         sttEngine->process(chunk);
 *     }
 * }
 * ```
 */
class AudioGate {
public:
    explicit AudioGate(const AudioGateConfig& config = AudioGateConfig())
        : config_(config)
        , smoothedRms_(0.0f)
        , consecutiveSilent_(0)
        , consecutiveActive_(0) {}
    
    /**
     * Analyze a frame of audio and determine if it's active (speech).
     * Uses RMS energy + zero-crossing rate heuristics.
     */
    AudioGateResult analyze(const int16_t* samples, size_t count) {
        if (!samples || count == 0) {
            return {false, 0.0f, -100.0f, 0.0f, 0, 0};
        }
        
        // Calculate RMS and zero-crossing rate
        float rms = calculateRmsNeon(samples, count);
        float zcr = calculateZeroCrossingRate(samples, count);

        // Asymmetric IIR smoothing: fast attack so an utterance onset
        // registers inside the hysteresis window even from a silence floor,
        // slow release to ride through inter-word pauses. The old symmetric
        // alpha=0.1 needed ~700ms of continuous speech to cross the
        // threshold, so gate reopenings failed on conversational onsets.
        const float alpha =
            (rms > smoothedRms_) ? config_.attackAlpha : config_.smoothingAlpha;
        smoothedRms_ = alpha * rms + (1.0f - alpha) * smoothedRms_;

        // Decision logic. The ZCR ceiling admits unvoiced fricatives
        // (s/f/sh — ZCR 0.3-0.5): they are speech, not noise.
        bool frameActive = (smoothedRms_ >= config_.rmsThresholdLinear()) &&
                          (zcr <= config_.zeroCrossingMax);
        
        // Hysteresis: require minimum consecutive samples
        if (frameActive) {
            consecutiveActive_ = saturatingSampleCount(consecutiveActive_, count);
            consecutiveSilent_ = 0;
        } else {
            consecutiveSilent_ = saturatingSampleCount(consecutiveSilent_, count);
            consecutiveActive_ = 0;
        }
        
        // Final decision with hysteresis
        bool isActive = (consecutiveActive_ >= config_.minActiveSamples) ||
                       (consecutiveSilent_ < config_.minSilenceSamples && wasActive_);
        
        wasActive_ = isActive;
        
        float rmsDb = (smoothedRms_ > 0.0f) ? 20.0f * std::log10(smoothedRms_) : -100.0f;
        
        return {
            isActive,
            smoothedRms_,
            rmsDb,
            zcr,
            consecutiveActive_,
            consecutiveSilent_
        };
    }
    
    /**
     * Analyze float samples.
     */
    AudioGateResult analyzeFloat(const float* samples, size_t count) {
        if (!samples || count == 0) {
            return {false, 0.0f, -100.0f, 0.0f, 0, 0};
        }
        
        float rms = calculateRmsFloat(samples, count);
        float zcr = calculateZeroCrossingRateFloat(samples, count);

        // Asymmetric smoothing — see analyze() for the rationale.
        const float alpha =
            (rms > smoothedRms_) ? config_.attackAlpha : config_.smoothingAlpha;
        smoothedRms_ = alpha * rms + (1.0f - alpha) * smoothedRms_;

        bool frameActive = (smoothedRms_ >= config_.rmsThresholdLinear()) &&
                          (zcr <= config_.zeroCrossingMax);
        
        if (frameActive) {
            consecutiveActive_ = saturatingSampleCount(consecutiveActive_, count);
            consecutiveSilent_ = 0;
        } else {
            consecutiveSilent_ = saturatingSampleCount(consecutiveSilent_, count);
            consecutiveActive_ = 0;
        }
        
        bool isActive = (consecutiveActive_ >= config_.minActiveSamples) ||
                       (consecutiveSilent_ < config_.minSilenceSamples && wasActive_);
        
        wasActive_ = isActive;
        
        float rmsDb = (smoothedRms_ > 0.0f) ? 20.0f * std::log10(smoothedRms_) : -100.0f;
        
        return {isActive, smoothedRms_, rmsDb, zcr, consecutiveActive_, consecutiveSilent_};
    }
    
    /**
     * Filter audio, returning only active segments.
     * Returns indices of active regions as [start, end) pairs.
     */
    std::vector<std::pair<size_t, size_t>> getActiveRegions(
        const int16_t* samples, 
        size_t totalSamples,
        size_t frameSize = 1600  // 100ms at 16kHz
    ) {
        std::vector<std::pair<size_t, size_t>> regions;
        if ((!samples && totalSamples != 0) || frameSize == 0) return regions;
        
        reset();
        
        size_t regionStart = 0;
        bool inRegion = false;
        
        for (size_t i = 0; i < totalSamples; i += frameSize) {
            size_t frameSamples = std::min(frameSize, totalSamples - i);
            auto result = analyze(samples + i, frameSamples);
            
            if (result.isActive && !inRegion) {
                // Start new region
                regionStart = i;
                inRegion = true;
            } else if (!result.isActive && inRegion) {
                // End region
                regions.emplace_back(regionStart, i);
                inRegion = false;
            }
        }
        
        // Close final region if still active
        if (inRegion) {
            regions.emplace_back(regionStart, totalSamples);
        }
        
        return regions;
    }
    
    /**
     * Reset gate state.
     */
    void reset() {
        smoothedRms_ = 0.0f;
        consecutiveSilent_ = 0;
        consecutiveActive_ = 0;
        wasActive_ = false;
    }
    
    /**
     * Update configuration.
     */
    void setConfig(const AudioGateConfig& config) {
        config_ = config;
        reset();
    }
    
    const AudioGateConfig& getConfig() const { return config_; }

private:
    AudioGateConfig config_;
    float smoothedRms_;
    int consecutiveSilent_;
    int consecutiveActive_;
    bool wasActive_ = false;

    static int saturatingSampleCount(int current, size_t increment) noexcept {
        const auto available = static_cast<size_t>(
            std::numeric_limits<int>::max() - current
        );
        return increment >= available
            ? std::numeric_limits<int>::max()
            : current + static_cast<int>(increment);
    }
    
    /**
     * Calculate RMS using NEON SIMD.
     */
    float calculateRmsNeon(const int16_t* samples, size_t count) {
        if (count == 0) return 0.0f;
        
        double sumSquares = 0.0;
        
#if STT_AUDIO_GATE_HAS_NEON
        int64x2_t sum_vec = vdupq_n_s64(0);
        
        size_t i = 0;
        for (; i + 8 <= count; i += 8) {
            int16x8_t s = vld1q_s16(samples + i);
            
            // Square and accumulate
            int32x4_t lo = vmull_s16(vget_low_s16(s), vget_low_s16(s));
            int32x4_t hi = vmull_s16(vget_high_s16(s), vget_high_s16(s));
            
            sum_vec = vaddq_s64(sum_vec, vpaddlq_s32(lo));
            sum_vec = vaddq_s64(sum_vec, vpaddlq_s32(hi));
        }
        
        // Horizontal add
        sumSquares = static_cast<double>(vgetq_lane_s64(sum_vec, 0) + vgetq_lane_s64(sum_vec, 1));
        
        // Remaining samples
        for (; i < count; ++i) {
            sumSquares += static_cast<double>(samples[i]) * samples[i];
        }
#else
        for (size_t i = 0; i < count; ++i) {
            sumSquares += static_cast<double>(samples[i]) * samples[i];
        }
#endif
        
        // Normalize to [0, 1] range (int16 max = 32768)
        double meanSquares = sumSquares / static_cast<double>(count);
        return static_cast<float>(std::sqrt(meanSquares) / 32768.0);
    }
    
    /**
     * Calculate RMS for float samples.
     */
    float calculateRmsFloat(const float* samples, size_t count) {
        if (count == 0) return 0.0f;
        
        double sumSquares = 0.0;
        
#if STT_AUDIO_GATE_HAS_NEON
        float32x4_t sum_vec = vdupq_n_f32(0.0f);
        
        size_t i = 0;
        for (; i + 4 <= count; i += 4) {
            float32x4_t s = vld1q_f32(samples + i);
            sum_vec = vmlaq_f32(sum_vec, s, s);
        }
        
#if defined(__aarch64__)
        sumSquares = vaddvq_f32(sum_vec);
#else
        float32x2_t pair = vadd_f32(vget_low_f32(sum_vec), vget_high_f32(sum_vec));
        pair = vpadd_f32(pair, pair);
        sumSquares = vget_lane_f32(pair, 0);
#endif
        
        for (; i < count; ++i) {
            sumSquares += samples[i] * samples[i];
        }
#else
        for (size_t i = 0; i < count; ++i) {
            sumSquares += samples[i] * samples[i];
        }
#endif
        
        return static_cast<float>(
            std::sqrt(sumSquares / static_cast<double>(count))
        );
    }
    
    /**
     * Calculate zero-crossing rate.
     */
    float calculateZeroCrossingRate(const int16_t* samples, size_t count) {
        if (count < 2) return 0.0f;
        
        int crossings = 0;
        for (size_t i = 1; i < count; ++i) {
            if ((samples[i] >= 0) != (samples[i-1] >= 0)) {
                ++crossings;
            }
        }
        
        return static_cast<float>(crossings) / static_cast<float>(count - 1);
    }
    
    float calculateZeroCrossingRateFloat(const float* samples, size_t count) {
        if (count < 2) return 0.0f;
        
        int crossings = 0;
        for (size_t i = 1; i < count; ++i) {
            if ((samples[i] >= 0.0f) != (samples[i-1] >= 0.0f)) {
                ++crossings;
            }
        }
        
        return static_cast<float>(crossings) / static_cast<float>(count - 1);
    }
};

} // namespace stt

#undef STT_AUDIO_GATE_HAS_NEON

#endif // AUDIO_GATE_H
