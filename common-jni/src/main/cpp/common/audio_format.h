#ifndef AUDIO_FORMAT_H
#define AUDIO_FORMAT_H

#include <cstdint>
#include <cstddef>

namespace stt {

/**
 * Canonical audio format for STT processing.
 * 
 * All audio in the pipeline should be converted to this format
 * as early as possible, then kept in this format until output.
 * 
 * Rationale:
 * - float32: Native format for neural networks, no conversion at inference
 * - mono: STT doesn't benefit from stereo
 * - 16kHz: Standard for Whisper, Vosk, most STT models
 */
struct AudioFormat {
    enum class SampleFormat {
        INT16,      // 16-bit signed integer
        FLOAT32     // 32-bit float (canonical for STT)
    };
    
    SampleFormat format = SampleFormat::FLOAT32;
    int sampleRate = 16000;
    int channels = 1;
    
    // Canonical STT format
    static constexpr AudioFormat stt() {
        return {SampleFormat::FLOAT32, 16000, 1};
    }
    
    // Common input formats
    static constexpr AudioFormat pcm16k() {
        return {SampleFormat::INT16, 16000, 1};
    }
    
    static constexpr AudioFormat pcm44k() {
        return {SampleFormat::INT16, 44100, 1};
    }
    
    static constexpr AudioFormat pcm48k() {
        return {SampleFormat::INT16, 48000, 1};
    }
    
    // Calculate bytes per sample
    size_t bytesPerSample() const {
        switch (format) {
            case SampleFormat::INT16: return 2;
            case SampleFormat::FLOAT32: return 4;
        }
        return 4;
    }
    
    // Calculate buffer size for duration
    size_t bufferSizeForDuration(float durationSeconds) const {
        return static_cast<size_t>(sampleRate * durationSeconds) * bytesPerSample() * channels;
    }
    
    // Calculate sample count for duration
    size_t sampleCountForDuration(float durationSeconds) const {
        return static_cast<size_t>(sampleRate * durationSeconds);
    }
    
    // Calculate duration for sample count
    float durationForSampleCount(size_t samples) const {
        return static_cast<float>(samples) / sampleRate;
    }
    
    bool operator==(const AudioFormat& other) const {
        return format == other.format && 
               sampleRate == other.sampleRate && 
               channels == other.channels;
    }
    
    bool operator!=(const AudioFormat& other) const {
        return !(*this == other);
    }
    
    // Check if this is the canonical STT format
    bool isCanonicalStt() const {
        return format == SampleFormat::FLOAT32 && 
               sampleRate == 16000 && 
               channels == 1;
    }
};

/**
 * Constants for the canonical STT format.
 */
namespace CanonicalFormat {
    constexpr int SAMPLE_RATE = 16000;
    constexpr int CHANNELS = 1;
    constexpr int BYTES_PER_SAMPLE = 4;  // float32
    
    // Common buffer sizes
    constexpr size_t SAMPLES_PER_SECOND = 16000;
    constexpr size_t SAMPLES_100MS = 1600;
    constexpr size_t SAMPLES_500MS = 8000;
    constexpr size_t SAMPLES_1S = 16000;
    constexpr size_t SAMPLES_30S = 480000;  // Whisper max context
    
    // Recommended buffer sizes (aligned for NEON)
    constexpr size_t CHUNK_SAMPLES = 1600;   // 100ms, NEON aligned
    constexpr size_t STREAM_BUFFER_SAMPLES = 32000;  // 2 seconds
}

} // namespace stt

#endif // AUDIO_FORMAT_H

