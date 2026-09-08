#ifndef STT_VOSK_ENGINE_H
#define STT_VOSK_ENGINE_H

#include "engine_interface.h"
#include <memory>

namespace stt {

/**
 * Vosk-based STT engine.
 * 
 * Features:
 * - Fast streaming recognition
 * - Low memory footprint
 * - Offline operation
 * - Cancellation support (NEW)
 * 
 * Improvements in this version:
 * - Segment accumulation for streaming parity with Whisper
 * - Memory-barrier-safe atomic operations
 * - Consistent JSON output format
 * - Thread-safe cancellation
 */
class VoskEngine : public IEngine {
public:
    VoskEngine();
    ~VoskEngine() override;

    bool initialize(const EngineConfig& config) override;
    int pushAudio(const int16_t* samples, int count, int sampleRate = 16000) override;
    int pushAudioFloat(const float* samples, int count, int sampleRate = 16000) override;
    std::string getPartial() override;
    std::string finalize() override;
    void reset() override;
    void release() override;
    std::string transcribeBatch(const int16_t* samples, int count, int sampleRate) override;
    std::string transcribeBatchFloat(const float* samples, int count, int sampleRate) override;
    bool isInitialized() const override;
    bool isProcessing() const override;
    std::string getLastError() const override;
    int getModelType() const override;
    
    EngineCapability getCapabilities() const override;

    static bool isAvailable();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace stt

#endif // STT_VOSK_ENGINE_H
