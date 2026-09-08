#ifndef JOB_SYSTEM_H
#define JOB_SYSTEM_H

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <exception>
#include <functional>
#include <future>
#include <iterator>
#include <memory>
#include <mutex>
#include <queue>
#include <stdexcept>
#include <thread>
#include <type_traits>
#include <utility>
#include <vector>

namespace stt {

/** Raised through result futures when shutdown has stopped accepting work. */
class JobSystemStopped final : public std::runtime_error {
public:
    JobSystemStopped()
        : std::runtime_error("JobSystem is shutting down and no longer accepts work") {}
};

/**
 * Small reusable pool for independent native tasks.
 *
 * This is infrastructure, not an extra parallel layer for FFmpeg, whisper.cpp,
 * or OpenCV inference. Those libraries already own internal worker pools and
 * stateful resources; wrapping their hot paths here would oversubscribe mobile
 * CPUs and could run non-reentrant contexts concurrently. Integrate only at an
 * independently owned task boundary after measuring the complete pipeline.
 *
 * Submission and waiting contracts:
 * - submit() returns false after shutdown begins.
 * - submitWithResult() returns a ready exceptional future when rejected.
 * - Exceptions from fire-and-forget jobs are contained and counted.
 * - A result task submitted by one of this pool's workers executes inline. This
 *   makes nested submitWithResult(...).get() and nested parallelFor safe even
 *   with a single worker.
 * - waitAll() and shutdown() are pool-wide barriers. Calling either from this
 *   pool's worker fails immediately with std::logic_error instead of deadlocking.
 */
class JobSystem {
public:
    using Job = std::function<void()>;

    static constexpr std::size_t kDefaultWorkerLimit = 4;
    static constexpr std::size_t kFallbackWorkerCount = 2;

    /**
     * Resolve a platform-reported CPU count to the bounded default pool size.
     * Explicit constructor counts are honored; only automatic sizing is capped.
     */
    static std::size_t boundedDefaultWorkerCount(unsigned int hardwareConcurrency) noexcept {
        if (hardwareConcurrency == 0U) return kFallbackWorkerCount;
        return std::min(static_cast<std::size_t>(hardwareConcurrency), kDefaultWorkerLimit);
    }

    static JobSystem& getInstance() {
        static JobSystem instance;
        return instance;
    }

    /** workerCount == 0 selects a hardware-aware default capped at four. */
    explicit JobSystem(std::size_t workerCount = 0)
        : configuredWorkerCount_(
              workerCount == 0
                  ? boundedDefaultWorkerCount(std::thread::hardware_concurrency())
                  : workerCount) {
        try {
            workers_.reserve(configuredWorkerCount_);
            for (std::size_t i = 0; i < configuredWorkerCount_; ++i) {
                workers_.emplace_back([this]() { workerLoop(); });
            }
        } catch (...) {
            stopWorkersAfterConstructionFailure();
            throw;
        }
    }

    ~JobSystem() noexcept {
        if (isCurrentWorkerThread()) std::terminate();
        try {
            shutdown();
        } catch (...) {
            std::terminate();
        }
    }

    /** Queue fire-and-forget work, or return false when shutdown has begun. */
    bool submit(Job job) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (state_ != State::Running) return false;
            jobs_.push(std::move(job));
            ++pendingCount_;
        }
        workCondition_.notify_one();
        return true;
    }

    /**
     * Submit result-bearing work. Task exceptions and shutdown rejection are
     * always delivered by the returned future; neither can strand a waiter.
     */
    template<typename T>
    std::future<T> submitWithResult(std::function<T()> task) {
        auto promise = std::make_shared<std::promise<T>>();
        auto future = promise->get_future();
        Job job = [promise, task = std::move(task)]() mutable {
            try {
                if constexpr (std::is_void_v<T>) {
                    task();
                    promise->set_value();
                } else {
                    promise->set_value(task());
                }
            } catch (...) {
                promise->set_exception(std::current_exception());
            }
        };

        // Queueing an inner result task can deadlock when every worker waits on
        // its own future. Run it inline while the parent job is already counted.
        if (isCurrentWorkerThread()) {
            if (acceptsInlineWork()) {
                job();
            } else {
                reject(promise);
            }
        } else if (!submit(std::move(job))) {
            reject(promise);
        }
        return future;
    }

    /** Wait until all accepted work completes. External threads only. */
    void waitAll() {
        if (isCurrentWorkerThread()) {
            throw std::logic_error("JobSystem::waitAll cannot be called from its worker");
        }
        std::unique_lock<std::mutex> lock(mutex_);
        completedCondition_.wait(lock, [this]() { return pendingCount_ == 0; });
    }

    /** Configured pool capacity. It remains stable after shutdown. */
    std::size_t workerCount() const noexcept { return configuredWorkerCount_; }

    std::size_t pendingJobs() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return pendingCount_;
    }

    std::size_t unhandledExceptionCount() const noexcept {
        return unhandledExceptionCount_.load(std::memory_order_relaxed);
    }

    bool isRunning() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return state_ == State::Running;
    }

    /**
     * Stop accepting work, drain accepted jobs, and join every worker.
     * Concurrent external shutdown callers wait for the owner to finish.
     */
    void shutdown() {
        if (isCurrentWorkerThread()) {
            throw std::logic_error("JobSystem::shutdown cannot be called from its worker");
        }

        {
            std::unique_lock<std::mutex> lock(mutex_);
            if (state_ == State::Stopped) return;
            if (state_ == State::Draining) {
                stoppedCondition_.wait(lock, [this]() { return state_ == State::Stopped; });
                return;
            }
            state_ = State::Draining;
        }
        workCondition_.notify_all();

        for (auto& worker : workers_) {
            if (worker.joinable()) worker.join();
        }

        {
            std::lock_guard<std::mutex> lock(mutex_);
            workers_.clear();
            state_ = State::Stopped;
        }
        stoppedCondition_.notify_all();
    }

    JobSystem(const JobSystem&) = delete;
    JobSystem& operator=(const JobSystem&) = delete;

private:
    enum class State {
        Running,
        Draining,
        Stopped,
    };

    template<typename T>
    static void reject(const std::shared_ptr<std::promise<T>>& promise) noexcept {
        try {
            promise->set_exception(std::make_exception_ptr(JobSystemStopped{}));
        } catch (...) {
            // The promise is private to this submission and cannot already be
            // satisfied. A defensive catch keeps the rejection path noexcept.
        }
    }

    bool isCurrentWorkerThread() const noexcept {
        return currentWorkerSystem_ == this;
    }

    bool acceptsInlineWork() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return state_ == State::Running;
    }

    void completeOneJob() noexcept {
        bool becameIdle = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (pendingCount_ > 0) {
                --pendingCount_;
                becameIdle = pendingCount_ == 0;
            }
        }
        if (becameIdle) completedCondition_.notify_all();
    }

    void workerLoop() noexcept {
        currentWorkerSystem_ = this;
        while (true) {
            Job job;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                workCondition_.wait(lock, [this]() {
                    return state_ != State::Running || !jobs_.empty();
                });
                if (state_ != State::Running && jobs_.empty()) {
                    currentWorkerSystem_ = nullptr;
                    return;
                }
                job = std::move(jobs_.front());
                jobs_.pop();
            }

            try {
                job();
            } catch (...) {
                unhandledExceptionCount_.fetch_add(1, std::memory_order_relaxed);
            }
            completeOneJob();
        }
    }

    void stopWorkersAfterConstructionFailure() noexcept {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            state_ = State::Draining;
        }
        workCondition_.notify_all();
        for (auto& worker : workers_) {
            if (worker.joinable()) worker.join();
        }
        workers_.clear();
        state_ = State::Stopped;
    }

    const std::size_t configuredWorkerCount_;
    std::vector<std::thread> workers_;
    std::queue<Job> jobs_;
    mutable std::mutex mutex_;
    std::condition_variable workCondition_;
    std::condition_variable completedCondition_;
    std::condition_variable stoppedCondition_;
    State state_{State::Running};
    std::size_t pendingCount_{0};
    std::atomic<std::size_t> unhandledExceptionCount_{0};

    inline static thread_local const JobSystem* currentWorkerSystem_ = nullptr;
};

namespace detail {

inline void waitForJobFutures(std::vector<std::future<void>>& futures) {
    std::exception_ptr firstError;
    for (auto& future : futures) {
        try {
            future.get();
        } catch (...) {
            if (!firstError) firstError = std::current_exception();
        }
    }
    if (firstError) std::rethrow_exception(firstError);
}

} // namespace detail

/**
 * Run independent values on the shared pool. Empty ranges return immediately.
 * The callable and copied values must be safe for concurrent use.
 */
template<typename Iterator, typename Func>
void parallelFor(Iterator begin, Iterator end, Func func) {
    if (begin == end) return;
    auto& jobs = JobSystem::getInstance();
    auto sharedFunc = std::make_shared<std::decay_t<Func>>(std::move(func));
    std::vector<std::future<void>> futures;
    for (auto it = begin; it != end; ++it) {
        futures.emplace_back(jobs.submitWithResult<void>(
            [sharedFunc, item = *it]() mutable { std::invoke(*sharedFunc, item); }));
    }
    detail::waitForJobFutures(futures);
}

/** Run indices [0, count) on the shared pool. count == 0 returns immediately. */
template<typename Func>
void parallelForIndex(std::size_t count, Func func) {
    if (count == 0) return;
    auto& jobs = JobSystem::getInstance();
    auto sharedFunc = std::make_shared<std::decay_t<Func>>(std::move(func));
    std::vector<std::future<void>> futures;
    futures.reserve(count);
    for (std::size_t i = 0; i < count; ++i) {
        futures.emplace_back(jobs.submitWithResult<void>(
            [sharedFunc, i]() mutable { std::invoke(*sharedFunc, i); }));
    }
    detail::waitForJobFutures(futures);
}

} // namespace stt

#endif // JOB_SYSTEM_H
