#include "buffer_pool.h"

#include <cstdio>
#include <cstdlib>
#include <utility>

namespace {
int checks = 0;
void check(bool condition, const char* message) {
    ++checks;
    if (!condition) {
        std::fprintf(stderr, "buffer_pool FAIL: %s\n", message);
        std::exit(1);
    }
}
}

int main() {
    stt::BufferPool<int> pool(4, 2);
    int foreign[4] = {};
    auto* first = pool.acquire();
    auto* second = pool.acquire();
    check(first && second && first != second, "two borrowers get distinct slots");
    check(pool.active() == 2 && pool.available() == 0, "both slots are owned");
    check(pool.tryRelease(first), "first release succeeds");
    check(!pool.tryRelease(first), "double release is rejected");
    check(!pool.tryRelease(foreign), "foreign pointer is rejected");
    check(!pool.tryRelease(first + 1), "interior pointer is rejected");
    check(pool.active() == 1 && pool.available() == 1, "bad releases leave counts unchanged");

    auto* third = pool.acquire();
    check(third == first && third != second, "only the released slot is reused");
    pool.resetAll();
    check(pool.active() == 2 && pool.available() == 0, "reset preserves outstanding buffers");
    check(pool.acquire() == nullptr, "reset cannot lend an owned slot again");
    check(pool.tryRelease(second) && pool.tryRelease(third), "both owners can release after reset");
    pool.resetAll();
    auto* again = pool.acquire();
    auto* last = pool.acquire();
    check(again && last && again != last, "reset rebuilds a unique free list");
    pool.release(again);
    pool.release(last);

    {
        auto owner = pool.acquirePooled();
        check(owner.valid(), "RAII acquisition returns a buffer");
        auto moved = std::move(owner);
        check(!owner.valid() && moved.valid(), "move transfers one ownership");
    }
    check(pool.active() == 0 && pool.available() == 2, "RAII destruction returns one slot");

    check(!stt::AudioBufferPool::wasInitialized(), "stats start without allocating audio pools");
    const auto idleStats = stt::AudioBufferPool::snapshotStats();
    check(idleStats.availableInt16 == stt::AudioBufferPool::POOL_SIZE &&
          idleStats.availableFloat == stt::AudioBufferPool::POOL_SIZE &&
          !stt::AudioBufferPool::wasInitialized(), "idle stats avoid singleton allocation");
    auto& audio = stt::AudioBufferPool::getInstance();
    auto* audioBuffer = audio.acquireFloat();
    check(stt::AudioBufferPool::snapshotStats().availableFloat ==
          stt::AudioBufferPool::POOL_SIZE - 1, "initialized stats report live ownership");
    audio.releaseFloat(audioBuffer);

    std::printf("buffer_pool: %d checks PASS\n", checks);
}
