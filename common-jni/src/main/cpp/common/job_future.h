#ifndef STT_JOB_FUTURE_H
#define STT_JOB_FUTURE_H

#include <exception>
#include <future>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <utility>

#include "cancel_token.h"
#include "error_codes.h"
#include "job_system.h"

namespace stt {

/** Exception carrying a typed ErrorCode for the failure path of JobResult. */
class JobException final : public std::runtime_error {
public:
    JobException(ErrorCode code, const std::string& message)
        : std::runtime_error(message), code_(code) {}
    ErrorCode code() const noexcept { return code_; }

private:
    ErrorCode code_;
};

/**
 * Typed result envelope over JobSystem::submitWithResult().
 *
 * Status is DONE (payload present), FAILED (ErrorCode + message) or
 * CANCELLED. submitJob() maps an exception to CANCELLED instead of
 * FAILED when the token fired — the distinction the file-watchdog design
 * could not make (it abandoned stalled jobs it could not cancel).
 */
template <typename T>
struct JobResult {
    enum class Status { Done, Failed, Cancelled };

    Status status = Status::Failed;
    T payload{};
    ErrorCode code = ErrorCode::UNKNOWN;
    std::string message;

    bool ok() const noexcept { return status == Status::Done; }
    bool cancelled() const noexcept { return status == Status::Cancelled; }

    static JobResult makeDone(T value) {
        return JobResult{Status::Done, std::move(value), ErrorCode::OK, {}};
    }
    static JobResult makeFailure(ErrorCode code, std::string message) {
        return JobResult{Status::Failed, T{}, code, std::move(message)};
    }
    static JobResult makeCancelled(std::string message = "cancelled") {
        return JobResult{Status::Cancelled, T{}, ErrorCode::ENGINE_CANCELLED, std::move(message)};
    }
};

template <>
struct JobResult<void> {
    enum class Status { Done, Failed, Cancelled };

    Status status = Status::Failed;
    ErrorCode code = ErrorCode::UNKNOWN;
    std::string message;

    bool ok() const noexcept { return status == Status::Done; }
    bool cancelled() const noexcept { return status == Status::Cancelled; }

    static JobResult makeDone() { return JobResult{Status::Done, ErrorCode::OK, {}}; }
    static JobResult makeFailure(ErrorCode code, std::string message) {
        return JobResult{Status::Failed, code, std::move(message)};
    }
    static JobResult makeCancelled(std::string message = "cancelled") {
        return JobResult{Status::Cancelled, ErrorCode::ENGINE_CANCELLED, std::move(message)};
    }
};

namespace detail {

inline const char* whatOf(const std::exception_ptr& error) noexcept {
    thread_local std::string storage;
    try {
        std::rethrow_exception(error);
    } catch (const std::exception& e) {
        storage = e.what();
    } catch (...) {
        storage = "unknown job failure";
    }
    return storage.c_str();
}

inline ErrorCode codeOf(const std::exception_ptr& error) noexcept {
    try {
        std::rethrow_exception(error);
    } catch (const JobException& e) {
        return e.code();
    } catch (...) {
        return ErrorCode::UNKNOWN;
    }
}

} // namespace detail

/**
 * Submit fn onto the pool; the returned future never throws — every
 * outcome arrives as a JobResult. Exceptions become FAILED (with the
 * JobException's code when thrown), or CANCELLED when the token fired,
 * whether the task noticed the token and threw or failed because its
 * inputs were torn down. Normal returns stay DONE even if the token
 * fired late — completed work is completed.
 *
 * Note (from job_system.h): do not route whisper/ffmpeg/opencv hot paths
 * through this pool; use it at independently owned task boundaries
 * (media sessions, host-side orchestration, tests).
 */
template <typename Fn>
auto submitJob(JobSystem& pool, const CancelToken& token, Fn fn)
    -> std::future<JobResult<std::decay_t<decltype(fn())>>> {
    using T = std::decay_t<decltype(fn())>;
    return pool.submitWithResult<JobResult<T>>([token, fn = std::move(fn)]() mutable -> JobResult<T> {
        try {
            if constexpr (std::is_void_v<T>) {
                fn();
                return JobResult<T>::makeDone();
            } else {
                return JobResult<T>::makeDone(fn());
            }
        } catch (...) {
            const std::exception_ptr error = std::current_exception();
            if (token.cancelled() || token.expired()) {
                return JobResult<T>::makeCancelled(detail::whatOf(error));
            }
            return JobResult<T>::makeFailure(detail::codeOf(error), detail::whatOf(error));
        }
    });
}

/**
 * Wait a submitJob future without ever throwing: pool shutdown rejection
 * (JobSystemStopped) becomes a FAILED result instead of an exception.
 */
template <typename T>
JobResult<T> getJob(std::future<JobResult<T>> future) {
    try {
        return future.get();
    } catch (const JobSystemStopped&) {
        return JobResult<T>::makeFailure(ErrorCode::INVALID_STATE, "job system rejected the task");
    } catch (...) {
        return JobResult<T>::makeFailure(ErrorCode::UNKNOWN, detail::whatOf(std::current_exception()));
    }
}

} // namespace stt

#endif // STT_JOB_FUTURE_H
