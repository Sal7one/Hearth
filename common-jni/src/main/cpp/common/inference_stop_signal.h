#ifndef STT_INFERENCE_STOP_SIGNAL_H
#define STT_INFERENCE_STOP_SIGNAL_H

#include <atomic>

namespace stt::concurrency {

/**
 * Stops a streaming worker's current decode without cancelling the engine's
 * next batch/finalize operation. Call clear() only after the worker is joined.
 */
class InferenceStopSignal {
public:
    void request() noexcept { requested_.store(true, std::memory_order_release); }
    void clear() noexcept { requested_.store(false, std::memory_order_release); }
    bool requested() const noexcept { return requested_.load(std::memory_order_acquire); }
    bool shouldAbort(bool workerInference) const noexcept {
        return workerInference && requested();
    }

private:
    std::atomic<bool> requested_{false};
};

} // namespace stt::concurrency

#endif // STT_INFERENCE_STOP_SIGNAL_H
