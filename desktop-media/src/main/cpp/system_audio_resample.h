#ifndef HEARTH_SYSTEM_AUDIO_RESAMPLE_H
#define HEARTH_SYSTEM_AUDIO_RESAMPLE_H

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <vector>

namespace hearth {
enum class SystemAudioSample { Float32, Int16, Int24, Int32 };

inline size_t systemAudioSampleBytes(SystemAudioSample format) {
    switch (format) {
        case SystemAudioSample::Int16: return 2;
        case SystemAudioSample::Int24: return 3;
        default: return 4;
    }
}

/** Averages little-endian interleaved channels into mono floats; a non-finite sample is an error. */
inline void systemAudioDownmix(const uint8_t* bytes, size_t frames, unsigned channels, SystemAudioSample format, std::vector<float>& mono) {
    if (channels < 1 || channels > 32) throw std::runtime_error("Unsupported system audio channel count");
    const size_t width = systemAudioSampleBytes(format);
    mono.resize(frames);
    for (size_t frame = 0; frame < frames; ++frame) {
        double sum = 0;
        for (unsigned channel = 0; channel < channels; ++channel) {
            const uint8_t* sample = bytes + (frame * channels + channel) * width;
            switch (format) {
                case SystemAudioSample::Float32: {
                    float value; std::memcpy(&value, sample, sizeof(value));
                    if (!std::isfinite(value)) throw std::runtime_error("System audio returned non-finite samples");
                    sum += value; break;
                }
                case SystemAudioSample::Int16:
                    sum += static_cast<int16_t>(uint16_t(sample[0]) | (uint16_t(sample[1]) << 8)) / 32768.0; break;
                case SystemAudioSample::Int24:
                    sum += static_cast<int32_t>((uint32_t(sample[0]) << 8) | (uint32_t(sample[1]) << 16) | (uint32_t(sample[2]) << 24)) / 2147483648.0; break;
                case SystemAudioSample::Int32:
                    sum += static_cast<int32_t>(uint32_t(sample[0]) | (uint32_t(sample[1]) << 8) | (uint32_t(sample[2]) << 16) | (uint32_t(sample[3]) << 24)) / 2147483648.0; break;
            }
        }
        mono[frame] = static_cast<float>(sum / channels);
    }
}

/**
 * Streaming band-limited mono resampler to 16 kHz: a Hann-windowed sinc whose cutoff sits
 * below the lower Nyquist rate. History is bounded and output is independent of chunking.
 */
class SystemAudioResampler {
public:
    explicit SystemAudioResampler(unsigned inputRate) {
        if (inputRate < 8000 || inputRate > 384000) throw std::runtime_error("Unsupported system audio sample rate");
        step_ = inputRate / 16000.0;
        const double scale = step_ > 1.0 ? step_ : 1.0;
        cutoff_ = 0.92 / scale;
        half_ = static_cast<size_t>(std::ceil(10.0 * scale));
        history_.assign(half_, 0.0f);
        position_ = static_cast<double>(half_);
    }
    void push(const float* samples, size_t count, std::vector<float>& output) {
        history_.insert(history_.end(), samples, samples + count);
        while (true) {
            const auto center = static_cast<size_t>(position_);
            if (center + half_ >= history_.size()) break;
            double sum = 0, weight = 0;
            for (size_t index = center + 1 - half_; index <= center + half_; ++index) {
                const double distance = static_cast<double>(index) - position_;
                const double phase = kPi * cutoff_ * distance;
                const double tap = (std::fabs(phase) < 1e-9 ? 1.0 : std::sin(phase) / phase) * (0.5 + 0.5 * std::cos(kPi * distance / static_cast<double>(half_)));
                sum += tap * history_[index]; weight += tap;
            }
            output.push_back(static_cast<float>(sum / weight));
            position_ += step_;
        }
        const auto consumed = static_cast<size_t>(position_) + 1 - half_;
        if (consumed >= 4096) {
            history_.erase(history_.begin(), history_.begin() + static_cast<std::ptrdiff_t>(consumed));
            position_ -= static_cast<double>(consumed);
        }
    }
private:
    static constexpr double kPi = 3.14159265358979323846;
    double step_ = 1, cutoff_ = 1, position_ = 0;
    size_t half_ = 0;
    std::vector<float> history_;
};
}
#endif
