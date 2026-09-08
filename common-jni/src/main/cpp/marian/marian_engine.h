#ifndef STT_MARIAN_ENGINE_H
#define STT_MARIAN_ENGINE_H

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "marian_tokenizer.h"

// ONNX Runtime C API (vendor headers live in common-jni/src/main/cpp/onnx/include).
#if __has_include("onnxruntime/onnxruntime_c_api.h")
    #include "onnxruntime/onnxruntime_c_api.h"
#elif __has_include("onnxruntime_c_api.h")
    #include "onnxruntime_c_api.h"
#else
    #error "ONNX Runtime headers not found. Extract them to onnx/include/onnxruntime/"
#endif

namespace stt {
namespace marian {

/**
 * Greedy OPUS-MT/Marian translation over the bundled ONNX Runtime.
 *
 * Executes the optimum-exported quantized graph pair:
 *   encoder_model(_quantized).onnx  — input_ids + attention_mask
 *                                     -> last_hidden_state
 *   decoder_model_merged(_quantized).onnx — single session with an on-graph
 *                                     KV-cache branch selected by
 *                                     use_cache_branch; 6 layers x
 *                                     (decoder key/value + encoder key/value).
 *
 * Decode contract (validated against onnxruntime reference runs, 2026-08-16):
 *   - The init call (use_cache_branch=false) runs with empty past tensors of
 *     shape [1, heads, 0, headDim].
 *   - The init call's present.N.encoder.* tensors are the projected
 *     cross-attention cache and stay CONSTANT for the whole decode; the cache
 *     branch returns empty dummies for them, so they must never be fed back.
 *   - Only the present.N.decoder.* tensors are fed back, step after step.
 *   - logits are [1, 1, vocab]; the pad id (62801) is masked to -inf
 *     (generation_config bad_words_ids) before greedy argmax; stop at the
 *     </s> id (0) or kMaxNewTokens.
 *
 * All failures are reported through status strings; nothing throws across
 * the public API (see the JNI caller).
 */
class MarianEngine {
public:
    static constexpr int64_t kMaxNewTokens = 64;
    static constexpr int kHeads = 8;
    static constexpr int kLayers = 6;
    static constexpr int kHeadDim = 64;
    static constexpr int kHiddenDim = 512;

    /** Per-stage timing breakdown for one translate() call (instrumentation). */
    struct StageStats {
        int64_t tokenizeMs = 0;
        int64_t encoderMs = 0;
        int64_t decoderMs = 0;
        int64_t tokensDecoded = 0;
    };

    struct TranslateResult {
        bool ok = false;
        std::string text;
        std::string error;
        int64_t latencyMs = 0;
        StageStats stages;
    };

    ~MarianEngine();

    MarianEngine(const MarianEngine&) = delete;
    MarianEngine& operator=(const MarianEngine&) = delete;

    /**
     * Loads tokenizer + sessions from a model directory containing
     * source.spm, tokenizer.json, one encoder_model*.onnx and one
     * decoder_model_merged*.onnx. Returns nullptr on failure.
     */
    static std::unique_ptr<MarianEngine> create(const std::string& modelDir,
                                                int numThreads,
                                                std::string* error);

    /** Translates one caption utterance. Blocking; thread-safe. */
    TranslateResult translate(const std::string& text);

    /** Latency of the most recent successful translation, in milliseconds. */
    int64_t lastLatencyMs() const {
        return lastLatencyMs_.load(std::memory_order_relaxed);
    }

    /** Per-stage timing of the most recent successful translation. */
    StageStats lastStageStats() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return stageStats_;
    }

    const std::string& encoderPath() const { return encoderPath_; }
    const std::string& decoderPath() const { return decoderPath_; }

private:
    MarianEngine() = default;

    bool init(const std::string& modelDir, int numThreads, std::string* error);
    bool loadSession(const std::string& path, OrtSession** session,
                     std::string* error);
    bool runEncoder(const std::vector<int64_t>& ids,
                    std::vector<float>& hidden, std::string* error);
    // Returns decoded ids (without </s>) via tokenizerDecoded on success.
    bool decodeLoop(const std::vector<float>& hidden, size_t encLen,
                    std::vector<int64_t>& decodedIds, std::string* error);
    bool setError(std::string* error, const std::string& message) const;

    // Persistent ORT handles.
    const OrtApi* api_ = nullptr;
    OrtEnv* env_ = nullptr;
    OrtSessionOptions* options_ = nullptr;
    OrtMemoryInfo* memoryInfo_ = nullptr;
    OrtAllocator* allocator_ = nullptr;
    OrtSession* encoderSession_ = nullptr;
    OrtSession* decoderSession_ = nullptr;

    MarianTokenizer tokenizer_;
    std::string encoderPath_;
    std::string decoderPath_;
    mutable std::mutex mutex_;
    int64_t eosId_ = -1;
    int64_t padId_ = -1;

    // Reused scratch buffers (guarded by mutex_).
    std::vector<int64_t> encIds_;
    std::vector<int64_t> attention_;
    std::vector<float> hidden_;
    std::vector<int64_t> decInput_;
    std::vector<float> logits_;
    std::vector<float> decoderPast_[kLayers][2];   // [layer][key|value]
    std::vector<float> encoderPast_[kLayers][2];
    std::vector<std::string> nameStorage_;
    std::vector<char*> inputNames_;
    std::vector<char*> outputNames_;
    std::atomic<int64_t> lastLatencyMs_{0};
    StageStats stageStats_;
};

} // namespace marian
} // namespace stt

#endif // STT_MARIAN_ENGINE_H
