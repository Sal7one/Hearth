#include "lease_registry.h"
#include <atomic>
#include <cassert>
#include <chrono>
#include <future>
#include <iostream>
#include <thread>
using namespace std::chrono_literals;
namespace {
struct Session {
    std::atomic<bool> cancelled{false};
    std::atomic<int>& destroyed;
    explicit Session(std::atomic<int>& count) : destroyed(count) {}
    ~Session() { ++destroyed; }
};
using Registry = stt::concurrency::LeaseRegistry<Session>;
}
int main() {
    std::atomic<int> destroyed{0};
    Registry sessions;
    const auto first = sessions.insert(std::make_unique<Session>(destroyed));
    const auto second = sessions.insert(std::make_unique<Session>(destroyed));
    auto inFlight = sessions.acquire(first);
    auto retired = sessions.retire(first);
    assert(!sessions.acquire(first)); // A new caller cannot enter after close.
    retired.signalTarget()->cancelled.store(true);
    std::promise<void> cleanupStarted;
    auto cleanup = std::async(std::launch::async, [owner=std::move(retired), &cleanupStarted]() mutable {
        cleanupStarted.set_value();
        auto exclusive = std::move(owner).lockExclusive();
        assert(exclusive->cancelled.load());
    });
    cleanupStarted.get_future().wait();
    assert(cleanup.wait_for(20ms) == std::future_status::timeout);
    assert(destroyed == 0); // Destruction waits for inference's strong lease.
    {
        auto independent = sessions.acquire(second);
        assert(independent && !independent->cancelled.load());
        independent->cancelled.store(true);
    }
    // Closing a different session must not wait behind the first session's inference.
    { auto other = sessions.retire(second); auto exclusive = std::move(other).lockExclusive(); }
    assert(destroyed == 1);
    inFlight = {}; // Also exercises move-assignment: unlock before deleting the node.
    assert(cleanup.wait_for(2s) == std::future_status::ready);
    cleanup.get(); assert(destroyed == 2);
    assert(!sessions.retire(first));
    const auto next = sessions.insert(std::make_unique<Session>(destroyed));
    assert(next != first && next != second);
    auto held = sessions.acquire(next);
    auto last = sessions.retire(next);
    last = {}; // The outstanding lease is now the node's sole owner.
    assert(destroyed == 2);
    held = {}; assert(destroyed == 3);
    assert(sessions.size() == 0);
    std::cout << "lease_registry: independent cancellation, blocking retirement, stale handles and sole-owner move PASS\n";
}
