// =============================================================================
// VITS ONNX TTS engine (sherpa-onnx compatible bundles)
//
// Runs sherpa-onnx VITS voice bundles entirely on-device through the vendored
// ONNX Runtime — no sherpa native library needed:
//   <dir>/model.onnx    VITS graph (acoustic model + vocoder in one)
//   <dir>/tokens.txt    phoneme -> id (one "symbol id" pair per line)
//   <dir>/lexicon.txt   WORD -> phoneme sequence (optional; without it the
//                       engine falls back to per-character token lookup)
//   <dir>/sample_rate.txt  optional override (one integer, e.g. 22050)
// =============================================================================

#ifndef TTS_VITS_ONNX_ENGINE_H
#define TTS_VITS_ONNX_ENGINE_H

#include "tts_engine_interface.h"

#include <unordered_map>
#include <mutex>

#if defined(WITH_ONNX)
// ONNX Runtime C API (vendored under onnx/include).
#include <onnxruntime/onnxruntime_c_api.h>
#endif

namespace common_jni {
namespace tts {

class VitsOnnxEngine : public ITtsEngine {
public:
    VitsOnnxEngine() = default;
    ~VitsOnnxEngine() override;

    bool initialize(const TtsEngineConfig& config) override;
    void reset() override;
    void release() override;

    std::vector<float> synthesize(const std::string& text, int targetSampleRate = 0) override;

    std::vector<TtsVoiceInfo> getAvailableVoices() const override;
    bool setVoice(const std::string& voiceId) override;
    std::string getCurrentVoice() const override { return "default"; }

    bool isInitialized() const override { return initialized_; }
    TtsEngineType getType() const override { return TtsEngineType::SHERPA_ONNX; }
    std::string getName() const override { return "VITS-ONNX (sherpa bundles)"; }
    std::string getVersion() const override { return "1.0"; }
    int getDefaultSampleRate() const override { return sampleRate_; }
    std::vector<std::string> getSupportedLanguages() const override { return {"en", "zh"}; }

private:
#if defined(WITH_ONNX)
    bool loadVocabulary(const std::string& dir);
    bool loadLexicon(const std::string& dir);
    int detectSampleRate(const TtsEngineConfig& config) const;
    bool tokenizeText(const std::string& text, std::vector<int64_t>& ids);

    const OrtApi* api_ = nullptr;
    OrtEnv* env_ = nullptr;
    OrtSessionOptions* options_ = nullptr;
    OrtSession* session_ = nullptr;
#endif
    std::unordered_map<std::string, int64_t> tokenIds_;
    std::unordered_map<std::string, std::vector<std::string>> lexicon_;
    int sampleRate_ = 22050;
    float lengthScale_ = 1.0f;
    float noiseScale_ = 0.667f;
    float noiseScaleW_ = 0.8f;
    std::atomic<bool> initialized_{false};
    std::mutex mutex_;
};

}  // namespace tts
}  // namespace common_jni

#endif  // TTS_VITS_ONNX_ENGINE_H
