#ifndef TTS_ENGINE_INTERFACE_H
#define TTS_ENGINE_INTERFACE_H

#include <string>
#include <vector>
#include <cstdint>
#include <atomic>
#include <functional>
#include <memory>

namespace common_jni {
namespace tts {

// ============================================================================
// Engine Types
// ============================================================================

enum class TtsEngineType : int {
    // 0 intentionally unused (previously VIBEVOICE, removed).
    SUPERTONIC  = 1,    // ONNX-based, fast
    KOKORO      = 2,    // ONNX-based, multi-speaker, 82M params
    SHERPA_ONNX = 3,    // ONNX-based wrapper, many VITS/Matcha/Kokoro voices
};

enum class TtsQualityMode : int {
    LOW_LATENCY = 0,    // Fastest, lower quality
    BALANCED = 1,       // Default
    HIGH_QUALITY = 2,   // Best quality, slower
};

// ============================================================================
// Engine Capabilities (mirrors STT EngineCapability pattern)
// ============================================================================

enum class TtsCapability : uint32_t {
    NONE = 0,
    
    // Core features
    BATCH_SYNTHESIS = 1 << 0,       // synthesize() works
    STREAMING_PUSH = 1 << 1,        // pushText() / streaming works
    
    // Voice features
    MULTI_SPEAKER = 1 << 2,         // Multiple built-in voices
    VOICE_CLONING = 1 << 3,         // Can clone from audio sample
    CUSTOM_VOICE = 1 << 4,          // Can load custom voice files
    
    // Language features
    MULTI_LANGUAGE = 1 << 5,        // Supports multiple languages
    LANGUAGE_DETECTION = 1 << 6,    // Auto-detect input language
    
    // Quality features
    WORD_TIMESTAMPS = 1 << 7,       // Word-level timing
    SSML_SUPPORT = 1 << 8,          // SSML markup support
    PROSODY_CONTROL = 1 << 9,       // Speed/pitch/volume control
    
    // Common combinations
    BASIC_TTS = BATCH_SYNTHESIS,
    STREAMING_TTS = BATCH_SYNTHESIS | STREAMING_PUSH,
    FULL_FEATURED = 0xFFFFFFFF
};

inline TtsCapability operator|(TtsCapability a, TtsCapability b) {
    return static_cast<TtsCapability>(
        static_cast<uint32_t>(a) | static_cast<uint32_t>(b)
    );
}

inline TtsCapability operator&(TtsCapability a, TtsCapability b) {
    return static_cast<TtsCapability>(
        static_cast<uint32_t>(a) & static_cast<uint32_t>(b)
    );
}

inline bool hasCapability(TtsCapability caps, TtsCapability flag) {
    return (static_cast<uint32_t>(caps) & static_cast<uint32_t>(flag)) != 0;
}

// ============================================================================
// Voice Information
// ============================================================================

struct TtsVoiceInfo {
    std::string id;
    std::string displayName;
    std::string language;       // "en", "zh", "ar", etc.
    std::string gender;         // "male", "female", "neutral"
    std::string style;          // "neutral", "cheerful", "sad", etc.
    bool isDefault = false;
    bool isCustom = false;      // User-loaded voice
};

// ============================================================================
// Audio Output
// ============================================================================

struct TtsAudioChunk {
    std::vector<float> samples;     // Audio samples [-1.0, 1.0]
    int sampleRate;
    int64_t timestampMs;
    bool isLast;
    
    // Optional word timing (if WORD_TIMESTAMPS capability)
    struct WordTiming {
        std::string word;
        int64_t startMs;
        int64_t endMs;
    };
    std::vector<WordTiming> wordTimings;
    
    // Convert to PCM16 for playback
    std::vector<int16_t> toPcm16() const {
        std::vector<int16_t> pcm(samples.size());
        for (size_t i = 0; i < samples.size(); i++) {
            float clamped = std::max(-1.0f, std::min(1.0f, samples[i]));
            pcm[i] = static_cast<int16_t>(clamped * 32767.0f);
        }
        return pcm;
    }
};

using TtsAudioCallback = std::function<void(const TtsAudioChunk&)>;

// ============================================================================
// Engine Configuration
// ============================================================================

struct TtsEngineConfig {
    // Required
    std::string modelPath;
    
    // Optional paths
    std::string vocabPath;          // Tokenizer vocabulary
    std::string voicePromptPath;    // Voice cloning reference
    std::string configPath;         // Engine-specific config
    
    // Quality settings
    TtsQualityMode qualityMode = TtsQualityMode::BALANCED;
    int sampleRate = 24000;         // Output sample rate
    
    // Performance settings
    int numThreads = 4;
    bool useGpu = false;
    bool useNnapi = false;
    
    // Streaming settings (if supported)
    int maxCacheLength = 4096;      // KV cache length for streaming
    int tokensPerStep = 4;          // Tokens per streaming step
    
    // Prosody control (if supported)
    float speed = 1.0f;             // 0.5 - 2.0
    float pitch = 1.0f;             // 0.5 - 2.0
    float volume = 1.0f;            // 0.0 - 1.0
    
    // Engine-specific settings as JSON
    std::string extraConfig;
};

// ============================================================================
// Engine Interface (mirrors STT IEngine pattern)
// ============================================================================

/**
 * Abstract interface for all TTS engines.
 *
 * Implementations: KokoroEngine, SherpaOnnxTtsEngine, SupertonicEngine
 * (enable each via its -DENABLE_* CMake flag).
 *
 * Lifecycle:
 *   1. Create engine
 *   2. initialize(config)
 *   3a. Batch mode: synthesize(text)
 *   3b. Streaming: startStreaming() -> pushText() -> finalizeStreaming()
 *   4. reset() to start new synthesis
 *   5. release() or destroy
 */
class ITtsEngine {
public:
    virtual ~ITtsEngine() = default;
    
    // =========================================================================
    // Lifecycle
    // =========================================================================
    
    /**
     * Initialize engine with configuration.
     * Must be called before any other methods.
     */
    virtual bool initialize(const TtsEngineConfig& config) = 0;
    
    /**
     * Reset engine state for new synthesis.
     * Clears internal buffers but keeps model loaded.
     */
    virtual void reset() = 0;
    
    /**
     * Release all resources including the model.
     * Engine must be re-initialized to use again.
     */
    virtual void release() = 0;
    
    // =========================================================================
    // Cancellation (thread-safe)
    // =========================================================================
    
    virtual void cancel() { cancelled_.store(true); }
    virtual bool isCancelled() const { return cancelled_.load(); }
    virtual void clearCancellation() { cancelled_.store(false); }
    
protected:
    std::atomic<bool> cancelled_{false};
    std::string lastError_;
    
    void setError(const std::string& error) { lastError_ = error; }
    
public:
    // =========================================================================
    // Batch Synthesis
    // =========================================================================
    
    /**
     * Synthesize text to audio (batch mode).
     * 
     * @param text Input text to synthesize
     * @param targetSampleRate Desired output sample rate (0 = use default)
     * @return Audio samples or empty on error
     */
    virtual std::vector<float> synthesize(
        const std::string& text,
        int targetSampleRate = 0
    ) = 0;
    
    // =========================================================================
    // Streaming Synthesis
    // =========================================================================
    
    /**
     * Start streaming synthesis session.
     * 
     * @param callback Function to receive audio chunks
     * @return true if streaming started successfully
     */
    virtual bool startStreaming(TtsAudioCallback callback) {
        (void)callback;
        return false;  // Default: not supported
    }
    
    /**
     * Push text for streaming synthesis.
     * 
     * @param text Text chunk to synthesize
     * @return Number of audio samples generated, or -1 on error
     */
    virtual int pushText(const std::string& text) {
        (void)text;
        return -1;  // Default: not supported
    }
    
    /**
     * Finalize streaming session.
     * Flushes any remaining audio.
     * 
     * @return true if successful
     */
    virtual bool finalizeStreaming() {
        return false;  // Default: not supported
    }
    
    // =========================================================================
    // Voice Management
    // =========================================================================
    
    /**
     * Get list of available voices.
     */
    virtual std::vector<TtsVoiceInfo> getAvailableVoices() const = 0;
    
    /**
     * Set active voice by ID.
     */
    virtual bool setVoice(const std::string& voiceId) = 0;
    
    /**
     * Get current voice ID.
     */
    virtual std::string getCurrentVoice() const = 0;
    
    /**
     * Load custom voice from file (for voice cloning).
     * Only supported if VOICE_CLONING or CUSTOM_VOICE capability.
     */
    virtual bool loadCustomVoice(const std::string& voicePath) {
        (void)voicePath;
        return false;  // Default: not supported
    }
    
    // =========================================================================
    // State Query
    // =========================================================================
    
    virtual bool isInitialized() const = 0;
    virtual bool isProcessing() const { return false; }
    virtual std::string getLastError() const { return lastError_; }
    
    // =========================================================================
    // Engine Info
    // =========================================================================
    
    virtual TtsEngineType getType() const = 0;
    virtual std::string getName() const = 0;
    virtual std::string getVersion() const = 0;
    virtual int getDefaultSampleRate() const = 0;
    
    /**
     * Get engine capabilities.
     */
    virtual TtsCapability getCapabilities() const {
        return TtsCapability::BATCH_SYNTHESIS;
    }
    
    bool hasCapability(TtsCapability cap) const {
        return tts::hasCapability(getCapabilities(), cap);
    }
    
    /**
     * Get list of supported languages.
     */
    virtual std::vector<std::string> getSupportedLanguages() const {
        return {"en"};
    }
    
    // =========================================================================
    // Streaming State (for engines that support it)
    // =========================================================================
    
    /**
     * Get current sequence length (tokens processed).
     */
    virtual int getSequenceLength() const { return 0; }
    
    /**
     * Get remaining cache capacity (tokens).
     */
    virtual int getRemainingCapacity() const { return 0; }
};

// ============================================================================
// Factory Functions
// ============================================================================

/**
 * Create TTS engine of specified type.
 */
std::unique_ptr<ITtsEngine> createTtsEngine(TtsEngineType type);

/**
 * Check if engine type is available (compiled in).
 */
bool isTtsEngineAvailable(TtsEngineType type);

/**
 * Get list of available engine types.
 */
std::vector<TtsEngineType> getAvailableTtsEngines();

/** Parse and validate a TTS config without partially mutating `config`. */
bool parseTtsConfig(
    const std::string& json,
    TtsEngineConfig& config,
    std::string* error = nullptr
);

} // namespace tts
} // namespace common_jni

#endif // TTS_ENGINE_INTERFACE_H
