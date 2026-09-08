#ifndef STT_AUDIO_DECODER_H
#define STT_AUDIO_DECODER_H

#include <string>
#include <vector>
#include <cstdint>
#include <memory>
#include <functional>

namespace stt {

/**
 * Audio file metadata
 */
struct AudioInfo {
    int64_t durationMs = 0;
    int sampleRate = 0;
    int channels = 0;
    int bitrate = 0;
    std::string format;
    std::string codec;
};

/**
 * Decoder capabilities
 */
enum class DecoderCapability : uint32_t {
    BATCH_DECODE = 1 << 0,      // Can decode entire file at once
    STREAMING_DECODE = 1 << 1,  // Can decode in chunks
    SEEKING = 1 << 2,           // Can seek to position
    METADATA = 1 << 3,          // Can read metadata
    RESAMPLING = 1 << 4,        // Has built-in resampling
};

inline DecoderCapability operator|(DecoderCapability a, DecoderCapability b) {
    return static_cast<DecoderCapability>(
        static_cast<uint32_t>(a) | static_cast<uint32_t>(b)
    );
}

inline bool hasCapability(DecoderCapability caps, DecoderCapability flag) {
    return (static_cast<uint32_t>(caps) & static_cast<uint32_t>(flag)) != 0;
}

/**
 * Abstract interface for audio decoding.
 * 
 * This allows swapping FFmpeg with MediaCodec or other decoders
 * without changing engine or JNI code.
 * 
 * All decoders MUST deliver:
 * - Mono audio (single channel)
 * - 16-bit signed PCM
 * - Target sample rate (resampled internally)
 */
class IAudioDecoder {
public:
    virtual ~IAudioDecoder() = default;
    
    // =========================================================
    // Capability Query
    // =========================================================
    
    /**
     * Get decoder capabilities bitmask.
     */
    virtual DecoderCapability getCapabilities() const = 0;
    
    /**
     * Check if decoder is available on this device.
     */
    virtual bool isAvailable() const = 0;
    
    /**
     * Get decoder name (e.g., "FFmpeg", "MediaCodec").
     */
    virtual std::string getName() const = 0;
    
    // =========================================================
    // Metadata
    // =========================================================
    
    /**
     * Get audio file information without full decode.
     */
    virtual AudioInfo getInfo(const std::string& source) = 0;
    
    /**
     * Get duration in milliseconds.
     */
    virtual int64_t getDuration(const std::string& source) = 0;
    
    // =========================================================
    // Batch Decode (entire file at once)
    // =========================================================
    
    /**
     * Decode entire file to PCM.
     * 
     * @param source File path or URI
     * @param targetSampleRate Desired output sample rate (typically 16000)
     * @return PCM samples as int16_t, empty on error
     */
    virtual std::vector<int16_t> decode(
        const std::string& source,
        int targetSampleRate
    ) = 0;
    
    // =========================================================
    // Streaming Decode
    // =========================================================
    
    /**
     * Open a stream for chunked decoding.
     * 
     * @param source File path or URI
     * @param targetSampleRate Desired output sample rate
     * @return true if stream opened successfully
     */
    virtual bool openStream(const std::string& source, int targetSampleRate) = 0;
    
    /**
     * Read a chunk of decoded audio.
     * 
     * @param maxSamples Maximum samples to return
     * @return PCM samples, empty if EOF or error
     */
    virtual std::vector<int16_t> readChunk(int maxSamples) = 0;
    
    /**
     * Check if stream has reached end of file.
     */
    virtual bool isStreamEof() const = 0;
    
    /**
     * Close the stream and release resources.
     */
    virtual void closeStream() = 0;
    
    // =========================================================
    // Error Handling
    // =========================================================
    
    /**
     * Check if last operation resulted in an error.
     */
    virtual bool hasError() const = 0;
    
    /**
     * Get last error message.
     */
    virtual std::string getLastError() const = 0;
    
    /**
     * Clear error state.
     */
    virtual void clearError() = 0;
};

/**
 * Factory for creating audio decoders.
 * 
 * Usage:
 *   auto decoder = AudioDecoderFactory::create(DecoderType::FFMPEG);
 *   if (decoder && decoder->isAvailable()) {
 *       auto pcm = decoder->decode("audio.mp3", 16000);
 *   }
 */
class AudioDecoderFactory {
public:
    enum class DecoderType {
        FFMPEG,       // FFmpeg-based decoder
        MEDIA_CODEC,  // Android MediaCodec (future)
        AUTO          // Best available
    };
    
    /**
     * Create a decoder of the specified type.
     * Returns nullptr if decoder type is not available.
     */
    static std::unique_ptr<IAudioDecoder> create(DecoderType type = DecoderType::AUTO);
    
    /**
     * Check if a decoder type is compiled in and available.
     */
    static bool isAvailable(DecoderType type);
    
    /**
     * Get capability mask for all compiled decoders.
     */
    static uint32_t getAvailableDecoders();
};

/**
 * Callback-based streaming decoder interface.
 * 
 * For use cases where pull-based readChunk() is not ideal
 * (e.g., background thread processing).
 */
class IStreamingDecoderCallback {
public:
    virtual ~IStreamingDecoderCallback() = default;
    
    /**
     * Called when a chunk of audio is decoded.
     * 
     * @param samples PCM audio data
     * @param count Number of samples
     * @param sampleRate Sample rate of audio
     * @param timestampMs Timestamp in milliseconds from start
     */
    virtual void onChunk(
        const int16_t* samples,
        int count,
        int sampleRate,
        int64_t timestampMs
    ) = 0;
    
    /**
     * Called when decoding completes.
     * 
     * @param success true if completed without error
     * @param error Error message if success is false
     */
    virtual void onComplete(bool success, const std::string& error) = 0;
    
    /**
     * Called periodically with progress updates.
     * 
     * @param progress Value from 0.0 to 1.0
     */
    virtual void onProgress(float progress) = 0;
};

} // namespace stt

#endif // STT_AUDIO_DECODER_H
