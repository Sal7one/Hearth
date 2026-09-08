#ifndef STT_LIFECYCLE_GATE_H
#define STT_LIFECYCLE_GATE_H

#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <limits>
#include <memory>
#include <mutex>
#include <utility>

namespace stt::concurrency {

/**
 * Coordinates operations with destructive lifecycle transitions.
 *
 * Operations acquire a move-only token. closeAndWait() atomically rejects new
 * operations and waits for all existing tokens to drain. A timeout deliberately
 * leaves the gate closed: callers may retry cleanup later, but must never free
 * resources that an active operation could still be using.
 *
 * Lifecycle transitions and the cleanup they protect must be serialized by the
 * owner. Multiple closeAndWait() callers may all observe the same drain; this
 * gate coordinates admission, not unique cleanup ownership.
 */
class LifecycleGate {
    struct State {
        mutable std::mutex mutex;
        std::condition_variable idle;
        std::size_t active = 0;
        std::size_t drainWaiters = 0;
        bool closing = false;
        bool permanentClosing = false;
        bool drained = false;
    };

public:
    class Operation {
    public:
        Operation() = default;

        Operation(Operation&& other) noexcept
            : state_(std::move(other.state_)) {}

        Operation& operator=(Operation&& other) noexcept {
            if (this == &other) return *this;
            release();
            state_ = std::move(other.state_);
            return *this;
        }

        ~Operation() { release(); }

        explicit operator bool() const noexcept { return state_ != nullptr; }

        Operation(const Operation&) = delete;
        Operation& operator=(const Operation&) = delete;

    private:
        friend class LifecycleGate;
        explicit Operation(std::shared_ptr<State> state) noexcept
            : state_(std::move(state)) {}

        void release() noexcept {
            if (!state_) return;
            std::shared_ptr<State> state = std::move(state_);
            {
                std::lock_guard<std::mutex> lock(state->mutex);
                if (state->active != 0) --state->active;
            }
            state->idle.notify_all();
        }

        std::shared_ptr<State> state_;
    };

    /** Temporarily rejects new work while a synchronous reset runs. */
    class Pause {
    public:
        Pause() = default;

        Pause(Pause&& other) noexcept
            : state_(std::move(other.state_)) {}

        Pause& operator=(Pause&& other) noexcept {
            if (this == &other) return *this;
            release();
            state_ = std::move(other.state_);
            return *this;
        }

        ~Pause() { release(); }

        explicit operator bool() const noexcept { return state_ != nullptr; }

        Pause(const Pause&) = delete;
        Pause& operator=(const Pause&) = delete;

    private:
        friend class LifecycleGate;
        explicit Pause(std::shared_ptr<State> state) noexcept
            : state_(std::move(state)) {}

        void release() noexcept {
            if (!state_) return;
            std::shared_ptr<State> state = std::move(state_);
            {
                std::lock_guard<std::mutex> lock(state->mutex);
                if (state->active != 0) --state->active;
                if (!state->permanentClosing && state->active == 0) {
                    state->closing = false;
                }
            }
            state->idle.notify_all();
        }

        std::shared_ptr<State> state_;
    };

    Operation tryAcquire() {
        std::lock_guard<std::mutex> lock(state_->mutex);
        if (state_->closing ||
            state_->active == std::numeric_limits<std::size_t>::max()) {
            return {};
        }
        ++state_->active;
        return Operation(state_);
    }

    /**
     * Close the gate and wait for active operations. On timeout the gate stays
     * closed, so a later cleanup attempt can safely retry the wait.
     */
    template <typename Rep, typename Period>
    bool closeAndWait(const std::chrono::duration<Rep, Period>& timeout) {
        std::unique_lock<std::mutex> lock(state_->mutex);
        state_->closing = true;
        state_->permanentClosing = true;
        ++state_->drainWaiters;
        const bool idle = state_->idle.wait_for(lock, timeout, [state = state_] {
            return state->active == 0;
        });
        if (idle) state_->drained = true;
        --state_->drainWaiters;
        return idle;
    }

    /** Permanently close and wait without a timeout, for object destruction. */
    void closeAndWait() {
        std::unique_lock<std::mutex> lock(state_->mutex);
        state_->closing = true;
        state_->permanentClosing = true;
        ++state_->drainWaiters;
        state_->idle.wait(lock, [state = state_] { return state->active == 0; });
        state_->drained = true;
        --state_->drainWaiters;
    }

    /** Close without waiting, useful before sending a cancellation signal. */
    void close() {
        std::lock_guard<std::mutex> lock(state_->mutex);
        state_->closing = true;
        state_->permanentClosing = true;
    }

    /**
     * Acquire an immediate exclusive pause. Failure does not change gate state.
     */
    Pause tryPause() {
        std::lock_guard<std::mutex> lock(state_->mutex);
        if (state_->closing || state_->active != 0) return {};
        state_->closing = true;
        state_->permanentClosing = false;
        state_->drained = false;
        ++state_->active;
        return Pause(state_);
    }

    /** Reopen only after closeAndWait() observed a fully drained gate. */
    bool reopen() {
        std::lock_guard<std::mutex> lock(state_->mutex);
        if (state_->active != 0 || state_->drainWaiters != 0 ||
            !state_->permanentClosing || !state_->drained) {
            return false;
        }
        state_->closing = false;
        state_->permanentClosing = false;
        state_->drained = false;
        return true;
    }

    bool isClosing() const {
        std::lock_guard<std::mutex> lock(state_->mutex);
        return state_->closing;
    }

    std::size_t activeCount() const {
        std::lock_guard<std::mutex> lock(state_->mutex);
        return state_->active;
    }

    LifecycleGate() : state_(std::make_shared<State>()) {}
    LifecycleGate(const LifecycleGate&) = delete;
    LifecycleGate& operator=(const LifecycleGate&) = delete;

private:
    std::shared_ptr<State> state_;
};

} // namespace stt::concurrency

#endif // STT_LIFECYCLE_GATE_H
