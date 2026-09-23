#include "../utterance_segmenter.h"
#include <atomic>
#include <cstdlib>
#include <iostream>
#include <new>
#include <vector>

namespace {
std::atomic<int> allocations{0};
}

void* operator new(std::size_t size) {
    allocations.fetch_add(1, std::memory_order_relaxed);
    if (void* value = std::malloc(size)) return value;
    throw std::bad_alloc();
}
void operator delete(void* value) noexcept { std::free(value); }
void operator delete(void* value, std::size_t) noexcept { std::free(value); }

int main() {
    stt::speech::UtteranceSegmenter segmenter(1000, 200, -45);
    const std::vector<float> audio(16000, 0.1F);
    int emitted = 0;
    const stt::speech::UtteranceSegmenter::Consumer emit =
        [&](const std::vector<float>& segment, int64_t) {
            if (segment.size() == audio.size()) ++emitted;
        };
    allocations.store(0, std::memory_order_relaxed);
    segmenter.push(audio.data(), audio.size(), emit);
    segmenter.push(audio.data(), audio.size(), emit);
    segmenter.finish(emit);
    const int observed = allocations.load(std::memory_order_relaxed);
    if (observed != 0 || emitted != 2) {
        std::cerr << "segmenter allocations=" << observed << " emitted=" << emitted << '\n';
        return 1;
    }
    std::cout << "segmenter_allocation_test: 2 max-size utterances without allocation PASS\n";
    return 0;
}
