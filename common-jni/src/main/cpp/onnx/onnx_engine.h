#ifndef STT_ONNX_ENGINE_H
#define STT_ONNX_ENGINE_H

#include "engine_interface.h"
#include <memory>

namespace stt {

/**
 * ONNX Runtime-based STT engine.
 * 
 * This is a framework implementation that can be used with
 * various ONNX-format STT models (Whisper-ONNX, Silero, etc.)
 * 
 * Features:
 * - Cancellation support (NEW)
 * 
 * NOTE: Requires model-specific implementation for inference.
 */
class OnnxEngine : public IEngine {
public:
    OnnxEngine();
    ~OnnxEngine() override;

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

#endif // STT_ONNX_ENGINE_H
