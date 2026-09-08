#include "onnx_engine.h"
#include "logging.h"
#include "error_codes.h"
#include "audio_utils.h"
#include "json_utils.h"
#include "audio_gate.h"
#include "lifecycle_gate.h"
#include "scoped_timer.h"

#include <mutex>
#include <atomic>
#include <vector>
#include <fstream>
#include <sstream>
#include <chrono>

#if ONNX_AVAILABLE
// ONNX Runtime C API
// Headers should be in include path set by CMakeLists.txt
// If not found, download from: https://github.com/microsoft/onnxruntime/releases
// Extract to: cpp/onnx/include/onnxruntime/
// Try multiple include paths
#if __has_include("onnxruntime/onnxruntime_c_api.h")
    #include "onnxruntime/onnxruntime_c_api.h"
#elif __has_include("onnxruntime_c_api.h")
    #include "onnxruntime_c_api.h"
#else
    #error "ONNX Runtime headers not found. Please download from https://github.com/microsoft/onnxruntime/releases and extract to cpp/onnx/include/"
#endif
#endif

namespace stt {

/**
 * ONNX Model Structure
 * 
 * ONNX STT models can have different structures:
 * 1. Single model.onnx file
 * 2. Encoder + Decoder + Vocab (Whisper-style)
 * 3. Single model + vocab (Silero-style)
 * 
 * This implementation detects and handles all structures.
 */

#if ONNX_AVAILABLE

struct OnnxModelPaths {
    std::string encoderPath;    // encoder.onnx or model.onnx
    std::string decoderPath;    // decoder.onnx (optional)
    std::string vocabPath;      // tokens.txt, vocab.txt, etc.
    std::string configPath;     // config.json (optional)
    bool isEncoderDecoder = false;
    bool hasVocab = false;
};

/**
 * Detect ONNX model structure from path.
 * Path can be:
 * - Direct .onnx file
 * - Directory containing model files
 */
struct OnnxEngine::Impl {
    EngineConfig config;
    OnnxModelPaths modelPaths;
    std::vector<std::string> vocab;
    
    std::atomic<bool> initialized{false};
    std::string lastError;
    mutable std::mutex errorMutex;
    
    mutable concurrency::LifecycleGate lifecycle;
    mutable std::mutex operationMutex;
    std::mutex transitionMutex;
    IEngine* parentEngine = nullptr;
    
    std::vector<float> buffer;
    int64_t totalSamples = 0;
    std::vector<TranscriptSegment> segments;
    
    // ONNX Runtime handles
    const OrtApi* api = nullptr;
    OrtEnv* env = nullptr;
    OrtSessionOptions* sessionOptions = nullptr;
    OrtSession* encoderSession = nullptr;
    OrtSession* decoderSession = nullptr;
    OrtMemoryInfo* memoryInfo = nullptr;
    
    void setError(const std::string& e) {
        std::lock_guard<std::mutex> lock(errorMutex);
        lastError = e;
        SET_ERROR(e);
        LOG_E(TAG_ONNX, "%s", e.c_str());
    }
    
    std::string getError() const {
        std::lock_guard<std::mutex> lock(errorMutex);
        return lastError;
    }
    
    bool shouldAbort() const {
        return lifecycle.isClosing();
    }
    
    bool isProcessing() const {
        return lifecycle.activeCount() > 0;
    }
    
    // RAII guard for processing state
    class ProcessingGuard {
        concurrency::LifecycleGate::Operation operation_;
        std::unique_lock<std::mutex> serialization_;
    public:
        explicit ProcessingGuard(Impl* impl)
            : operation_(impl->lifecycle.tryAcquire()),
              serialization_(impl->operationMutex, std::defer_lock) {
            if (operation_) {
                serialization_.lock();
                if (impl->lifecycle.isClosing() ||
                    (impl->parentEngine && impl->parentEngine->isShuttingDown())) {
                    serialization_.unlock();
                    operation_ = {};
                }
            }
        }
        bool isActive() const { return static_cast<bool>(operation_); }
        ProcessingGuard(const ProcessingGuard&) = delete;
        ProcessingGuard& operator=(const ProcessingGuard&) = delete;
    };
    
    bool initOnnxRuntime() {
        api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
        if (!api) {
            setError("Failed to get ONNX Runtime API");
            return false;
        }
        
        OrtStatus* status = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "stt_onnx", &env);
        if (status) {
            setError(std::string("CreateEnv failed: ") + api->GetErrorMessage(status));
            api->ReleaseStatus(status);
            return false;
        }
        
        status = api->CreateSessionOptions(&sessionOptions);
        if (status) {
            setError(std::string("CreateSessionOptions failed: ") + api->GetErrorMessage(status));
            api->ReleaseStatus(status);
            return false;
        }
        
        // Configure for mobile
        status = api->SetSessionGraphOptimizationLevel(sessionOptions, ORT_ENABLE_ALL);
        if (status) {
            setError(std::string("SetSessionGraphOptimizationLevel failed: ") +
                     api->GetErrorMessage(status));
            api->ReleaseStatus(status);
            return false;
        }
        status = api->SetIntraOpNumThreads(sessionOptions, config.numThreads);
        if (status) {
            setError(std::string("SetIntraOpNumThreads failed: ") +
                     api->GetErrorMessage(status));
            api->ReleaseStatus(status);
            return false;
        }
        
        status = api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memoryInfo);
        if (status) {
            setError(std::string("CreateCpuMemoryInfo failed: ") + api->GetErrorMessage(status));
            api->ReleaseStatus(status);
            return false;
        }
        
        return true;
    }
    
    bool loadModel(const std::string& modelPath, OrtSession** session) {
        if (!api || !env || !sessionOptions) {
            setError("ONNX Runtime not initialized");
            return false;
        }
        
        OrtStatus* status = api->CreateSession(env, modelPath.c_str(), sessionOptions, session);
        if (status) {
            setError(std::string("CreateSession failed for ") + modelPath + ": " + api->GetErrorMessage(status));
            api->ReleaseStatus(status);
            return false;
        }
        
        LOG_I(TAG_ONNX, "Loaded ONNX model: %s", modelPath.c_str());
        return true;
    }
    
    bool closeAndDrain() {
        if (lifecycle.closeAndWait(std::chrono::seconds(5))) return true;
        setError("Timed out draining ONNX operations; engine left closed with resources intact");
        LOG_E(TAG_ONNX, "Release timed out with %zu operation(s); refusing unsafe cleanup",
              lifecycle.activeCount());
        return false;
    }

    void clearResources() {
        if (api) {
            if (encoderSession) { api->ReleaseSession(encoderSession); encoderSession = nullptr; }
            if (decoderSession) { api->ReleaseSession(decoderSession); decoderSession = nullptr; }
            if (sessionOptions) { api->ReleaseSessionOptions(sessionOptions); sessionOptions = nullptr; }
            if (memoryInfo) { api->ReleaseMemoryInfo(memoryInfo); memoryInfo = nullptr; }
            if (env) { api->ReleaseEnv(env); env = nullptr; }
        }
        api = nullptr;
        buffer.clear();
        segments.clear();
        vocab.clear();
        modelPaths = {};
        initialized = false;
        totalSamples = 0;
    }

    bool reopenAfterCleanup() {
        if (lifecycle.reopen()) return true;
        setError("ONNX lifecycle gate could not reopen after cleanup");
        return false;
    }

    bool doRelease() {
        std::lock_guard<std::mutex> transition(transitionMutex);
        if (!closeAndDrain()) {
            LOG_W(TAG_ONNX, "Waiting for remaining operations before release returns");
            lifecycle.closeAndWait();
        }
        clearResources();
        if (!reopenAfterCleanup()) return false;
        LOG_I(TAG_ONNX, "Engine released safely");
        return true;
    }

    void doFinalRelease() {
        std::lock_guard<std::mutex> transition(transitionMutex);
        lifecycle.closeAndWait();
        clearResources();
    }

    void resetState() {
        buffer.clear();
        segments.clear();
        totalSamples = 0;
    }

    bool safeReset() {
        auto pause = lifecycle.tryPause();
        if (!pause) return false;
        std::lock_guard<std::mutex> operation(operationMutex);
        if (parentEngine) parentEngine->clearCancellation();
        resetState();
        return true;
    }
    
    /**
     * Run inference on audio buffer.
     * This is a placeholder - actual implementation depends on model architecture.
     */
    std::string runInference(const float* samples, int count) {
        if (shouldAbort() ||
            (parentEngine && parentEngine->isShuttingDown()) ||
            (parentEngine && parentEngine->isCancelled())) {
            return "";
        }
        if (!encoderSession) {
            setError("Encoder session not loaded");
            return "";
        }
        
        // TODO: Implement actual ONNX inference
        // This requires:
        // 1. Audio preprocessing (mel spectrogram for Whisper)
        // 2. Running encoder
        // 3. Running decoder (autoregressive token generation)
        // 4. Token to text conversion using vocab
        
        // For now, return error indicating implementation needed
        setError("ONNX inference not yet implemented - model loaded but inference requires model-specific code");
        
        return "";
    }
};

OnnxEngine::OnnxEngine() : impl_(std::make_unique<Impl>()) {
    impl_->parentEngine = this;
}
OnnxEngine::~OnnxEngine() { impl_->doFinalRelease(); }

bool OnnxEngine::initialize(const EngineConfig& config) {
    std::lock_guard<std::mutex> transition(impl_->transitionMutex);
    if (isShuttingDown()) return false;
    if (!impl_->closeAndDrain()) return false;
    impl_->clearResources();
    clearCancellation();
    impl_->config = config;

    // HONEST GUARD: the runInference() path is a placeholder that requires
    // model-specific preprocessing / autoregressive decoding and token->text
    // conversion. Refuse init so callers get an immediate, truthful failure
    // instead of silent empty transcripts. Whisper / Vosk remain the
    // supported STT engines; the Marian ONNX translation runtime lives in
    // marian/ (separate module, unrelated to this STT engine).
    impl_->setError(
        "ONNX STT not implemented: inference requires model-specific "
        "preprocessing and decoding. Use Whisper or Vosk.");
    (void)impl_->reopenAfterCleanup();
    return false;
}

// Scaffolding below is kept for future re-enablement once real inference lands.

int OnnxEngine::pushAudio(const int16_t* samples, int count, int sampleRate) {
    std::vector<float> f(count);
    AudioUtils::int16ToFloat(samples, f.data(), count);
    return pushAudioFloat(f.data(), count, sampleRate);
}

int OnnxEngine::pushAudioFloat(const float* samples, int count, int sampleRate) {
    Impl::ProcessingGuard guard(impl_.get());
    if (!guard.isActive()) return -1;
    if (!impl_->initialized) { impl_->setError("Not initialized"); return -1; }
    if (isCancelled()) return -1;
    
    if (sampleRate != impl_->config.sampleRate) {
        auto resampled = AudioUtils::resample(samples, count, sampleRate, impl_->config.sampleRate);
        impl_->buffer.insert(impl_->buffer.end(), resampled.begin(), resampled.end());
        impl_->totalSamples += resampled.size();
    } else {
        impl_->buffer.insert(impl_->buffer.end(), samples, samples + count);
        impl_->totalSamples += count;
    }
    
    return 0;
}

std::string OnnxEngine::getPartial() {
    Impl::ProcessingGuard guard(impl_.get());
    if (!guard.isActive()) return "";
    if (!impl_->initialized) return "";
    if (isCancelled()) return R"({"partial":"","cancelled":true})";
    
    // Run partial inference if we have enough audio
    if (impl_->buffer.size() >= static_cast<size_t>(impl_->config.sampleRate)) {
        return impl_->runInference(impl_->buffer.data(), impl_->buffer.size());
    }
    
    return "";
}

std::string OnnxEngine::finalize() {
    Impl::ProcessingGuard guard(impl_.get());
    if (!guard.isActive()) return R"({"cancelled": true, "text": ""})";
    if (!impl_->initialized) { impl_->setError("Not initialized"); return ""; }
    if (isCancelled()) return R"({"cancelled": true, "text": ""})";
    
    auto start = std::chrono::steady_clock::now();
    
    std::string text;
    if (!impl_->buffer.empty()) {
        text = impl_->runInference(impl_->buffer.data(), impl_->buffer.size());
    }
    
    auto end = std::chrono::steady_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    
    return JsonUtils::buildTranscriptJson(text, impl_->segments, impl_->config.language, ms);
}

void OnnxEngine::reset() {
    if (!impl_->safeReset()) {
        LOG_W(TAG_ONNX, "reset() rejected while engine is busy or closing");
        return;
    }
}

void OnnxEngine::release() { impl_->doRelease(); }

std::string OnnxEngine::transcribeBatch(const int16_t* samples, int count, int sampleRate) {
    std::vector<float> f(count);
    AudioUtils::int16ToFloat(samples, f.data(), count);
    return transcribeBatchFloat(f.data(), count, sampleRate);
}

std::string OnnxEngine::transcribeBatchFloat(const float* samples, int count, int sampleRate) {
    Impl::ProcessingGuard guard(impl_.get());
    if (!guard.isActive()) return R"({"cancelled": true, "text": ""})";
    if (!impl_->initialized) { impl_->setError("Not initialized"); return ""; }
    
    // Check cancellation before starting
    if (isCancelled()) {
        LOG_I(TAG_ONNX, "Cancelled before batch transcription");
        return R"({"cancelled": true, "text": ""})";
    }

    impl_->resetState();
    
    auto start = std::chrono::steady_clock::now();
    
    std::vector<float> processBuffer;
    if (sampleRate != impl_->config.sampleRate) {
        processBuffer = AudioUtils::resample(samples, count, sampleRate, impl_->config.sampleRate);
    } else {
        processBuffer.assign(samples, samples + count);
    }
    
    // Check cancellation after resampling
    if (isCancelled()) {
        LOG_I(TAG_ONNX, "Cancelled after resampling");
        return R"({"cancelled": true, "text": ""})";
    }
    
    std::string text = impl_->runInference(processBuffer.data(), processBuffer.size());
    
    auto end = std::chrono::steady_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    
    return JsonUtils::buildTranscriptJson(text, impl_->segments, impl_->config.language, ms);
}

bool OnnxEngine::isInitialized() const { return impl_->initialized; }
bool OnnxEngine::isProcessing() const { return impl_->isProcessing(); }
std::string OnnxEngine::getLastError() const { return impl_->getError(); }
int OnnxEngine::getModelType() const {
    Impl::ProcessingGuard guard(impl_.get());
    return guard.isActive() && impl_->modelPaths.isEncoderDecoder ? 1 : 0;
}

EngineCapability OnnxEngine::getCapabilities() const {
    return EngineCapability::BATCH_TRANSCRIPTION |
           EngineCapability::STREAMING_PUSH |
           EngineCapability::SEGMENT_TIMESTAMPS;
}

bool OnnxEngine::isAvailable() { return true; }

#else // !ONNX_AVAILABLE

struct OnnxEngine::Impl { std::string lastError = "ONNX Runtime not compiled"; };
OnnxEngine::OnnxEngine() : impl_(std::make_unique<Impl>()) {}
OnnxEngine::~OnnxEngine() = default;
bool OnnxEngine::initialize(const EngineConfig&) { SET_ERROR(impl_->lastError); return false; }
int OnnxEngine::pushAudio(const int16_t*, int, int) { return -1; }
int OnnxEngine::pushAudioFloat(const float*, int, int) { return -1; }
std::string OnnxEngine::getPartial() { return ""; }
std::string OnnxEngine::finalize() { return ""; }
void OnnxEngine::reset() {}
void OnnxEngine::release() {}
std::string OnnxEngine::transcribeBatch(const int16_t*, int, int) { return ""; }
std::string OnnxEngine::transcribeBatchFloat(const float*, int, int) { return ""; }
bool OnnxEngine::isInitialized() const { return false; }
bool OnnxEngine::isProcessing() const { return false; }
std::string OnnxEngine::getLastError() const { return impl_->lastError; }
int OnnxEngine::getModelType() const { return -1; }
EngineCapability OnnxEngine::getCapabilities() const { return EngineCapability::NONE; }
bool OnnxEngine::isAvailable() { return false; }

#endif

} // namespace stt
