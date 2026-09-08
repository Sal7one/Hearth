#ifndef STT_ENGINE_INTERFACE_H
#define STT_ENGINE_INTERFACE_H

#include <string>
#include <vector>
#include <cstdint>
#include <atomic>
#include <mutex>

namespace stt {

/**
 * Engine capability flags.
 * Use getCapabilities() to query what features an engine supports.
 */
enum class EngineCapability : uint32_t {
    NONE = 0,
    
    // Core features
    BATCH_TRANSCRIPTION = 1 << 0,      // transcribeBatch() works
    STREAMING_PUSH = 1 << 1,           // pushAudio() works
    PARTIAL_RESULTS = 1 << 2,          // getPartial() returns intermediate text
    
    // Timing features
    SEGMENT_TIMESTAMPS = 1 << 3,       // Segment start/end times
    WORD_TIMESTAMPS = 1 << 4,          // Word/token level times
    
    // Language features
    LANGUAGE_DETECTION = 1 << 5,       // Auto-detect input language
    TRANSLATION = 1 << 6,              // Translate to English
    MULTI_LANGUAGE = 1 << 7,           // Supports multiple languages in same audio
    
    // Quality features
    SEGMENT_CONFIDENCE = 1 << 8,       // Per-segment confidence scores
    WORD_CONFIDENCE = 1 << 9,          // Per-word confidence scores
    
    // Common combinations
    BASIC_STT = BATCH_TRANSCRIPTION | SEGMENT_TIMESTAMPS,
    STREAMING_STT = STREAMING_PUSH | PARTIAL_RESULTS | SEGMENT_TIMESTAMPS,
    FULL_FEATURED = 0xFFFFFFFF
};

inline EngineCapability operator|(EngineCapability a, EngineCapability b) {
    return static_cast<EngineCapability>(
        static_cast<uint32_t>(a) | static_cast<uint32_t>(b)
    );
}

inline EngineCapability operator&(EngineCapability a, EngineCapability b) {
    return static_cast<EngineCapability>(
        static_cast<uint32_t>(a) & static_cast<uint32_t>(b)
    );
}

inline bool hasCapability(EngineCapability caps, EngineCapability flag) {
    return (static_cast<uint32_t>(caps) & static_cast<uint32_t>(flag)) != 0;
}

/**
 * Transcript segment with timing and confidence.
 */
struct TranscriptSegment {
    std::string text;
    int64_t startMs = 0;
    int64_t endMs = 0;
    float confidence = 0.0f;
    std::string language;  // Optional: detected language for this segment
};

enum class AudioProcessingMode {
    AUTO,
    BATCH,
    STREAMING
};

/**
 * Engine configuration - unified across all engines.
 * 
 * Not all options apply to all engines. Use getCapabilities()
 * to check what the engine supports.
 */
struct EngineConfig {
    // Required
    std::string modelPath;

    // Trusted or persisted-TOFU digest supplied by the app-private model registry.
    // Whisper and Vosk require it and verify the exact bytes before parsing them.
    std::string modelSha256;
    
    // Language settings
    std::string language = "auto";     // "auto", "en", "ar", "zh", etc.
    bool translateToEnglish = false;   // Whisper only: translate to English
    
    // Performance settings
    int sampleRate = 16000;            // Expected input sample rate
    int numThreads = 4;                // Number of inference threads
    
    // Whisper-specific (ignored by other engines)
    bool enableTimestamps = true;
    bool suppressBlank = true;
    float noSpeechThreshold = 0.6f;
    bool suppressNonSpeechTokens = false;  // Allow non-speech tokens
    bool debugForceEnglish = false;       // Debug mode: force English
    bool debugLogging = false;            // Enable verbose logging
    
    // Streaming settings
    AudioProcessingMode audioProcessingMode = AudioProcessingMode::AUTO;
    int chunkDurationMs = 30000;       // Chunk size for streaming (ms)
    int contextDurationMs = 1000;      // Context overlap between chunks (ms)
    
    // Legacy fields (kept for compatibility)
    bool enableVad = true;
    int maxSegmentLengthMs = 30000;
    float silenceThresholdDb = -40.0f;
    bool verbose = false;
};

/** Parse and validate an engine config without partially mutating `config`. */
bool parseConfig(
    const std::string& json,
    EngineConfig& config,
    std::string* error = nullptr
);

/**
 * Abstract interface for all STT engines.
 * 
 * Implementations: WhisperEngine, VoskEngine, OnnxEngine
 * 
 * Lifecycle:
 *   1. Create engine
 *   2. initialize(config)
 *   3a. Batch mode: transcribeBatch() or transcribeBatchFloat()
 *   3b. Streaming: pushAudio() repeatedly, getPartial(), finalize()
 *   4. reset() to start new transcription
 *   5. release() or destroy
 */
class IEngine {
public:
    virtual ~IEngine() = default;
    
    // =========================================================
    // Lifecycle
    // =========================================================
    
    /**
     * Initialize engine with configuration.
     * Must be called before any other methods.
     * 
     * @param config Engine configuration
     * @return true if initialization succeeded
     */
    virtual bool initialize(const EngineConfig& config) = 0;
    
    /**
     * Reset engine state for new transcription.
     * Clears internal buffers but keeps model loaded.
     */
    virtual void reset() = 0;
    
    /**
     * Release all resources including the model.
     * Engine must be re-initialized to use again.
     */
    virtual void release() = 0;
    
    // =========================================================
    // Cancellation (NEW - P0 from FFmpeg-Kit analysis)
    // =========================================================
    
    /**
     * Request cancellation of current operation.
     * This is thread-safe and can be called from any thread.
     * The engine will stop at the next safe point.
     */
    void cancel() {
        {
            std::lock_guard<std::mutex> lock(cancellationMutex_);
            cancelled_.store(true, std::memory_order_release);
        }
        onCancellationRequested();
    }

    /**
     * Permanently reject work for router-driven destruction. Unlike cancel(),
     * this signal cannot be cleared by reset or a new transcription.
     */
    void beginShutdown() {
        {
            std::lock_guard<std::mutex> lock(cancellationMutex_);
            shuttingDown_.store(true, std::memory_order_release);
            cancelled_.store(true, std::memory_order_release);
        }
        cancel();
    }
    
    /**
     * Check if cancellation has been requested.
     */
    bool isCancelled() const {
        return cancelled_.load(std::memory_order_acquire);
    }
    
    /**
     * Clear the cancellation flag.
     * Called automatically by reset().
     */
    void clearCancellation() {
        std::lock_guard<std::mutex> lock(cancellationMutex_);
        if (!shuttingDown_.load(std::memory_order_relaxed)) {
            cancelled_.store(false, std::memory_order_release);
        }
    }

    bool isShuttingDown() const {
        return shuttingDown_.load(std::memory_order_acquire);
    }

protected:
    /** Optional thread-safe wake-up hook; cancellation state is already set. */
    virtual void onCancellationRequested() {}

private:
    std::atomic<bool> cancelled_{false};
    std::atomic<bool> shuttingDown_{false};
    mutable std::mutex cancellationMutex_;
    
public:
    // =========================================================
    // Streaming Mode
    // =========================================================
    
    /**
     * Push audio samples for streaming transcription.
     * 
     * @param samples 16-bit PCM audio
     * @param count Number of samples
     * @param sampleRate Sample rate (will be resampled if needed)
     * @return 0 on success, negative on error
     */
    virtual int pushAudio(const int16_t* samples, int count, int sampleRate = 16000) = 0;
    
    /**
     * Push float audio samples for streaming transcription.
     * 
     * @param samples Float PCM audio (range -1.0 to 1.0)
     * @param count Number of samples
     * @param sampleRate Sample rate (will be resampled if needed)
     * @return 0 on success, negative on error
     */
    virtual int pushAudioFloat(const float* samples, int count, int sampleRate = 16000) = 0;
    
    /**
     * Get partial transcription of buffered audio.
     * 
     * @return JSON string with partial results, or empty if not available
     */
    virtual std::string getPartial() = 0;
    
    /**
     * Finalize streaming transcription.
     * Processes remaining buffer and returns complete result.
     * 
     * @return JSON string with final transcription
     */
    virtual std::string finalize() = 0;
    
    // =========================================================
    // Batch Mode
    // =========================================================
    
    /**
     * Transcribe entire audio buffer at once (16-bit PCM).
     * 
     * @param samples 16-bit PCM audio
     * @param count Number of samples
     * @param sampleRate Sample rate (will be resampled if needed)
     * @return JSON string with transcription result
     */
    virtual std::string transcribeBatch(const int16_t* samples, int count, int sampleRate) = 0;
    
    /**
     * Transcribe entire audio buffer at once (float PCM).
     * 
     * @param samples Float PCM audio (range -1.0 to 1.0)
     * @param count Number of samples
     * @param sampleRate Sample rate (will be resampled if needed)
     * @return JSON string with transcription result
     */
    virtual std::string transcribeBatchFloat(const float* samples, int count, int sampleRate) = 0;
    
    // =========================================================
    // State Query
    // =========================================================
    
    /**
     * Check if engine is initialized and ready to use.
     */
    virtual bool isInitialized() const = 0;
    
    /**
     * Check if engine is currently processing audio.
     */
    virtual bool isProcessing() const = 0;
    
    /**
     * Get last error message.
     */
    virtual std::string getLastError() const = 0;
    
    /**
     * Get model type identifier (engine-specific).
     */
    virtual int getModelType() const = 0;
    
    // =========================================================
    // Capability Query (NEW)
    // =========================================================
    
    /**
     * Get engine capabilities.
     * Default implementation returns basic capabilities.
     */
    virtual EngineCapability getCapabilities() const {
        return EngineCapability::BATCH_TRANSCRIPTION | 
               EngineCapability::STREAMING_PUSH |
               EngineCapability::SEGMENT_TIMESTAMPS;
    }
    
    /**
     * Check if engine has a specific capability.
     */
    bool hasCapability(EngineCapability cap) const {
        return stt::hasCapability(getCapabilities(), cap);
    }
    
    /**
     * Get list of supported languages.
     * Returns empty for engines that don't support language selection.
     */
    virtual std::vector<std::string> getSupportedLanguages() const {
        return {};
    }
};

} // namespace stt

#endif // STT_ENGINE_INTERFACE_H
