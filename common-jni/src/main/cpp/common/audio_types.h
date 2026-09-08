#ifndef AUDIO_TYPES_H
#define AUDIO_TYPES_H

#include <cstdint>
#include <cstddef>
#include <vector>
#include <memory>

namespace stt {

/**
 * Non-owning view of float audio samples.
 * Use this for passing audio data without copying.
 */
struct AudioViewF {
    float* data = nullptr;
    size_t frames = 0;      // Number of samples per channel
    int channels = 1;
    int sampleRate = 16000;
    
    AudioViewF() = default;
    AudioViewF(float* d, size_t f, int ch = 1, int sr = 16000)
        : data(d), frames(f), channels(ch), sampleRate(sr) {}
    
    size_t totalSamples() const { return frames * channels; }
    size_t sizeBytes() const { return totalSamples() * sizeof(float); }
    bool empty() const { return frames == 0 || data == nullptr; }
    
    // Duration in milliseconds
    int64_t durationMs() const {
        return sampleRate > 0 ? (frames * 1000) / sampleRate : 0;
    }
};

/**
 * Non-owning view of int16 audio samples.
 */
struct AudioViewI16 {
    int16_t* data = nullptr;
    size_t frames = 0;
    int channels = 1;
    int sampleRate = 16000;
    
    AudioViewI16() = default;
    AudioViewI16(int16_t* d, size_t f, int ch = 1, int sr = 16000)
        : data(d), frames(f), channels(ch), sampleRate(sr) {}
    
    size_t totalSamples() const { return frames * channels; }
    size_t sizeBytes() const { return totalSamples() * sizeof(int16_t); }
    bool empty() const { return frames == 0 || data == nullptr; }
    
    int64_t durationMs() const {
        return sampleRate > 0 ? (frames * 1000) / sampleRate : 0;
    }
};

/**
 * Audio frame with timestamp for streaming/synchronization.
 */
struct AudioFrame {
    AudioViewF audio;
    int64_t pts = 0;            // Presentation timestamp in microseconds
    int64_t dts = 0;            // Decode timestamp (usually same as pts for audio)
    uint32_t flags = 0;         // Frame flags
    
    static constexpr uint32_t FLAG_KEYFRAME = 1 << 0;
    static constexpr uint32_t FLAG_END_OF_STREAM = 1 << 1;
    static constexpr uint32_t FLAG_DISCONTINUITY = 1 << 2;
    
    bool isKeyframe() const { return flags & FLAG_KEYFRAME; }
    bool isEndOfStream() const { return flags & FLAG_END_OF_STREAM; }
    bool isDiscontinuity() const { return flags & FLAG_DISCONTINUITY; }
};

/**
 * Owning audio buffer with automatic memory management.
 */
class AudioBuffer {
public:
    AudioBuffer() = default;
    
    explicit AudioBuffer(size_t samples, int channels = 1, int sampleRate = 16000)
        : channels_(channels), sampleRate_(sampleRate) {
        data_.resize(samples * channels);
    }
    
    // Create from existing data (copies)
    AudioBuffer(const float* data, size_t samples, int channels = 1, int sampleRate = 16000)
        : channels_(channels), sampleRate_(sampleRate) {
        data_.assign(data, data + samples * channels);
    }
    
    // Create from int16 data (converts to float)
    static AudioBuffer fromInt16(const int16_t* data, size_t samples, 
                                  int channels = 1, int sampleRate = 16000) {
        AudioBuffer buffer(samples, channels, sampleRate);
        constexpr float scale = 1.0f / 32768.0f;
        for (size_t i = 0; i < samples * channels; ++i) {
            buffer.data_[i] = data[i] * scale;
        }
        return buffer;
    }
    
    // Accessors
    float* data() { return data_.data(); }
    const float* data() const { return data_.data(); }
    size_t frames() const { return data_.size() / channels_; }
    size_t totalSamples() const { return data_.size(); }
    int channels() const { return channels_; }
    int sampleRate() const { return sampleRate_; }
    bool empty() const { return data_.empty(); }
    
    // Get view
    AudioViewF view() { 
        return AudioViewF(data_.data(), frames(), channels_, sampleRate_); 
    }
    
    // Resize
    void resize(size_t samples) { data_.resize(samples * channels_); }
    void clear() { data_.clear(); }
    
    // Duration
    int64_t durationMs() const {
        return sampleRate_ > 0 ? (frames() * 1000) / sampleRate_ : 0;
    }

private:
    std::vector<float> data_;
    int channels_ = 1;
    int sampleRate_ = 16000;
};

/**
 * Video frame structure for future AR/video processing.
 */
struct VideoFrame {
    uint8_t* data = nullptr;    // Pixel data (or plane pointers for YUV)
    int width = 0;
    int height = 0;
    int stride = 0;             // Bytes per row
    int64_t pts = 0;
    
    enum class Format {
        UNKNOWN,
        RGBA,
        RGB,
        NV21,       // Android camera
        YUV420P,
        BGRA
    };
    Format format = Format::UNKNOWN;
    
    size_t sizeBytes() const {
        switch (format) {
            case Format::RGBA:
            case Format::BGRA:
                return stride * height;
            case Format::RGB:
                return stride * height;
            case Format::NV21:
            case Format::YUV420P:
                return width * height * 3 / 2;
            default:
                return 0;
        }
    }
};

} // namespace stt

#endif // AUDIO_TYPES_H

