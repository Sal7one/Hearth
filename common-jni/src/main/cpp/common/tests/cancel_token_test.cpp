// cancel_token_test.cpp — direct unit tests for common/cancel_token.h.
#include <cassert>
#include <chrono>
#include <iostream>
#include <thread>

#include "cancel_token.h"

using stt::CancelToken;

static int failures = 0;
#define CHECK(cond)                                              \
    do {                                                         \
        if (!(cond)) {                                           \
            ++failures;                                          \
            std::cerr << "FAIL " << __LINE__ << ": " #cond "\n"; \
        }                                                        \
    } while (0)

int main() {
    // Basic request/observe/reset
    CancelToken token;
    CHECK(!token.cancelled());
    CHECK(token.reason() == CancelToken::Reason::None);
    token.request();
    CHECK(token.cancelled());
    CHECK(token.reason() == CancelToken::Reason::Requested);
    token.reset();
    CHECK(!token.cancelled());
    CHECK(!token.expired());

    // Copies share state (worker holds a copy, owner requests the original)
    CancelToken workerCopy = token;
    token.request();
    CHECK(workerCopy.cancelled());

    // Cancellation observed from another thread
    CancelToken threaded;
    std::thread observer([&threaded] {
        int spins = 0;
        while (!threaded.cancelled() && spins < 100000) ++spins;
    });
    threaded.request();
    observer.join();

    // Deadline: armed timeout expires against a monotonic clock
    CancelToken timed;
    timed.setDeadline(std::chrono::milliseconds(5));
    CHECK(timed.hasDeadline());
    CHECK(!timed.cancelled());
    CHECK(timed.shouldStop() == false || timed.expired());  // may already be past
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    CHECK(timed.expired());
    CHECK(timed.shouldStop());
    CHECK(!timed.cancelled());  // timeout is not user cancellation
    CHECK(timed.reason() == CancelToken::Reason::Expired);

    // reset clears the deadline too
    timed.reset();
    CHECK(!timed.hasDeadline());
    CHECK(!timed.expired());

    // user cancel wins over timeout in reason()
    timed.setDeadline(std::chrono::milliseconds(5));
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    timed.request();
    CHECK(timed.reason() == CancelToken::Reason::Requested);

    if (failures == 0) {
        std::cout << "cancel_token_test: ALL PASS\n";
        return 0;
    }
    std::cout << "cancel_token_test: " << failures << " FAILURES\n";
    return 1;
}
