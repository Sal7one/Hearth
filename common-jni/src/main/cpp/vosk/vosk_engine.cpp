#include "vosk_engine.h"
#include "logging.h"
#include "error_codes.h"
#include "audio_utils.h"
#include "json_utils.h"
#include "audio_gate.h"
#include "lifecycle_gate.h"
#include "model_integrity.h"
#include "scoped_timer.h"

#include <algorithm>
#include <mutex>
#include <atomic>
#include <chrono>

#if VOSK_AVAILABLE
#include "vosk_api.h"
#endif

namespace stt {

#if VOSK_AVAILABLE

struct VoskEngine::Impl {
    VoskModel* model = nullptr;
    VoskRecognizer* recognizer = nullptr;
    EngineConfig config;
    std::atomic<bool> initialized{false};
    std::string lastError;
    mutable std::mutex errorMutex;
    int64_t totalSamples = 0;
    
    mutable concurrency::LifecycleGate lifecycle;
    mutable std::mutex operationMutex;
    std::mutex transitionMutex;
    IEngine* parentEngine = nullptr;
    
    // Voice Activity Detection - skip silent segments
    // Voice Activity Detection state retained for the batch file pipeline;
    // live pushes are no longer gated (see pushAudio*).
    AudioGate audioGate;
    bool vadEnabled = true;
    int silentFramesSkipped = 0;
    
    // Segments from finalize() call (NOTE: NOT accumulated during streaming - 
    // each complete utterance from Vosk is stored here)
    std::vector<TranscriptSegment> segments;
    int64_t lastSegmentEndMs = 0;  // Track position for segment timing
    
    void setError(const std::string& e) {
        std::lock_guard<std::mutex> lock(errorMutex);
        lastError = e;
        SET_ERROR(e);
    }
    
    std::string getError() const {
        std::lock_guard<std::mutex> lock(errorMutex);
        return lastError;
    }
    
    bool shouldAbort() const {
        return lifecycle.isClosing();
    }
    
    // Check if cancelled OR release requested
    bool checkCancelled() const {
        if (parentEngine && parentEngine->isShuttingDown()) return true;
        if (parentEngine && parentEngine->isCancelled()) return true;
        return shouldAbort();
    }
    
    bool isProcessing() const {
        return lifecycle.activeCount() > 0;
    }
    
    // RAII guard for processing state (refcount-based)
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
        bool acquired() const { return static_cast<bool>(operation_); }
        bool isActive() const { return acquired(); }
        ProcessingGuard(const ProcessingGuard&) = delete;
        ProcessingGuard& operator=(const ProcessingGuard&) = delete;
    };

    bool closeAndDrain() {
        if (lifecycle.closeAndWait(std::chrono::seconds(5))) return true;
        setError("Timed out draining Vosk operations; engine left closed with resources intact");
        LOG_E(TAG_VOSK, "Release timed out with %zu operation(s); refusing unsafe cleanup",
              lifecycle.activeCount());
        return false;
    }

    void clearResources() {
        if (recognizer) { voskApi().vosk_recognizer_free(recognizer); recognizer = nullptr; }
        if (model) { voskApi().vosk_model_free(model); model = nullptr; }
        initialized.store(false, std::memory_order_release);
        totalSamples = 0;
        segments.clear();
        lastSegmentEndMs = 0;
        silentFramesSkipped = 0;
        audioGate.reset();
    }

    bool reopenAfterCleanup() {
        if (lifecycle.reopen()) return true;
        setError("Vosk lifecycle gate could not reopen after cleanup");
        return false;
    }

    bool doRelease() {
        std::lock_guard<std::mutex> transition(transitionMutex);
        if (!closeAndDrain()) {
            LOG_W(TAG_VOSK, "Waiting for remaining operations before release returns");
            lifecycle.closeAndWait();
        }
        clearResources();
        if (!reopenAfterCleanup()) return false;
        LOG_I(TAG_VOSK, "Engine released safely");
        return true;
    }

    void doFinalRelease() {
        std::lock_guard<std::mutex> transition(transitionMutex);
        lifecycle.closeAndWait();
        clearResources();
    }

    void resetState() {
        if (recognizer) voskApi().vosk_recognizer_reset(recognizer);
        totalSamples = 0;
        segments.clear();
        lastSegmentEndMs = 0;
        silentFramesSkipped = 0;
        audioGate.reset();
    }

    bool safeReset() {
        auto pause = lifecycle.tryPause();
        if (!pause) return false;
        std::lock_guard<std::mutex> operation(operationMutex);
        if (parentEngine) parentEngine->clearCancellation();
        resetState();
        return true;
    }
    
    // Parse Vosk's partial result and extract text
    std::string parsePartialText(const char* json) {
        if (!json) return "";
        return JsonUtils::getString(json, "partial", "");
    }
    
    // Parse Vosk's final result and create segment
    void parseAndAccumulateResult(const char* json) {
        if (!json) return;

        std::string text = JsonUtils::getString(json, "text", "");
        if (text.empty()) return;

        // Calculate timing based on samples processed
        int64_t durationMs = (totalSamples * 1000) / config.sampleRate;

        TranscriptSegment seg;
        seg.text = text;
        seg.startMs = lastSegmentEndMs;
        seg.endMs = durationMs;
        seg.confidence = 0.9f;  // Vosk doesn't provide confidence, use reasonable default
        seg.language = config.language;

        segments.push_back(seg);
        lastSegmentEndMs = durationMs;
    }

    // Batch feed in bounded chunks: a single whole-file accept_waveform drops
    // every completed utterance except the last (Vosk resets its buffer at
    // each boundary unless vosk_recognizer_result is consumed), and would
    // make cancellation latency unbounded. Returns false when cancelled.
    bool feedBatchInt16(const int16_t* samples, int count) {
        const int chunk = std::max(1, config.sampleRate / 2);  // ~0.5s
        for (int offset = 0; offset < count; offset += chunk) {
            const int n = std::min(chunk, count - offset);
            if (voskApi().vosk_recognizer_accept_waveform_s(recognizer, samples + offset, n) == 1) {
                parseAndAccumulateResult(voskApi().vosk_recognizer_result(recognizer));
            }
            if (checkCancelled()) return false;
        }
        return true;
    }

    bool feedBatchFloat(const float* samples, int count) {
        const int chunk = std::max(1, config.sampleRate / 2);  // ~0.5s
        for (int offset = 0; offset < count; offset += chunk) {
            const int n = std::min(chunk, count - offset);
            if (voskApi().vosk_recognizer_accept_waveform_f(recognizer, samples + offset, n) == 1) {
                parseAndAccumulateResult(voskApi().vosk_recognizer_result(recognizer));
            }
            if (checkCancelled()) return false;
        }
        return true;
    }
};

VoskEngine::VoskEngine() : impl_(std::make_unique<Impl>()) {
    impl_->parentEngine = this;
}

VoskEngine::~VoskEngine() { impl_->doFinalRelease(); }

bool VoskEngine::initialize(const EngineConfig& config) {
    std::lock_guard<std::mutex> transition(impl_->transitionMutex);
    if (isShuttingDown()) return false;
    if (!impl_->closeAndDrain()) return false;
    impl_->clearResources();
    clearCancellation();

    const auto failInitialization = [this](const std::string& error) {
        impl_->setError(error);
        impl_->clearResources();
        (void)impl_->reopenAfterCleanup();
        return false;
    };

    try { (void)voskApi(); }
    catch (const std::exception& error) { return failInitialization(error.what()); }

    impl_->config = config;
    
    if (config.modelPath.empty()) return failInitialization("Model path empty");
    if (config.modelSha256.empty()) {
        return failInitialization("Verified model SHA-256 is required");
    }

    const ModelPathDigestResult verified = digestPath(config.modelPath, config.modelSha256);
    if (!verified) {
        return failInitialization(
            std::string("Vosk model verification failed: ") +
            modelIntegrityErrorMessage(verified.error)
        );
    }
    if (verified.kind != ModelPathKind::DIRECTORY_TREE) {
        return failInitialization("Vosk model must be a verified directory tree");
    }
    if (isShuttingDown()) {
        return failInitialization("Engine shutdown requested after model verification");
    }
    
    LOG_I(TAG_VOSK, "Loading: %s", config.modelPath.c_str());
    auto start = std::chrono::steady_clock::now();
    
    VoskModel* newModel = voskApi().vosk_model_new(config.modelPath.c_str());
    if (!newModel) return failInitialization("Failed to load Vosk model");

    VoskRecognizer* newRecognizer =
        voskApi().vosk_recognizer_new(newModel, static_cast<float>(config.sampleRate));
    if (!newRecognizer) {
        voskApi().vosk_model_free(newModel);
        return failInitialization("Failed to create recognizer");
    }
    if (isShuttingDown()) {
        voskApi().vosk_recognizer_free(newRecognizer);
        voskApi().vosk_model_free(newModel);
        return failInitialization("Engine shutdown requested while loading model");
    }
    impl_->model = newModel;
    impl_->recognizer = newRecognizer;
    
    auto end = std::chrono::steady_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    
    impl_->initialized.store(true, std::memory_order_release);
    if (!impl_->reopenAfterCleanup()) {
        impl_->clearResources();
        return false;
    }
    LOG_I(TAG_VOSK, "Loaded in %lldms", static_cast<long long>(ms));
    return true;
}

int VoskEngine::pushAudio(const int16_t* samples, int count, int sampleRate) {

    Impl::ProcessingGuard guard(impl_.get());
    if (!guard.acquired()) return -1;
    if (!impl_->initialized.load(std::memory_order_acquire)) { 
        impl_->setError("Not initialized"); 
        return -1; 
    }
    
    // Check cancellation before doing any work
    if (impl_->checkCancelled()) {
        return -1;
    }

    // No gate here: Vosk's built-in endpointer owns silence handling. The
    // previous AudioGate drop froze live captions exactly like the whisper
    // path — pushes succeeded while the recognizer never saw the audio.
    // If sample rate differs, need resampling
    if (sampleRate != impl_->config.sampleRate) {
        std::vector<float> f(count);
        AudioUtils::int16ToFloat(samples, f.data(), count);
        auto resampled = AudioUtils::resample(f.data(), count, sampleRate, impl_->config.sampleRate);
        
        int result = voskApi().vosk_recognizer_accept_waveform_f(impl_->recognizer,
                                                       resampled.data(), 
                                                       static_cast<int>(resampled.size()));
        impl_->totalSamples += resampled.size();
        
        // If Vosk indicates a complete utterance, accumulate it
        if (result == 1) {
            const char* json = voskApi().vosk_recognizer_result(impl_->recognizer);
            impl_->parseAndAccumulateResult(json);
        }
    } else {
        int result = voskApi().vosk_recognizer_accept_waveform_s(impl_->recognizer, samples, count);
        impl_->totalSamples += count;
        
        if (result == 1) {
            const char* json = voskApi().vosk_recognizer_result(impl_->recognizer);
            impl_->parseAndAccumulateResult(json);
        }
    }
    
    return 0;
}

int VoskEngine::pushAudioFloat(const float* samples, int count, int sampleRate) {

    Impl::ProcessingGuard guard(impl_.get());
    if (!guard.acquired()) return -1;
    if (!impl_->initialized.load(std::memory_order_acquire)) { 
        impl_->setError("Not initialized"); 
        return -1; 
    }
    
    // Check cancellation before doing any work
    if (impl_->checkCancelled()) {
        LOG_I(TAG_VOSK, "Cancelled/released, aborting pushAudioFloat");
        return -1;
    }
    
    // No gate here either — see the int16 push path above.
    // If sample rate differs, need resampling
    if (sampleRate != impl_->config.sampleRate) {
        auto resampled = AudioUtils::resample(samples, count, sampleRate, impl_->config.sampleRate);
        int result = voskApi().vosk_recognizer_accept_waveform_f(impl_->recognizer,
                                                       resampled.data(), 
                                                       static_cast<int>(resampled.size()));
        impl_->totalSamples += resampled.size();
        
        if (result == 1) {
            const char* json = voskApi().vosk_recognizer_result(impl_->recognizer);
            impl_->parseAndAccumulateResult(json);
        }
    } else {
        int result = voskApi().vosk_recognizer_accept_waveform_f(impl_->recognizer, samples, count);
        impl_->totalSamples += count;
        
        if (result == 1) {
            const char* json = voskApi().vosk_recognizer_result(impl_->recognizer);
            impl_->parseAndAccumulateResult(json);
        }
    }
    
    return 0;
}

std::string VoskEngine::getPartial() {
    Impl::ProcessingGuard guard(impl_.get());
    if (!guard.acquired()) return "";
    if (!impl_->initialized.load(std::memory_order_acquire)) return "";
    
    // Check cancellation
    if (impl_->checkCancelled()) {
        return R"({"partial":"","cancelled":true})";
    }
    
    const char* result = voskApi().vosk_recognizer_partial_result(impl_->recognizer);
    if (!result) return "";
    
    // Extract just the partial text for display
    std::string partialText = impl_->parsePartialText(result);
    if (partialText.empty()) return "";
    
    // Build full text including accumulated segments + current partial
    std::string fullText;
    for (const auto& seg : impl_->segments) {
        if (!fullText.empty()) fullText += " ";
        fullText += seg.text;
    }
    if (!fullText.empty() && !partialText.empty()) fullText += " ";
    fullText += partialText;
    
    // Return as JSON for consistency with Whisper partial format.
    return JsonUtils::stringify(JsonValue::object({{"partial", fullText}}));
}

std::string VoskEngine::finalize() {
    Impl::ProcessingGuard guard(impl_.get());
    if (!guard.acquired()) return R"({"cancelled": true, "text": ""})";
    if (!impl_->initialized.load(std::memory_order_acquire)) { 
        impl_->setError("Not initialized"); 
        return ""; 
    }
    
    // Check cancellation
    if (impl_->checkCancelled()) {
        LOG_I(TAG_VOSK, "Cancelled before finalize");
        return R"({"cancelled": true, "text": ""})";
    }
    
    auto start = std::chrono::steady_clock::now();
    
    // Get final result from current utterance
    const char* result = voskApi().vosk_recognizer_final_result(impl_->recognizer);
    impl_->parseAndAccumulateResult(result);
    
    auto end = std::chrono::steady_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    LOG_I(TAG_VOSK, "Finalize took %lldms", static_cast<long long>(ms));
    
    // Build complete text from all accumulated segments
    std::string text;
    for (const auto& seg : impl_->segments) {
        if (!text.empty()) text += " ";
        text += seg.text;
    }
    
    return JsonUtils::buildTranscriptJson(text, impl_->segments, impl_->config.language, ms);
}

void VoskEngine::reset() {
    if (!impl_->safeReset()) {
        LOG_W(TAG_VOSK, "reset() rejected while engine is busy or closing");
        return;
    }
}

void VoskEngine::release() { impl_->doRelease(); }

std::string VoskEngine::transcribeBatch(const int16_t* samples, int count, int sampleRate) {
    PROFILE_SCOPE("VoskEngine::transcribeBatch");

    Impl::ProcessingGuard guard(impl_.get());
    if (!guard.acquired()) return R"({"cancelled": true, "text": ""})";
    if (!impl_->initialized.load(std::memory_order_acquire)) { 
        impl_->setError("Not initialized"); 
        return ""; 
    }
    
    // Check cancellation before starting
    if (impl_->checkCancelled()) {
        LOG_I(TAG_VOSK, "Cancelled before batch transcription");
        return R"({"cancelled": true, "text": ""})";
    }
    
    // Reset internal state (direct call since we hold the guard)
    if (impl_->recognizer) {
        voskApi().vosk_recognizer_reset(impl_->recognizer);
    }
    impl_->totalSamples = 0;
    impl_->segments.clear();
    impl_->lastSegmentEndMs = 0;
    
    // If sample rate differs, need resampling
    if (sampleRate != impl_->config.sampleRate) {
        std::vector<float> f(count);
        AudioUtils::int16ToFloat(samples, f.data(), count);
        auto resampled = AudioUtils::resample(f.data(), count, sampleRate, impl_->config.sampleRate);
        impl_->totalSamples = resampled.size();
        if (!impl_->feedBatchFloat(resampled.data(), static_cast<int>(resampled.size()))) {
            return R"({"cancelled": true, "text": ""})";
        }
    } else {
        impl_->totalSamples = count;
        if (!impl_->feedBatchInt16(samples, count)) {
            return R"({"cancelled": true, "text": ""})";
        }
    }

    // Get final result (inline since we hold the guard)
    auto start = std::chrono::steady_clock::now();
    const char* result = voskApi().vosk_recognizer_final_result(impl_->recognizer);
    impl_->parseAndAccumulateResult(result);
    auto end = std::chrono::steady_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();

    std::string text;
    for (const auto& seg : impl_->segments) {
        if (!text.empty()) text += " ";
        text += seg.text;
    }

    return JsonUtils::buildTranscriptJson(text, impl_->segments, impl_->config.language, ms);
}

std::string VoskEngine::transcribeBatchFloat(const float* samples, int count, int sampleRate) {
    PROFILE_SCOPE("VoskEngine::transcribeBatchFloat");

    Impl::ProcessingGuard guard(impl_.get());
    if (!guard.acquired()) return R"({"cancelled": true, "text": ""})";
    if (!impl_->initialized.load(std::memory_order_acquire)) { 
        impl_->setError("Not initialized"); 
        return ""; 
    }
    
    // Check cancellation before starting
    if (impl_->checkCancelled()) {
        LOG_I(TAG_VOSK, "Cancelled before batch transcription (float)");
        return R"({"cancelled": true, "text": ""})";
    }
    
    // Reset internal state (direct call since we hold the guard)
    if (impl_->recognizer) {
        voskApi().vosk_recognizer_reset(impl_->recognizer);
    }
    impl_->totalSamples = 0;
    impl_->segments.clear();
    impl_->lastSegmentEndMs = 0;
    
    if (sampleRate != impl_->config.sampleRate) {
        auto resampled = AudioUtils::resample(samples, count, sampleRate, impl_->config.sampleRate);
        impl_->totalSamples = resampled.size();
        if (!impl_->feedBatchFloat(resampled.data(), static_cast<int>(resampled.size()))) {
            return R"({"cancelled": true, "text": ""})";
        }
    } else {
        impl_->totalSamples = count;
        if (!impl_->feedBatchFloat(samples, count)) {
            return R"({"cancelled": true, "text": ""})";
        }
    }

    // Get final result (inline since we hold the guard)
    auto start = std::chrono::steady_clock::now();
    const char* result = voskApi().vosk_recognizer_final_result(impl_->recognizer);
    impl_->parseAndAccumulateResult(result);
    auto end = std::chrono::steady_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();

    std::string text;
    for (const auto& seg : impl_->segments) {
        if (!text.empty()) text += " ";
        text += seg.text;
    }

    return JsonUtils::buildTranscriptJson(text, impl_->segments, impl_->config.language, ms);
}

bool VoskEngine::isInitialized() const { 
    return impl_->initialized.load(std::memory_order_acquire); 
}

bool VoskEngine::isProcessing() const { 
    return impl_->isProcessing(); 
}

std::string VoskEngine::getLastError() const { 
    return impl_->getError(); 
}

int VoskEngine::getModelType() const { 
    return -1;  // Vosk doesn't expose model type
}

bool VoskEngine::isAvailable() { 
    return true; 
}

EngineCapability VoskEngine::getCapabilities() const {
    return EngineCapability::BATCH_TRANSCRIPTION |
           EngineCapability::STREAMING_PUSH |
           EngineCapability::PARTIAL_RESULTS |
           EngineCapability::SEGMENT_TIMESTAMPS;
    // Note: No LANGUAGE_DETECTION, TRANSLATION, or WORD_TIMESTAMPS
}

#else // !VOSK_AVAILABLE

struct VoskEngine::Impl { std::string lastError = "Vosk not compiled"; };
VoskEngine::VoskEngine() : impl_(std::make_unique<Impl>()) {}
VoskEngine::~VoskEngine() = default;
bool VoskEngine::initialize(const EngineConfig&) { SET_ERROR(impl_->lastError); return false; }
int VoskEngine::pushAudio(const int16_t*, int, int) { return -1; }
int VoskEngine::pushAudioFloat(const float*, int, int) { return -1; }
std::string VoskEngine::getPartial() { return ""; }
std::string VoskEngine::finalize() { return ""; }
void VoskEngine::reset() {}
void VoskEngine::release() {}
std::string VoskEngine::transcribeBatch(const int16_t*, int, int) { return ""; }
std::string VoskEngine::transcribeBatchFloat(const float*, int, int) { return ""; }
bool VoskEngine::isInitialized() const { return false; }
bool VoskEngine::isProcessing() const { return false; }
std::string VoskEngine::getLastError() const { return impl_->lastError; }
int VoskEngine::getModelType() const { return -1; }
bool VoskEngine::isAvailable() { return false; }
EngineCapability VoskEngine::getCapabilities() const { return EngineCapability::NONE; }

#endif

} // namespace stt
