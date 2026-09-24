#ifndef STT_WHISPER_ENGINE_H
#define STT_WHISPER_ENGINE_H

#include "engine_interface.h"
#include <memory>
#include <atomic>

namespace stt {

class WhisperEngine : public IEngine {
public:
    WhisperEngine();
    ~WhisperEngine() override;

    bool initialize(const EngineConfig& config) override;
    int pushAudio(const int16_t* samples, int count, int sampleRate = 16000) override;
    int pushAudioFloat(const float* samples, int count, int sampleRate = 16000) override;
    std::string getPartial() override;
    std::string finalize() override;
    void reset() override;
    /** Report whether a reset actually cleared the streaming session. */
    bool resetChecked();
    void release() override;
    std::string transcribeBatch(const int16_t* samples, int count, int sampleRate) override;
    std::string transcribeBatchFloat(const float* samples, int count, int sampleRate) override;
    bool isInitialized() const override;
    bool isProcessing() const override;
    std::string getLastError() const override;
    int getModelType() const override;
    
    // Enhanced capability reporting
    EngineCapability getCapabilities() const override;
    std::vector<std::string> getSupportedLanguages() const override;

    // Whisper-specific
    static std::string getVersion();
    static bool isAvailable();

    /**
     * Attach a progress pipe for the next batch decode. The fd stays owned
     * by the caller (never closed here); pass -1 to detach. Updates are
     * newline-delimited JSON lines (common/pipe_progress.h contract). This
     * is additive: with no fd attached, behavior is exactly as before.
     */
    void setProgressFd(int fd);

    /**
     * Get detected language from last transcription.
     */
    std::string getDetectedLanguage() const;
    
    /**
     * Detect language from audio samples WITHOUT full transcription.
     * Uses whisper_lang_auto_detect() for fast LID-only operation.
     * 
     * @param samples Audio samples (16-bit PCM)
     * @param count Number of samples
     * @param sampleRate Sample rate (will be resampled to 16kHz if needed)
     * @return ISO language code (e.g., "en", "ar") or empty string on error
     */
    std::string detectLanguageOnly(const int16_t* samples, int count, int sampleRate = 16000);
    
    /**
     * Set chunk parameters for streaming mode.
     * @param chunkMs Chunk duration in milliseconds (default 30000)
     * @param overlapMs Overlap duration in milliseconds (default 1000)
     */
    void setChunkParams(int chunkMs, int overlapMs);

protected:
    /** IEngine hook: fires the same instant cancelled_ flips — wakes the decode loop's CancelToken. */
    void onCancellationRequested() override;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace stt

#endif // STT_WHISPER_ENGINE_H
