#include "lifecycle_gate.h"

#include <cassert>
#include <chrono>
#include <future>
#include <thread>

using namespace std::chrono_literals;

int main() {
    stt::concurrency::LifecycleGate gate;
    {
        auto pause = gate.tryPause();
        assert(pause && gate.isClosing());
        assert(!gate.tryAcquire() && !gate.tryPause());
    }
    assert(!gate.isClosing());
    auto operation = gate.tryAcquire();
    assert(operation && gate.activeCount() == 1);
    assert(!gate.tryPause());

    // A timed-out close must reject new work until the owner retries cleanup.
    assert(!gate.closeAndWait(0ms));
    assert(gate.isClosing() && !gate.tryAcquire() && !gate.reopen());
    operation = {};
    assert(gate.closeAndWait(1s));
    assert(gate.reopen() && gate.tryAcquire());

    // A pause cannot reopen a gate that became permanently closed while it
    // held the only active token.
    {
        auto pause = gate.tryPause();
        assert(pause);
        assert(!gate.closeAndWait(0ms));
    }
    assert(gate.isClosing() && !gate.tryAcquire());
    assert(gate.closeAndWait(1s) && gate.reopen());

    // A blocking close waits for an existing operation, then permits the
    // owner to reopen after teardown has completed.
    auto held = gate.tryAcquire();
    std::promise<void> started;
    auto startedFuture = started.get_future();
    std::promise<void> drained;
    auto drainedFuture = drained.get_future();
    std::thread closer([&] {
        started.set_value();
        gate.closeAndWait();
        drained.set_value();
    });
    startedFuture.wait();
    assert(drainedFuture.wait_for(10ms) == std::future_status::timeout);
    held = {};
    assert(drainedFuture.wait_for(1s) == std::future_status::ready);
    closer.join();
    assert(gate.reopen());
}
