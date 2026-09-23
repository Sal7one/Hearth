#include "inference_stop_signal.h"

#include <cassert>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <future>
#include <thread>

int main() {
    stt::concurrency::InferenceStopSignal stop;
    assert(!stop.requested());
    assert(!stop.shouldAbort(true));

    std::promise<void> entered;
    std::promise<void> exited;
    auto enteredFuture = entered.get_future();
    auto exitedFuture = exited.get_future();
    std::thread fakeDecode([&] {
        entered.set_value();
        while (!stop.shouldAbort(true)) std::this_thread::yield();
        exited.set_value();
    });
    enteredFuture.wait();
    stop.request();
    if (exitedFuture.wait_for(std::chrono::seconds(2)) != std::future_status::ready) {
        std::fprintf(stderr, "streaming decode ignored its stop request\n");
        std::abort();
    }
    fakeDecode.join();

    assert(stop.requested());
    assert(!stop.shouldAbort(false)); // Finalize/batch must still be able to decode.
    stop.clear();
    assert(!stop.shouldAbort(true)); // A new streaming session may start.
    std::puts("inference_stop_signal: 6 checks PASS");
}
