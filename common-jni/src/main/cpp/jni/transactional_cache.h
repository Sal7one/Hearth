#ifndef JNI_TRANSACTIONAL_CACHE_H
#define JNI_TRANSACTIONAL_CACHE_H

#include <mutex>
#include <optional>
#include <utility>

namespace jni {

/**
 * Thread-safe, transactionally initialized process-lifetime cache.
 *
 * Initialization publishes only a complete candidate. release() is terminal,
 * idempotent, and waits for an in-progress access callback before moving the
 * stored value to caller-provided cleanup. access() is intended to promote a
 * cached global resource into a call-local lease while holding the mutex.
 */
template <typename T>
class TransactionalCache {
public:
    enum class State { Empty, Ready, Released };

    template <typename Builder, typename Rollback>
    bool initialize(Builder&& builder, Rollback&& rollback) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ == State::Ready) return true;
        if (state_ == State::Released) return false;

        T candidate{};
        try {
            if (!builder(candidate)) {
                rollback(candidate);
                return false;
            }
        } catch (...) {
            rollback(candidate);
            throw;
        }
        value_.emplace(std::move(candidate));
        state_ = State::Ready;
        return true;
    }

    /** Run a small promotion callback while the cached value is protected. */
    template <typename Result, typename Promoter>
    bool access(Result& result, Promoter&& promoter) const {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != State::Ready || !value_) return false;
        return promoter(*value_, result);
    }

    template <typename Cleanup>
    void release(Cleanup&& cleanup) {
        std::optional<T> retired;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (state_ == State::Released) return;
            state_ = State::Released;
            if (value_) {
                retired.emplace(std::move(*value_));
                value_.reset();
            }
        }
        if (retired) cleanup(*retired);
    }

    State state() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return state_;
    }

    TransactionalCache() = default;
    TransactionalCache(const TransactionalCache&) = delete;
    TransactionalCache& operator=(const TransactionalCache&) = delete;

private:
    mutable std::mutex mutex_;
    std::optional<T> value_;
    State state_ = State::Empty;
};

} // namespace jni

#endif // JNI_TRANSACTIONAL_CACHE_H
