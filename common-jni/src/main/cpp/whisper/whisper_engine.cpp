#include "whisper_engine.h"
#include "logging.h"
#include "error_codes.h"
#include "audio_utils.h"
#include "json_utils.h"
#include "ring_buffer.h"
#include "audio_gate.h"
#include "buffer_pool.h"
#include "lifecycle_gate.h"
#include "scoped_timer.h"
#include "verified_model_file.h"
#include "cancel_token.h"
#include "pipe_progress.h"
#include "utf8_utils.h"
#include "inference_stop_signal.h"

#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <vector>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <limits>

// Only include whisper headers if enabled via CMake
#if defined(WHISPER_AVAILABLE) && WHISPER_AVAILABLE
#include "whisper.h"
#endif

namespace stt {

    constexpr int WHISPER_RATE = 16000;
    constexpr int DEFAULT_CHUNK_MS = 30000;
    constexpr int DEFAULT_OVERLAP_MS = 1000;

#if defined(WHISPER_AVAILABLE) && WHISPER_AVAILABLE

    namespace {
        // JNI calls may arrive on different dispatcher workers, so scratch is
        // per-thread rather than shared by an engine. It grows to the largest
        // observed streaming chunk and performs no further hot-path allocation.
        thread_local std::vector<float> t_pcmFloatScratch;
        thread_local std::vector<float> t_resampleScratch;

        void resizeScratch(std::vector<float>& scratch, std::size_t size) {
            if (scratch.size() < size) scratch.resize(size);
        }
    }

    void whisper_log_callback(enum ggml_log_level level, const char * text, void * user_data) {
        (void)user_data;
        if (level == GGML_LOG_LEVEL_ERROR) LOG_E(TAG_WHISPER, "CORE: %s", text);
        else if (level == GGML_LOG_LEVEL_WARN) LOG_W(TAG_WHISPER, "CORE: %s", text);
#ifdef VERBOSE_LOGGING
        else LOG_D(TAG_WHISPER, "CORE: %s", text);
#endif
    }

    struct WhisperEngine::Impl {
        whisper_context* ctx = nullptr;
        EngineConfig config;
        std::atomic<bool> initialized{false};
        std::string lastError;
        mutable std::mutex errorMutex;

        // The lifecycle gate owns admission and draining; the operation mutex
        // serializes whisper_context and streaming-state access.
        mutable concurrency::LifecycleGate lifecycle;
        mutable std::mutex operationMutex;
        std::mutex transitionMutex;

        // Audio buffer - using ring buffer for O(1) operations
        AudioRingBuffer buffer;

        // Voice Activity Detection - skip silent segments
        AudioGate audioGate;
        bool vadEnabled = true;
        int silentFramesSkipped = 0;

        // Segments from last finalize() call (NOT accumulated during streaming)
        std::vector<TranscriptSegment> segments;
        std::string detectedLang;
        int64_t totalSamples = 0;

        // Streaming accumulation. The background worker promotes audio regions
        // with partial=true (which deliberately do not append to `segments`).
        // Promoted segments are captured separately with timestamps shifted
        // onto the session timeline so finalize() returns the complete
        // transcript.
        std::vector<TranscriptSegment> streamedSegments;
        int64_t streamedBaseSamples = 0;

        // Chunk parameters (configurable, clamped to whisper-sane minimums)
        int chunkSamples = WHISPER_RATE * 30;  // 30 seconds default
        int overlapSamples = WHISPER_RATE;     // 1 second overlap

        // Temporary buffer for normalization (avoids modifying input)
        std::vector<float> inferenceBuffer;

        // Reused streaming-path buffers: rolling-promote slice, partial tail
        // window snapshot and finalize tail. resize() is allocation-free once
        // sized.
        std::vector<float> chunkScratch;
        std::vector<float> partialScratch;
        std::vector<float> finalizeScratch;

        // =========================================================================
        // Async streaming worker
        //
        // Architecture: pushes NEVER run inference. They append to the ring
        // buffer in O(chunk) under streamMutex and wake the worker. A single
        // background thread owns every whisper_* call (the context is not
        // thread-safe), decides the cheapest useful inference:
        //
        //   - partial:  transcribe the tail window (<= partialMaxSamples)
        //               at most once per partialIntervalMs while voice is
        //               active; getPartial() returns the published result
        //               without touching the model.
        //   - promote:  when voice ended (silence grace) or the pending audio
        //               outgrew the rolling window, transcribe ONE slice
        //               (<= promoteSamples) exactly once, append its segments
        //               to the session timeline and discard it (keeping the
        //               overlap). No audio region is ever re-transcribed by a
        //               second promote.
        //
        // Language auto-detection runs ONCE per session (it costs a full
        // encoder pass); later calls reuse the cached language.
        //
        // whisper.cpp pads every input mel to 30 s, so each call pays a fixed
        // encoder cost regardless of window size; the architecture therefore
        // minimizes the NUMBER of calls rather than the window size.
        // =========================================================================
        std::thread workerThread;
        std::atomic<bool> workerStop{false};
        concurrency::InferenceStopSignal workerDecodeAbort;

        std::mutex streamMutex;              // buffer + timeline + voice markers
        std::condition_variable streamCv;

        std::mutex partialMutex;             // publishedPartial + language cache
        std::string publishedPartial;
        std::string languageCache;

        std::chrono::steady_clock::time_point lastVoiceAt{};
        std::chrono::steady_clock::time_point lastPartialAt{};
        std::chrono::steady_clock::time_point lastPromoteAt{};
        size_t pendingAtLastPartial = 0;   // buffer size when the last partial decoded
        int64_t droppedSamples = 0;

        // Effective streaming geometry (samples at 16 kHz).
        int partialMinSamples = WHISPER_RATE;         // 1.0 s before partials
        int partialMaxSamples = WHISPER_RATE * 6;     // 6 s tail window (latency/quality)
        int promoteSamples = WHISPER_RATE * 8;        // 8 s rolling slice
        int silenceGraceMs = 900;
        int partialIntervalMs = 700;
        static constexpr int64_t kMaxPendingSamples = WHISPER_RATE * 60;

        // ---------------------------------------------------------------------
        // Aggregate performance counters (instrumentation only; no behavior
        // change). A periodic summary is emitted every kAggregateWindow
        // completed inferences so live streaming cost is visible in the log
        // without any per-call overhead beyond two cheap integer updates.
        // ---------------------------------------------------------------------
        mutable std::mutex aggMutex;
        int64_t aggInferenceTotalUs = 0;
        int64_t aggInferenceMinUs = std::numeric_limits<int64_t>::max();
        int64_t aggInferenceMaxUs = 0;
        int aggInferenceCount = 0;
        int64_t aggPushTotalUs = 0;
        int aggPushCount = 0;
        static constexpr int kAggregateWindow = 20;

        void setError(const std::string& e) {
            std::lock_guard<std::mutex> lock(errorMutex);
            lastError = e;
            SET_ERROR(e);
        }

        std::string getError() const {
            std::lock_guard<std::mutex> lock(errorMutex);
            return lastError;
        }

        // Instrumentation: record a completed pushAudioFloat call duration.
        void recordPushUs(int64_t us) {
            std::lock_guard<std::mutex> lock(aggMutex);
            aggPushTotalUs += us;
            ++aggPushCount;
        }

        // Instrumentation: record a completed inference and, every
        // kAggregateWindow inferences, emit a periodic aggregate summary.
        void recordInferenceUs(int64_t us) {
            int count = 0;
            double avgMs = 0.0, minMs = 0.0, maxMs = 0.0, pushAvgUs = 0.0;
            int pushCount = 0;
            {
                std::lock_guard<std::mutex> lock(aggMutex);
                aggInferenceTotalUs += us;
                aggInferenceMinUs = std::min(aggInferenceMinUs, us);
                aggInferenceMaxUs = std::max(aggInferenceMaxUs, us);
                ++aggInferenceCount;
                if (aggInferenceCount < kAggregateWindow) return;
                count = aggInferenceCount;
                avgMs = static_cast<double>(aggInferenceTotalUs) / count / 1000.0;
                minMs = static_cast<double>(aggInferenceMinUs) / 1000.0;
                maxMs = static_cast<double>(aggInferenceMaxUs) / 1000.0;
                pushCount = aggPushCount;
                pushAvgUs = pushCount > 0
                    ? static_cast<double>(aggPushTotalUs) / pushCount
                    : 0.0;
                aggInferenceTotalUs = 0;
                aggInferenceMinUs = std::numeric_limits<int64_t>::max();
                aggInferenceMaxUs = 0;
                aggInferenceCount = 0;
                aggPushTotalUs = 0;
                aggPushCount = 0;
            }
            LOG_I(TAG_WHISPER,
                  "Aggregate over %d inferences: avg=%.2fms min=%.2fms max=%.2fms push_avg=%.2fus (%d pushes)",
                  count, avgMs, minMs, maxMs, pushAvgUs, pushCount);
        }

        // Check if we should abort processing (called during inference)
        bool shouldAbort() const {
            return lifecycle.isClosing();
        }

        // Check if currently processing (for external queries)
        bool isProcessing() const {
            return lifecycle.activeCount() > 0;
        }

        bool closeAndDrain() {
            if (lifecycle.closeAndWait(std::chrono::seconds(5))) return true;
            setError("Timed out draining Whisper operations; engine left closed with resources intact");
            LOG_E(TAG_WHISPER, "Release timed out with %zu operation(s); refusing unsafe cleanup",
                  lifecycle.activeCount());
            return false;
        }

        // Clear/release abort an in-flight decode. Finalize instead lets that
        // decode complete so its promoted segments are included in the final
        // transcript before the worker exits.
        void stopWorker(bool abortInFlight = true) {
            if (abortInFlight) workerDecodeAbort.request();
            workerStop = true;
            streamCv.notify_all();
            if (workerThread.joinable()) workerThread.join();
        }

        void startWorker() {
            if (workerThread.joinable()) return;
            workerDecodeAbort.clear();
            workerStop = false;
            workerThread = std::thread([this]() { workerLoop(); });
        }

        void clearResources() {
            stopWorker();
            if (ctx) {
                whisper_free(ctx);
                ctx = nullptr;
            }
            std::lock_guard<std::mutex> lock(streamMutex);
            buffer.clear();
            segments.clear();
            streamedSegments.clear();
            streamedBaseSamples = 0;
            inferenceBuffer.clear();
            detectedLang.clear();
            initialized = false;
            totalSamples = 0;
            silentFramesSkipped = 0;
            droppedSamples = 0;
            audioGate.reset();
            {
                std::lock_guard<std::mutex> plock(partialMutex);
                publishedPartial.clear();
                languageCache.clear();
            }
        }

        bool reopenAfterCleanup() {
            if (lifecycle.reopen()) return true;
            setError("Whisper lifecycle gate could not reopen after cleanup");
            return false;
        }

        bool doRelease() {
            std::lock_guard<std::mutex> transition(transitionMutex);
            stopWorker();
            if (!closeAndDrain()) {
                LOG_W(TAG_WHISPER, "Waiting for remaining operations before release returns");
                lifecycle.closeAndWait();
            }
            clearResources();
            if (!reopenAfterCleanup()) return false;
            LOG_I(TAG_WHISPER, "Engine released safely");
            return true;
        }

        void doFinalRelease() {
            std::lock_guard<std::mutex> transition(transitionMutex);
            stopWorker();
            lifecycle.closeAndWait();
            clearResources();
        }

        void resetState() {
            {
                std::lock_guard<std::mutex> lock(streamMutex);
                buffer.clear();
                streamedSegments.clear();
                streamedBaseSamples = 0;
                totalSamples = 0;
                silentFramesSkipped = 0;
                droppedSamples = 0;
                lastVoiceAt = {};
                lastPartialAt = {};
                lastPromoteAt = {};
                audioGate.reset();
            }
            segments.clear();
            detectedLang.clear();
            {
                std::lock_guard<std::mutex> plock(partialMutex);
                publishedPartial.clear();
                languageCache.clear();
            }
        }

        bool safeReset() {
            std::lock_guard<std::mutex> transition(transitionMutex);
            if (!initialized) {
                setError("Whisper reset rejected: engine is not initialized");
                return false;
            }
            // Join the streaming worker before pausing: its in-flight inference
            // holds a gate operation, so tryPause() failed during speech and
            // Clear silently kept transcribing the old audio.
            stopWorker();
            {
                auto pause = lifecycle.tryPause();
                if (!pause) {
                    setError("Whisper reset rejected: engine is busy or closing");
                    if (!lifecycle.isClosing()) startWorker();
                    return false;
                }
                std::lock_guard<std::mutex> operation(operationMutex);
                if (parentEngine) parentEngine->clearCancellation();
                resetState();
            }
            // Start after the pause ends: the worker exits when it observes the
            // gate closing, and a temporary pause must not end streaming.
            startWorker();
            return true;
        }

        // =========================================================================
        // Streaming geometry
        // =========================================================================

        void applyStreamingGeometry(int chunkMs, int overlapMs) {
            // Whisper needs multi-second windows: a 200 ms chunk from the
            // caption pipeline would re-encode the 30 s padded mel constantly.
            // Clamp to a sane rolling slice and tail window instead.
            const int effectiveChunkMs =
                std::min(30000, std::max(4000, chunkMs > 0 ? chunkMs : DEFAULT_CHUNK_MS));
            const int effectiveOverlapMs =
                std::min(4000, std::max(0, overlapMs > 0 ? overlapMs : DEFAULT_OVERLAP_MS));
            std::lock_guard<std::mutex> lock(streamMutex);
            chunkSamples = (effectiveChunkMs * WHISPER_RATE) / 1000;
            overlapSamples = (effectiveOverlapMs * WHISPER_RATE) / 1000;
            promoteSamples = chunkSamples;
            partialMaxSamples = std::min(chunkSamples + 2 * WHISPER_RATE, WHISPER_RATE * 12);
            partialMinSamples = WHISPER_RATE;
            LOG_I(TAG_WHISPER,
                  "Streaming geometry: promote=%dms overlap=%dms partialMax=%dms",
                  effectiveChunkMs, effectiveOverlapMs,
                  (partialMaxSamples * 1000) / WHISPER_RATE);
        }

        // =========================================================================
        // Background worker
        // =========================================================================

        void workerLoop() {
            LOG_I(TAG_WHISPER, "Streaming worker started");
            while (true) {
                {
                    std::unique_lock<std::mutex> lock(streamMutex);
                    streamCv.wait_for(lock, std::chrono::milliseconds(200), [this]() {
                        return workerStop.load() || lifecycle.isClosing();
                    });
                }
                if (workerStop.load() || lifecycle.isClosing()) break;
                if (!initialized.load()) continue;

                bool doPromote = false;
                bool doPartial = false;
                size_t count = 0;
                const auto now = std::chrono::steady_clock::now();
                auto promoteDecidedAt = std::chrono::steady_clock::time_point{};
                {
                    std::lock_guard<std::mutex> lock(streamMutex);
                    const size_t pending = buffer.size();
                    if (pending == 0) continue;
                    const bool voiceEnded =
                        now - lastVoiceAt >= std::chrono::milliseconds(silenceGraceMs);
                    // Real speech pending? Both markers default to epoch, so a
                    // fresh session starts with "no speech since last promote".
                    const bool voiceSincePromote = lastVoiceAt > lastPromoteAt;
                    const bool cadenceOk =
                        now - lastPartialAt >= std::chrono::milliseconds(partialIntervalMs);
                    if (voiceEnded) {
                        if (pending >= static_cast<size_t>(partialMinSamples) &&
                            voiceSincePromote) {
                            doPromote = true;
                            count = std::min(pending, static_cast<size_t>(promoteSamples));
                        } else if (!voiceSincePromote &&
                                   pending > static_cast<size_t>(overlapSamples)) {
                            // Only silence since the last promote: drain without
                            // inference so idle audio never costs a whisper pass.
                            const size_t drop =
                                pending - static_cast<size_t>(overlapSamples);
                            buffer.discard_front(drop);
                            streamedBaseSamples += static_cast<int64_t>(drop);
                            continue;
                        }
                    } else if (pending >=
                               static_cast<size_t>(promoteSamples + partialMaxSamples)) {
                        // Long continuous speech: roll the oldest slice forward
                        // so the pending audio stays bounded and each region is
                        // transcribed exactly once.
                        doPromote = true;
                        count = static_cast<size_t>(promoteSamples);
                    } else if (pending >= static_cast<size_t>(partialMinSamples) &&
                               cadenceOk &&
                               pending > pendingAtLastPartial +
                                   static_cast<size_t>(partialMinSamples)) {
                        // A partial may only re-decode when NEW audio arrived
                        // since the last one. Without this, whisper's
                        // context-carryover re-decodes the same 6 s tail
                        // forever and the output oscillates between two
                        // hypotheses (A,B,A,B) — the visible caption flicker.
                        doPartial = true;
                        count = std::min(pending, static_cast<size_t>(partialMaxSamples));
                    }
                    if (doPromote) promoteDecidedAt = now;
                }
                if (!doPromote && !doPartial) continue;

                // Copy the window out of the buffer before releasing the lock:
                // partials use the TAIL, promotes use the HEAD.
                std::vector<float>& scratch = doPartial ? partialScratch : chunkScratch;
                {
                    std::lock_guard<std::mutex> lock(streamMutex);
                    const size_t pending = buffer.size();
                    count = std::min(count, pending);
                    if (count == 0) continue;
                    scratch.resize(count);
                    if (doPartial) {
                        // 'New audio since the last partial' is measured as
                        // buffer growth at decode time.
                        pendingAtLastPartial = pending;
                        const size_t start = pending - count;
                        for (size_t i = 0; i < count; ++i) {
                            scratch[i] = buffer[start + i];
                        }
                    } else {
                        buffer.peek_front(scratch.data(), count);
                    }
                }

                // Inference: serialized with batch/detect calls through the
                // lifecycle operation + operation mutex. Never blocks pushes.
                ProcessingGuard guard(this);
                if (!guard.acquired()) break;
                if (checkCancelled()) break;

                std::vector<TranscriptSegment> captured;
                const std::string text =
                    runInference(scratch.data(), static_cast<int>(scratch.size()),
                                 /*partial=*/true, &captured,
                                 /*streamingParams=*/true);

                {
                    std::lock_guard<std::mutex> lock(streamMutex);
                    if (doPromote) {
                        const int64_t offsetMs =
                            streamedBaseSamples * 1000 / WHISPER_RATE;
                        const size_t overlap =
                            std::min(count, static_cast<size_t>(overlapSamples));
                        const size_t discard = count > overlap ? count - overlap : count;
                        for (auto& seg : captured) {
                            seg.startMs += offsetMs;
                            seg.endMs += offsetMs;
                            // The retained overlap tail is re-transcribed by
                            // the NEXT promote. Drop a re-emitted segment only
                            // when an already-streamed segment covers most of
                            // it (>50% time overlap): a pure "starts inside
                            // the overlap" test also drops words the next
                            // window fails to re-emit at all.
                            bool duplicate = false;
                            const int64_t len = seg.endMs - seg.startMs;
                            if (len > 0) {
                                for (const auto& prev : streamedSegments) {
                                    const int64_t ov =
                                        std::min(prev.endMs, seg.endMs) -
                                        std::max(prev.startMs, seg.startMs);
                                    if (ov > 0 && ov * 2 >= len) {
                                        duplicate = true;
                                        break;
                                    }
                                }
                            }
                            if (duplicate) continue;
                            streamedSegments.push_back(std::move(seg));
                        }
                        buffer.discard_front(discard);
                        streamedBaseSamples += static_cast<int64_t>(discard);
                        // Decision time, not completion time: voice that
                        // arrived while this inference ran counts as "since"
                        // this promote and must not be drained as silence.
                        lastPromoteAt = promoteDecidedAt;
                        std::lock_guard<std::mutex> plock(partialMutex);
                        // Utterance boundary: the controller promotes the last
                        // shown partial when it sees an empty partial.
                        publishedPartial.clear();
                    } else {
                        std::lock_guard<std::mutex> plock(partialMutex);
                        publishedPartial = text;
                        lastPartialAt = std::chrono::steady_clock::now();
                    }

                }
            }
            LOG_I(TAG_WHISPER, "Streaming worker stopped");
        }

        whisper_full_params makeParams(bool isStreaming) {
            // Use GREEDY for speed on Android
            whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
            p.print_realtime = false;
            p.print_progress = false;
            // Decode progress → the attached pipe (inert until a consumer
            // calls setProgressFd). whisper.cpp reports 0..100 per segment.
            p.progress_callback = [](whisper_context*, whisper_state*, int pct, void* user) {
                static_cast<Impl*>(user)->onDecodeProgress(pct);
            };
            p.progress_callback_user_data = this;
            p.print_timestamps = false;
            p.print_special = false;
            // The encoder is the dominant cost (every call pads its mel to
            // 30 s). Modern octa-core phones scale it well beyond 4 threads;
            // keep at least the configured count and allow up to 8 so live
            // partials finish faster while the worker thread stays free.
            p.n_threads = std::min(std::max(config.numThreads, 4), 8);

            // --- LANGUAGE CONFIGURATION ---
            if (config.language == "auto" || config.language.empty()) {
                // Auto-detection costs a full extra encoder pass: run it once
                // per session, then reuse the detected language. The member's
                // buffer stays stable while inference runs: every writer
                // (resetState / clearResources) is serialized behind the
                // operation mutex the inference caller already holds.
                std::lock_guard<std::mutex> lock(partialMutex);
                if (!languageCache.empty()) {
                    p.language = languageCache.c_str();
                    p.detect_language = false;
                } else {
                    p.language = nullptr;
                    p.detect_language = true;
                }
            } else {
                p.language = config.language.c_str();
                p.detect_language = false;
            }

            // Whisper's built-in X→English translation task. Parsed from the
            // config since the beginning but previously dropped here.
            p.translate = config.translateToEnglish;

            // --- STREAMING vs BATCH OPTIMIZATION ---
            p.suppress_blank = config.suppressBlank;

            if (isStreaming) {
                // 🟢 STREAMING MODE: More permissive thresholds for smaller chunks
                p.no_speech_thold = 0.3f;  // More permissive for streaming chunks
                p.entropy_thold = 2.8f;
                p.temperature_inc = 0.0f;
                p.no_context = false;  // Keep context for continuity across chunks
                // Live partials never need token timestamps and must stay
                // bounded in decoder time.
                p.token_timestamps = false;
                p.max_tokens = 96;
            } else {
                // 🟢 BATCH MODE: More aggressive thresholds for full files
                p.no_speech_thold = config.noSpeechThreshold;
                p.entropy_thold = 2.8f;
                p.temperature_inc = 0.2f;
                p.no_context = true;
                p.token_timestamps = config.enableTimestamps;
                p.max_tokens = 0;  // no limit
            }

            p.translate = config.translateToEnglish;
            p.single_segment = false;
            p.abort_callback = isStreaming
                ? static_cast<ggml_abort_callback>([](void* userData) -> bool {
                    return static_cast<Impl*>(userData)->checkCancelled(true);
                })
                : static_cast<ggml_abort_callback>([](void* userData) -> bool {
                    return static_cast<Impl*>(userData)->checkCancelled();
                });
            p.abort_callback_user_data = this;

            return p;
        }

        std::string sanitizeTranscript(const char* rawText) {
            if (!rawText) return "";
            // max_tokens and segment splits can cut a CJK/Arabic character;
            // strict JNI conversion would otherwise end the caption session.
            std::string text = common_jni::text::repairUtf8(rawText);
            size_t start = text.find_first_not_of(" \t\n\r");
            if (start == std::string::npos) return "";
            size_t end = text.find_last_not_of(" \t\n\r");
            text = text.substr(start, end - start + 1);
            if (text == "[BLANK_AUDIO]" || text == "[NOISE]" || text == "[MUSIC]") return "";
            return text;
        }

        float calculateSegmentConfidence(whisper_context* ctx, int segmentIndex) {
            int n_tokens = whisper_full_n_tokens(ctx, segmentIndex);
            if (n_tokens == 0) return 0.0f;
            float totalProb = 0.0f;
            for (int i = 0; i < n_tokens; ++i) {
                whisper_token_data token = whisper_full_get_token_data(ctx, segmentIndex, i);
                if (token.p > 0) totalProb += token.p;
            }
            return totalProb / n_tokens;
        }

        IEngine* parentEngine = nullptr;

        // Cooperative cancellation + progress for the decode loop. The token
        // is requested from WhisperEngine::onCancellationRequested (same
        // moment IEngine::cancelled_ flips); progress stays inert (fd < 0)
        // until a consumer attaches a pipe with setProgressFd().
        CancelToken cancelToken;
        PipeProgress progress;

        void onDecodeProgress(int percent) { progress.report(percent / 100.0F); }

        // Check if we should abort (either cancelled by user OR release requested)
        bool checkCancelled(bool streamingInference = false) const {
            if (workerDecodeAbort.shouldAbort(streamingInference)) return true;
            if (parentEngine && parentEngine->isShuttingDown()) return true;
            if (parentEngine && parentEngine->isCancelled()) return true;
            if (cancelToken.shouldStop()) return true;
            return shouldAbort();  // Also check if release was requested
        }

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

        /**
         * Run inference on a buffer of float samples.
         *
         * THREAD SAFETY:
         * - Caller holds ProcessingGuard for RAII lifecycle management
         * - Checks shouldAbort() periodically for clean shutdown
         * - endProcessing() is guaranteed to be called via RAII
         */
        std::string runInference(
            const float* samples, int count, bool partial,
            std::vector<TranscriptSegment>* streamedCapture = nullptr,
            bool streamingParams = false
        ) {
            if (!ctx || count == 0) return "";

            if (checkCancelled(streamingParams)) {
                LOG_I(TAG_WHISPER, "Cancelled/released before inference started");
                progress.reportCancelled();
                return "";
            }
            progress.reportPreparing();

            bool isAutoLang = (config.language.empty() || config.language == "auto");
            auto start = std::chrono::steady_clock::now();

            // Copy to inference buffer for normalization; this preserves the
            // original audio for context in streaming mode.
            inferenceBuffer.assign(samples, samples + count);
            float* audioData = inferenceBuffer.data();

            // Gain normalization
            float maxVal = 0.0f;
            for (int i = 0; i < count; ++i) {
                float a = std::abs(audioData[i]);
                if (a > maxVal) maxVal = a;
            }

            if (maxVal > 0.0001f && maxVal < 0.5f) {
                float gain = 0.9f / maxVal;
                LOG_I(TAG_WHISPER, "Normalizing: max=%.4f, gain=%.2f", maxVal, gain);
                for (int i = 0; i < count; ++i) {
                    audioData[i] *= gain;
                }
            }

            if (checkCancelled(streamingParams)) {
                LOG_I(TAG_WHISPER, "Cancelled before whisper_full");
                progress.reportCancelled();
                return R"({"cancelled": true, "text": ""})";
            }

            // First pass – standard decode
            whisper_full_params p = makeParams(streamingParams);
            if (streamingParams) {
                // whisper.cpp pads every mel to 30 s, but the encoder only
                // processes audio_ctx frames of it. Sizing audio_ctx to the
                // window being transcribed skips the zero-padding frames and
                // cuts the dominant encoder pass by up to ~6x for the short
                // windows live captions use. Batch transcription keeps the
                // full 30 s context (audio_ctx = 0).
                const int windowFrames = (count + 160 - 1) / 160;
                p.audio_ctx = std::min(1500, std::max(224, windowFrames + 16));
            }
            int res = whisper_full(ctx, p, audioData, count);

            if (res != 0) {
                if (checkCancelled(streamingParams)) {
                    LOG_I(TAG_WHISPER, "whisper_full aborted by cancellation");
                    progress.reportCancelled();
                    return "";
                }
                setError("whisper_full (pass#1) failed: " + std::to_string(res));
                progress.reportFailed("whisper_full failed: " + std::to_string(res));
                return "";
            }

            int n = whisper_full_n_segments(ctx);

            // Cache the detected language after the first auto-detection so
            // later calls skip the extra encoder pass.
            if (isAutoLang) {
                int lid = whisper_full_lang_id(ctx);
                if (lid >= 0) {
                    std::lock_guard<std::mutex> lock(partialMutex);
                    if (languageCache.empty()) {
                        languageCache = whisper_lang_str(lid);
                        LOG_I(TAG_WHISPER, "Language cached: %s", languageCache.c_str());
                    }
                }
            }

            // --- RETRY LOGIC ---
            // If auto-detect found a language but returned NO text, force that language and retry.
            if (!partial && isAutoLang && n == 0 && !checkCancelled()) {
                int lid = whisper_full_lang_id(ctx);
                if (lid >= 0) {
                    const char* lang = whisper_lang_str(lid);
                    LOG_W(TAG_WHISPER, "Auto-detected '%s' but 0 segments. Retrying forced.", lang);

                    whisper_full_params p2 = makeParams(streamingParams);
                    p2.detect_language = false;
                    p2.language = lang;
                    p2.no_speech_thold = 1.0f; // Force speech
                    p2.entropy_thold = 3.0f;
                    p2.suppress_blank = false;

                    whisper_full(ctx, p2, audioData, count);
                    n = whisper_full_n_segments(ctx);
                }
            }

            // Language detection update
            if (isAutoLang && n > 0) {
                int lid = whisper_full_lang_id(ctx);
                if (lid >= 0) detectedLang = whisper_lang_str(lid);
            } else {
                detectedLang = config.language;
            }

            std::string text;

            // Batch mode → clear segments. Streaming mode → keep and append.
            if (!partial && !streamingParams) {
                segments.clear();
            }

            for (int i = 0; i < n; ++i) {
                if (checkCancelled(streamingParams)) {
                    LOG_I(TAG_WHISPER, "Cancelled at segment %d/%d", i, n);
                    break;
                }

                const char* t = whisper_full_get_segment_text(ctx, i);
                std::string cleanText = sanitizeTranscript(t);
                if (cleanText.empty()) continue;

                TranscriptSegment seg;
                seg.text = cleanText;
                seg.startMs = whisper_full_get_segment_t0(ctx, i) * 10;
                seg.endMs = whisper_full_get_segment_t1(ctx, i) * 10;
                seg.confidence = calculateSegmentConfidence(ctx, i);

                if (!partial) {
                    segments.push_back(seg);
                } else if (streamedCapture) {
                    streamedCapture->push_back(seg);
                }

                if (!text.empty() && !seg.text.empty()) text += " ";
                text += seg.text;
            }

            auto end = std::chrono::steady_clock::now();
            auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
            auto us = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
            LOG_I(TAG_WHISPER, "Inference done: %d segments, %lldms (streaming=%d)",
                  n, ms, streamingParams ? 1 : 0);
            recordInferenceUs(us);

            return text;
        }
    };

    WhisperEngine::WhisperEngine() : impl_(std::make_unique<Impl>()) {
        impl_->parentEngine = this;
    }
    WhisperEngine::~WhisperEngine() { impl_->doFinalRelease(); }

    bool WhisperEngine::initialize(const EngineConfig& config) {
        std::lock_guard<std::mutex> transition(impl_->transitionMutex);
        if (isShuttingDown()) return false;
        impl_->stopWorker();
        if (!impl_->closeAndDrain()) return false;
        impl_->clearResources();
        clearCancellation();

        const auto failInitialization = [this](const std::string& error) {
            impl_->setError(error);
            impl_->clearResources();
            (void)impl_->reopenAfterCleanup();
            return false;
        };

        impl_->config = config;

        if (config.modelPath.empty()) return failInitialization("Model path empty");
        // Defense-in-depth for the vendored whisper.cpp: an unknown language
        // string reaches whisper_token_lang(ctx, -1), an out-of-bounds vocab
        // read. Our UI only offers valid codes, but the config JSON is a
        // public boundary — reject here with a clear error instead.
        if (!config.language.empty() && config.language != "auto" &&
            whisper_lang_id(config.language.c_str()) < 0) {
            return failInitialization(
                "Unknown language code: " + config.language +
                " (use ISO-639-1 like en, ar, zh, or auto)");
        }
        if (config.modelSha256.empty()) {
            return failInitialization("Verified model SHA-256 is required");
        }

        VerifiedModelFile verifiedModel;
        if (!verifiedModel.open(config.modelPath, config.modelSha256)) {
            return failInitialization(
                std::string("Model verification failed: ") +
                VerifiedModelFile::errorMessage(verifiedModel.lastError())
            );
        }
        if (isShuttingDown()) {
            return failInitialization("Engine shutdown requested after model verification");
        }

        whisper_log_set(whisper_log_callback, nullptr);
        LOG_I(TAG_WHISPER, "Loading model: %s", config.modelPath.c_str());

        whisper_context_params cp = whisper_context_default_params();
        cp.use_gpu = false;
        cp.flash_attn = false;

        auto start = std::chrono::steady_clock::now();
        whisper_model_loader loader{};
        loader.context = &verifiedModel;
        loader.read = &VerifiedModelFile::readCallback;
        loader.eof = &VerifiedModelFile::eofCallback;
        loader.close = &VerifiedModelFile::closeCallback;
        whisper_context* newContext = whisper_init_with_params(&loader, cp);
        auto end = std::chrono::steady_clock::now();

        if (!verifiedModel.healthy()) {
            if (newContext) whisper_free(newContext);
            return failInitialization(
                std::string("Verified model changed or ended during parsing: ") +
                VerifiedModelFile::errorMessage(verifiedModel.lastError())
            );
        }
        if (!newContext) return failInitialization("Failed to load model");
        if (isShuttingDown()) {
            whisper_free(newContext);
            return failInitialization("Engine shutdown requested while loading model");
        }
        impl_->ctx = newContext;

        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
        LOG_I(TAG_WHISPER, "Model loaded in %lldms", static_cast<long long>(ms));

        // Apply streaming geometry FIRST (before pre-allocation uses it).
        impl_->applyStreamingGeometry(config.chunkDurationMs, config.contextDurationMs);

        // Pre-allocate buffers with correct sizes
        impl_->buffer.reserve(impl_->chunkSamples * 2);
        impl_->inferenceBuffer.reserve(impl_->chunkSamples);
        impl_->segments.reserve(100);

        impl_->initialized = true;
        if (!impl_->reopenAfterCleanup()) {
            impl_->clearResources();
            return false;
        }
        impl_->startWorker();
        return true;
    }

    int WhisperEngine::pushAudio(const int16_t* samples, int count, int sampleRate) {
        if (!samples || count <= 0 || sampleRate <= 0) {
            impl_->setError("Invalid PCM16 streaming input");
            return -1;
        }

        const auto inputCount = static_cast<std::size_t>(count);
        resizeScratch(t_pcmFloatScratch, inputCount);
        AudioUtils::int16ToFloat(samples, t_pcmFloatScratch.data(), inputCount);

        if (sampleRate != WHISPER_RATE) {
            const std::size_t outputCapacity = AudioUtils::calculateResampleOutputSize(
                inputCount, sampleRate, WHISPER_RATE
            );
            if (outputCapacity == 0 ||
                outputCapacity > static_cast<std::size_t>(std::numeric_limits<int>::max())) {
                impl_->setError("Invalid streaming resample configuration");
                return -1;
            }
            resizeScratch(t_resampleScratch, outputCapacity);
            const std::size_t outputCount = AudioUtils::resampleInto(
                t_pcmFloatScratch.data(), inputCount, sampleRate, WHISPER_RATE,
                t_resampleScratch.data(), outputCapacity
            );
            if (outputCount == 0) {
                impl_->setError("Streaming resample failed");
                return -1;
            }
            return pushAudioFloat(
                t_resampleScratch.data(), static_cast<int>(outputCount), WHISPER_RATE
            );
        }
        return pushAudioFloat(t_pcmFloatScratch.data(), count, WHISPER_RATE);
    }

    int WhisperEngine::pushAudioFloat(const float* samples, int count, int sampleRate) {
        PROFILE_SCOPE("WhisperEngine::pushAudioFloat");
        const auto pushStart = std::chrono::steady_clock::now();

        // A reset pauses the lifecycle gate before clearing the ring buffer.
        // Count pushes as operations so an in-flight push cannot append old
        // audio after that clear, even when a native caller uses another thread.
        auto operation = impl_->lifecycle.tryAcquire();
        if (!operation) {
            impl_->setError("Whisper audio push rejected: engine is resetting or closing");
            return -1;
        }

        if (!impl_->initialized) {
            impl_->setError("Not initialized");
            return -1;
        }
        if (impl_->lifecycle.isClosing() || impl_->checkCancelled()) return -1;

        if (!samples || count <= 0) return 0;
        if (sampleRate <= 0) {
            impl_->setError("Invalid streaming sample rate");
            return -1;
        }
        const auto pcmStatus = AudioUtils::validateFinitePcm(samples, static_cast<size_t>(count));
        if (pcmStatus != AudioStatus::OK) {
            impl_->setError(std::string("Invalid streaming audio: ") + audioStatusMessage(pcmStatus));
            return -1;
        }

        // Handle resampling if needed (shouldn't happen if called correctly)
        std::vector<float> resampled;
        const float* data = samples;
        int dataCount = count;
        if (sampleRate != WHISPER_RATE) {
            resampled = AudioUtils::resample(samples, count, sampleRate, WHISPER_RATE);
            data = resampled.data();
            dataCount = static_cast<int>(resampled.size());
            if (dataCount == 0) {
                impl_->setError("Streaming resample failed");
                return -1;
            }
        }

        // O(chunk) append. Voice detection only MARKS utterance boundaries
        // (lastVoiceAt) — it never gates buffering. The previous
        // drop-on-silence path froze streaming whenever the gate misjudged
        // quiet speech: pushes kept succeeding while the worker saw an empty
        // buffer and never inferred again. Inference is fully asynchronous:
        // this call never touches whisper_context, so the audio path cannot
        // be blocked by a slow partial.
        {
            std::lock_guard<std::mutex> lock(impl_->streamMutex);

            bool active = true;
            if (impl_->vadEnabled && impl_->config.enableVad) {
                auto vadResult = impl_->audioGate.analyzeFloat(data, static_cast<size_t>(dataCount));
                if (!vadResult.isActive) {
                    active = false;
                    impl_->silentFramesSkipped++;
                    if (impl_->silentFramesSkipped % 50 == 1) {
                        LOG_D(TAG_WHISPER, "VAD: skipped %d silent frames (rms=%.4f dB)",
                              impl_->silentFramesSkipped, vadResult.rmsLevelDb);
                    }
                }
            }

            impl_->buffer.push_back(data, static_cast<size_t>(dataCount));
            if (active) {
                impl_->lastVoiceAt = std::chrono::steady_clock::now();
            }

            // Hard safety cap: drop the OLDEST audio rather than growing
            // without bound. The worker's rolling promote keeps the buffer
            // far below this in normal operation.
            const auto pending = static_cast<int64_t>(impl_->buffer.size());
            if (pending > Impl::kMaxPendingSamples) {
                const auto drop = static_cast<size_t>(pending - Impl::kMaxPendingSamples);
                impl_->buffer.discard_front(drop);
                impl_->streamedBaseSamples += static_cast<int64_t>(drop);
                impl_->droppedSamples += static_cast<int64_t>(drop);
                LOG_W(TAG_WHISPER,
                      "Streaming worker saturated; dropped %zu samples (%lld total)",
                      drop, static_cast<long long>(impl_->droppedSamples));
            }
            impl_->totalSamples += dataCount;
        }
        const auto pushUs = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - pushStart).count();
        impl_->recordPushUs(pushUs);
        impl_->streamCv.notify_one();
        return 0;
    }

    std::string WhisperEngine::getPartial() {
        if (impl_->lifecycle.isClosing()) return "";
        if (impl_->checkCancelled()) return R"({"partial":"","cancelled":true})";
        // Non-blocking: returns the last worker-published partial. No model
        // access, no lock shared with inference.
        std::lock_guard<std::mutex> lock(impl_->partialMutex);
        return impl_->publishedPartial;
    }

    std::string WhisperEngine::finalize() {
        std::lock_guard<std::mutex> transition(impl_->transitionMutex);
        impl_->stopWorker(/*abortInFlight=*/false);
        Impl::ProcessingGuard guard(impl_.get());
        if (!guard.acquired()) return R"({"cancelled": true, "text": ""})";
        if (!impl_->initialized) {
            impl_->setError("Not initialized");
            return "";
        }
        if (impl_->checkCancelled()) return R"({"cancelled": true, "text": ""})";

        auto start = std::chrono::steady_clock::now();

        // Process remaining buffer (single inference over the tail).
        {
            std::lock_guard<std::mutex> lock(impl_->streamMutex);
            impl_->finalizeScratch.clear();
            if (impl_->buffer.size() > 0) {
                impl_->finalizeScratch.resize(impl_->buffer.size());
                impl_->buffer.peek_front(impl_->finalizeScratch.data(), impl_->buffer.size());
                impl_->buffer.clear();
            }
        }
        if (!impl_->finalizeScratch.empty()) {
            // Pad if too short for Whisper
            size_t minSize = WHISPER_RATE / 2;  // 0.5 seconds minimum
            if (impl_->finalizeScratch.size() < minSize) {
                impl_->finalizeScratch.resize(minSize, 0.0f);
            }

            impl_->runInference(
                impl_->finalizeScratch.data(),
                static_cast<int>(impl_->finalizeScratch.size()), false,
                nullptr, /*streamingParams=*/false);
            if (impl_->checkCancelled()) {
                return R"({"cancelled": true, "text": ""})";
            }
        }

        // Build result text: streamed chunks first (chronological), then the
        // finalize tail segments.
        std::vector<TranscriptSegment> allSegments;
        allSegments.reserve(
            impl_->streamedSegments.size() + impl_->segments.size());
        allSegments.insert(
            allSegments.end(),
            impl_->streamedSegments.begin(), impl_->streamedSegments.end());
        allSegments.insert(
            allSegments.end(),
            impl_->segments.begin(), impl_->segments.end());

        std::string text;
        for (const auto& s : allSegments) {
            if (!text.empty()) text += " ";
            text += s.text;
        }

        auto end = std::chrono::steady_clock::now();
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();

        // Keep streaming functional if the caller pushes more audio after
        // finalize without resetting.
        impl_->startWorker();

        return JsonUtils::buildTranscriptJson(
            text,
            allSegments,
            impl_->detectedLang.empty() ? impl_->config.language : impl_->detectedLang,
            ms
        );
    }

    bool WhisperEngine::resetChecked() { return impl_->safeReset(); }

    void WhisperEngine::reset() { (void)resetChecked(); }

    void WhisperEngine::release() { impl_->doRelease(); }

    std::string WhisperEngine::transcribeBatch(const int16_t* samples, int count, int sampleRate) {
        if (!samples || count <= 0 || sampleRate <= 0) {
            impl_->setError("Invalid PCM16 batch input");
            return "";
        }
        std::vector<float> f(count);
        AudioUtils::int16ToFloat(samples, f.data(), count);
        return transcribeBatchFloat(f.data(), count, sampleRate);
    }

    std::string WhisperEngine::transcribeBatchFloat(const float* samples, int count, int sampleRate) {
        PROFILE_SCOPE("WhisperEngine::transcribeBatchFloat");

        Impl::ProcessingGuard guard(impl_.get());
        if (!guard.acquired()) return R"({"cancelled": true, "text": ""})";
        if (!impl_->initialized) { impl_->setError("Not initialized"); return ""; }
        if (!samples || count <= 0 || sampleRate <= 0) {
            impl_->setError("Invalid float batch input");
            return "";
        }
        const auto pcmStatus = AudioUtils::validateFinitePcm(samples, static_cast<size_t>(count));
        if (pcmStatus != AudioStatus::OK) {
            impl_->setError(std::string("Invalid float batch audio: ") + audioStatusMessage(pcmStatus));
            return "";
        }

        // Fresh cooperative-cancellation scope for this decode. The engine's
        // own cancelled_ flag (checked by checkCancelled) stays authoritative
        // for cancellation requested before this call.
        impl_->cancelToken.reset();

        impl_->resetState();
        auto start = std::chrono::steady_clock::now();

        std::vector<float> processBuffer;
        if (sampleRate != WHISPER_RATE) {
            processBuffer = AudioUtils::resample(samples, count, sampleRate, WHISPER_RATE);
            if (processBuffer.empty()) {
                impl_->setError("Batch resample failed");
                return "";
            }
        } else {
            processBuffer.assign(samples, samples + count);
        }

        std::string text = impl_->runInference(processBuffer.data(), static_cast<int>(processBuffer.size()), false, nullptr, /*streamingParams=*/false);
        if (impl_->checkCancelled()) {
            return R"({"cancelled": true, "text": ""})";
        }
        impl_->progress.reportDone();

        auto end = std::chrono::steady_clock::now();
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();

        // Log VAD stats if any frames were skipped
        if (impl_->silentFramesSkipped > 0) {
            LOG_I(TAG_WHISPER, "VAD stats: skipped %d silent frames", impl_->silentFramesSkipped);
        }

        return JsonUtils::buildTranscriptJson(text, impl_->segments,
                impl_->detectedLang.empty() ? impl_->config.language : impl_->detectedLang, ms);
    }

bool WhisperEngine::isInitialized() const { return impl_->initialized; }
bool WhisperEngine::isProcessing() const { return impl_->isProcessing(); }
std::string WhisperEngine::getLastError() const { return impl_->getError(); }

void WhisperEngine::onCancellationRequested() { impl_->cancelToken.request(); }

void WhisperEngine::setProgressFd(int fd) { impl_->progress.reattach(fd); }
int WhisperEngine::getModelType() const {
    Impl::ProcessingGuard guard(impl_.get());
    return guard.acquired() && impl_->ctx ? whisper_model_type(impl_->ctx) : -1;
}

std::string WhisperEngine::getVersion() { return "whisper.cpp"; }
bool WhisperEngine::isAvailable() { return true; }

std::string WhisperEngine::getDetectedLanguage() const {
    Impl::ProcessingGuard guard(impl_.get());
    if (!guard.acquired()) return "";
    return impl_->detectedLang;
}

std::string WhisperEngine::detectLanguageOnly(const int16_t* samples, int count, int sampleRate) {
    // Validate inputs BEFORE acquiring lock
    if (!samples || count <= 0) {
        impl_->setError("Invalid audio data");
        return "";
    }

    Impl::ProcessingGuard guard(impl_.get());
    if (!guard.acquired()) {
        impl_->setError("Engine is being released");
        return "";
    }

    if (!impl_->initialized || !impl_->ctx) {
        impl_->setError("Not initialized");
        return "";
    }

    // Check cancellation
    if (impl_->checkCancelled()) {
        return "";
    }

    // Convert to float
    std::vector<float> floatSamples(count);
    for (int i = 0; i < count; ++i) {
        floatSamples[i] = samples[i] / 32768.0f;
    }

    // Resample if needed - use AudioUtils (same as rest of codebase)
    std::vector<float> resampled;
    const float* audioData = floatSamples.data();
    int audioCount = count;

    if (sampleRate != WHISPER_RATE) {
        resampled = AudioUtils::resample(floatSamples.data(), count, sampleRate, WHISPER_RATE);
        if (resampled.empty()) {
            impl_->setError("Resampling failed");
            return "";
        }
        audioData = resampled.data();
        audioCount = static_cast<int>(resampled.size());
    }

    // Check cancellation before heavy work
    if (impl_->checkCancelled()) {
        return "";
    }

    // Run mel spectrogram
    // NOTE: This modifies ctx internal state - must be serialized with other whisper_* calls
    if (whisper_pcm_to_mel(impl_->ctx, audioData, audioCount, impl_->config.numThreads) != 0) {
        impl_->setError("Failed to compute mel spectrogram");
        return "";
    }

    // Check cancellation
    if (impl_->checkCancelled()) {
        return "";
    }

    // Use whisper_lang_auto_detect for LID-only (no full transcription)
    std::vector<float> langProbs(whisper_lang_max_id() + 1, 0.0f);
    int langId = whisper_lang_auto_detect(impl_->ctx, 0, impl_->config.numThreads, langProbs.data());

    if (langId < 0) {
        impl_->setError("Language detection failed");
        return "";
    }

    const char* langStr = whisper_lang_str(langId);
    if (!langStr) {
        impl_->setError("Unknown language ID");
        return "";
    }

    impl_->detectedLang = langStr;
    LOG_D(TAG_WHISPER, "Detected language: %s (id=%d)", langStr, langId);
    return langStr;
}

void WhisperEngine::setChunkParams(int chunkMs, int overlapMs) {
    Impl::ProcessingGuard guard(impl_.get());
    if (!guard.acquired()) return;
    impl_->applyStreamingGeometry(chunkMs, overlapMs);
}

EngineCapability WhisperEngine::getCapabilities() const {
    return EngineCapability::BATCH_TRANSCRIPTION |
           EngineCapability::STREAMING_PUSH |
           EngineCapability::PARTIAL_RESULTS |
           EngineCapability::SEGMENT_TIMESTAMPS |
           EngineCapability::WORD_TIMESTAMPS |
           EngineCapability::LANGUAGE_DETECTION |
           EngineCapability::TRANSLATION |
           EngineCapability::SEGMENT_CONFIDENCE;
}

std::vector<std::string> WhisperEngine::getSupportedLanguages() const {
    // Whisper supports these languages
    return {
        "auto", "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt",
        "tr", "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi",
        "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta",
        "no", "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy"
    };
}

#else // !WHISPER_AVAILABLE

    struct WhisperEngine::Impl { std::string lastError = "Whisper not compiled"; };
WhisperEngine::WhisperEngine() : impl_(std::make_unique<Impl>()) {}
WhisperEngine::~WhisperEngine() = default;
bool WhisperEngine::initialize(const EngineConfig&) { SET_ERROR(impl_->lastError); return false; }
int WhisperEngine::pushAudio(const int16_t*, int, int) { return -1; }
int WhisperEngine::pushAudioFloat(const float*, int) { return -1; }
std::string WhisperEngine::getPartial() { return ""; }
std::string WhisperEngine::finalize() { return ""; }
void WhisperEngine::reset() {}
bool WhisperEngine::resetChecked() { return false; }
void WhisperEngine::release() {}
std::string WhisperEngine::transcribeBatch(const int16_t*, int, int) { return ""; }
std::string WhisperEngine::transcribeBatchFloat(const float*, int, int) { return ""; }
bool WhisperEngine::isInitialized() const { return false; }
bool WhisperEngine::isProcessing() const { return false; }
std::string WhisperEngine::getLastError() const { return impl_->lastError; }
int WhisperEngine::getModelType() const { return -1; }
std::string WhisperEngine::getVersion() { return "not available"; }
bool WhisperEngine::isAvailable() { return false; }
std::string WhisperEngine::detectLanguageOnly(const int16_t*, int, int) { return ""; }

#endif

} // namespace stt
