#ifndef STT_CANCEL_TOKEN_H
#define STT_CANCEL_TOKEN_H

#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <utility>

namespace stt {

/**
 * Cooperative-cancellation token for hot loops.
 *
 * Modeled after the LifecycleGate contract (common/lifecycle_gate.h):
 * shared state behind a handle, non-throwing, safe to poll from any
 * thread. request() flips a release-store flag; cancelled() is an
 * acquire-load meant for tight decode/encode loops. Copies share state,
 * so a worker may hold a copy while the owner requests cancellation on
 * the original — the same observation semantics as LifecycleGate's
 * shared_ptr<State>.
 *
 * The optional monotonic deadline (steady_clock) covers bounded work:
 * expired() reports timeout separately from user cancellation so callers
 * can map outcomes to CANCELLED vs FAILED. shouldStop() is the single
 * hot-loop check combining both.
 */
class CancelToken {
    struct State {
        std::atomic<bool> requested{false};
        mutable std::mutex deadlineMutex;
        bool hasDeadline = false;
        std::chrono::steady_clock::time_point deadline{};
    };

public:
    /** Why the token fired — distinguishes user cancel from timeout. */
    enum class Reason { None, Requested, Expired };

    CancelToken() : state_(std::make_shared<State>()) {}

    /** Request cancellation. Idempotent; never throws. */
    void request() noexcept {
        state_->requested.store(true, std::memory_order_release);
    }

    /** Hot-loop check: true once request() was called. Acquire-load. */
    bool cancelled() const noexcept {
        return state_->requested.load(std::memory_order_acquire);
    }

    /** Reuse the token for a fresh unit of work (mirrors clearCancellation). */
    void reset() noexcept {
        state_->requested.store(false, std::memory_order_release);
        std::lock_guard<std::mutex> lock(state_->deadlineMutex);
        state_->hasDeadline = false;
    }

    /** Arm a monotonic timeout (steady_clock; immune to wall-clock jumps). */
    void setDeadline(std::chrono::milliseconds timeout) {
        std::lock_guard<std::mutex> lock(state_->deadlineMutex);
        state_->hasDeadline = timeout.count() >= 0;
        state_->deadline = std::chrono::steady_clock::now() + timeout;
    }

    bool hasDeadline() const {
        std::lock_guard<std::mutex> lock(state_->deadlineMutex);
        return state_->hasDeadline;
    }

    /** True when the armed deadline has passed (false when none/expired flag). */
    bool expired() const {
        std::lock_guard<std::mutex> lock(state_->deadlineMutex);
        if (!state_->hasDeadline) return false;
        return std::chrono::steady_clock::now() >= state_->deadline;
    }

    /** The single check for hot loops: user cancel OR deadline. */
    bool shouldStop() const noexcept { return cancelled() || expired(); }

    Reason reason() const {
        if (cancelled()) return Reason::Requested;
        if (expired()) return Reason::Expired;
        return Reason::None;
    }

private:
    std::shared_ptr<State> state_;
};

} // namespace stt

#endif // STT_CANCEL_TOKEN_H
