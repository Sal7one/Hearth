// job_future_test.cpp — direct unit tests for common/job_future.h.
#include <chrono>
#include <iostream>
#include <string>
#include <thread>

#include "cancel_token.h"
#include "error_codes.h"
#include "job_future.h"
#include "job_system.h"

using stt::CancelToken;
using stt::ErrorCode;
using stt::JobException;
using stt::JobResult;
using stt::JobSystem;

static int failures = 0;
#define CHECK(cond)                                              \
    do {                                                         \
        if (!(cond)) {                                           \
            ++failures;                                          \
            std::cerr << "FAIL " << __LINE__ << ": " #cond "\n"; \
        }                                                        \
    } while (0)

int main() {
    JobSystem pool(2);

    // DONE carries the payload
    {
        auto future = stt::submitJob(pool, CancelToken{}, [] { return 41 + 1; });
        JobResult<int> result = stt::getJob(std::move(future));
        CHECK(result.ok());
        CHECK(result.status == JobResult<int>::Status::Done);
        CHECK(result.payload == 42);
        CHECK(result.code == ErrorCode::OK);
    }

    // Typed failure through JobException
    {
        auto future = stt::submitJob(pool, CancelToken{}, []() -> int {
            throw JobException(ErrorCode::ENGINE_INFERENCE_FAILED, "model exploded");
        });
        JobResult<int> result = stt::getJob(std::move(future));
        CHECK(result.status == JobResult<int>::Status::Failed);
        CHECK(result.code == ErrorCode::ENGINE_INFERENCE_FAILED);
        CHECK(result.message == "model exploded");
    }

    // Exception with the token fired maps to CANCELLED, not FAILED
    {
        CancelToken token;
        token.request();
        auto future = stt::submitJob(pool, token, []() -> int {
            throw std::runtime_error("inputs torn down mid-cancel");
        });
        JobResult<int> result = stt::getJob(std::move(future));
        CHECK(result.cancelled());
        CHECK(result.code == ErrorCode::ENGINE_CANCELLED);
        CHECK(result.message == "inputs torn down mid-cancel");
    }

    // Cancellation observed inside the task itself (cooperative exit)
    {
        CancelToken token;
        auto future = stt::submitJob(pool, token, [&token]() -> std::string {
            for (int i = 0; i < 1000; ++i) {
                if (token.shouldStop()) throw JobException(ErrorCode::ENGINE_CANCELLED, "decode loop stop");
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
            return "finished";
        });
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
        token.request();
        JobResult<std::string> result = stt::getJob(std::move(future));
        CHECK(result.cancelled());
    }

    // Deadline expiry also maps exceptions to CANCELLED
    {
        CancelToken token;
        token.setDeadline(std::chrono::milliseconds(1));
        auto future = stt::submitJob(pool, token, []() -> int {
            std::this_thread::sleep_for(std::chrono::milliseconds(30));
            throw std::runtime_error("too slow");
        });
        JobResult<int> result = stt::getJob(std::move(future));
        CHECK(result.cancelled());
    }

    // Normal completion stays DONE even if the token fired late
    {
        CancelToken token;
        auto future = stt::submitJob(pool, token, [] { return 7; });
        token.request();
        JobResult<int> result = stt::getJob(std::move(future));
        CHECK(result.ok());
        CHECK(result.payload == 7);
    }

    // void jobs
    {
        bool ran = false;
        auto future = stt::submitJob(pool, CancelToken{}, [&ran] { ran = true; });
        JobResult<void> result = stt::getJob(std::move(future));
        CHECK(result.ok());
        CHECK(ran);
    }

    // getJob never throws, even against a stopped pool
    {
        JobSystem tiny(1);
        tiny.shutdown();
        CancelToken token;
        auto future = stt::submitJob(tiny, token, [] { return 1; });
        JobResult<int> result = stt::getJob(std::move(future));
        CHECK(result.status == JobResult<int>::Status::Failed);
        CHECK(result.code == ErrorCode::INVALID_STATE);
    }

    pool.shutdown();

    if (failures == 0) {
        std::cout << "job_future_test: ALL PASS\n";
        return 0;
    }
    std::cout << "job_future_test: " << failures << " FAILURES\n";
    return 1;
}
