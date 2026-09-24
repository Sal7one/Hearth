#include "ring_buffer.h"

#include <algorithm>
#include <cassert>
#include <cstdint>
#include <deque>
#include <limits>
#include <stdexcept>
#include <vector>

int main() {
    stt::RingBuffer<int> ring;
    ring.push_back(nullptr, 0);
    ring.push_back(std::vector<int>{});
    assert(ring.empty() && ring.capacity() == 0);

    bool rejected = false;
    try { ring.push_back(nullptr, 1); }
    catch (const std::invalid_argument&) { rejected = true; }
    assert(rejected && ring.empty());

    std::deque<int> reference;
    std::uint32_t seed = 0x719c3b2dU;
    for (int step = 0; step < 10000; ++step) {
        seed = seed * 1664525U + 1013904223U;
        const std::size_t count = seed % 83U;
        if ((seed & 3U) != 0 && reference.size() < 1024) {
            std::vector<int> input(count);
            for (std::size_t i = 0; i < count; ++i) input[i] = step * 100 + static_cast<int>(i);
            ring.push_back(input);
            reference.insert(reference.end(), input.begin(), input.end());
        } else {
            std::vector<int> actual(count);
            const auto removed = ring.pop_front(actual.data(), count);
            const auto expected = std::min(count, reference.size());
            assert(removed == expected);
            for (std::size_t i = 0; i < removed; ++i) {
                assert(actual[i] == reference.front());
                reference.pop_front();
            }
        }
        assert(ring.size() == reference.size());
        assert(ring.capacity() == 0 ||
               (ring.capacity() & (ring.capacity() - 1)) == 0);
        if (step % 17 == 0) {
            const auto snapshot = ring.toVector();
            assert(std::equal(snapshot.begin(), snapshot.end(),
                              reference.begin(), reference.end()));
        }
    }

    // Appending an exposed region must survive both overlapping writes and
    // reallocation without reading a dangling pointer.
    stt::RingBuffer<int> self(4);
    const int first[] = {1, 2, 3, 4};
    self.push_back(first, 4);
    auto region = self.contiguousRegion();
    self.push_back(region.first, region.second);
    assert((self.toVector() == std::vector<int>{1, 2, 3, 4, 1, 2, 3, 4}));
    self.discard_front(5);
    region = self.contiguousRegion();
    self.push_back(region.first, region.second);
    assert((self.toVector() == std::vector<int>{2, 3, 4, 2, 3, 4}));

    rejected = false;
    try { self.peek_front(nullptr, 1); }
    catch (const std::invalid_argument&) { rejected = true; }
    assert(rejected && self.size() == 6);

    const auto before = self.toVector();
    rejected = false;
    try { self.push_back(first, std::numeric_limits<std::size_t>::max()); }
    catch (const std::length_error&) { rejected = true; }
    assert(rejected && self.toVector() == before);
    rejected = false;
    try { self.reserve(std::numeric_limits<std::size_t>::max()); }
    catch (const std::length_error&) { rejected = true; }
    assert(rejected && self.toVector() == before);
}
