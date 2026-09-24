#include "audio_utils.h"

#include <cassert>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

namespace {

void checkChunks(int sourceRate, int destinationRate, std::size_t count) {
    std::vector<float> input(count);
    for (std::size_t index = 0; index < count; ++index) {
        input[index] = static_cast<float>(index) / static_cast<float>(count);
    }

    stt::AudioStreamResampler oneShot;
    stt::AudioStreamResampler chunked;
    assert(oneShot.configure(sourceRate, destinationRate));
    assert(chunked.configure(sourceRate, destinationRate));
    std::vector<float> expected;
    std::vector<float> actual;
    assert(oneShot.push(input.data(), input.size(), expected));

    std::uint32_t seed = 0x51A7E123U;
    for (std::size_t offset = 0; offset < count;) {
        seed = seed * 1664525U + 1013904223U;
        const std::size_t size = std::min<std::size_t>(
            count - offset, static_cast<std::size_t>(seed % 2048U) + 1U);
        assert(chunked.push(input.data() + offset, size, actual));
        offset += size;
    }
    const auto wantedCount =
        (static_cast<std::uint64_t>(count - 1) * destinationRate) / sourceRate + 1;
    assert(expected.size() == wantedCount);
    assert(actual.size() == expected.size());
    for (std::size_t index = 0; index < expected.size(); ++index) {
        assert(actual[index] == expected[index]);
        const double sourcePosition =
            static_cast<double>(index) * sourceRate / destinationRate;
        const double rampValue = sourcePosition / static_cast<double>(count);
        assert(std::abs(static_cast<double>(actual[index]) - rampValue) < 1e-6);
    }
}

} // namespace

int main() {
    checkChunks(44100, 16000, 44100);
    checkChunks(16000, 24000, 16000);
    checkChunks(16000, 16000, 16000);

    stt::AudioStreamResampler resampler;
    std::vector<float> output;
    float sample = 0.25F;
    assert(!resampler.push(&sample, 1, output));
    assert(!resampler.configure(0, 16000));
    assert(resampler.configure(48000, 16000));
    assert(resampler.push(&sample, 1, output));
    assert(output.size() == 1);
    const float invalid = std::numeric_limits<float>::quiet_NaN();
    assert(resampler.push(&invalid, 1, output).status == stt::AudioStatus::NON_FINITE);
    assert(output.size() == 1);
    assert(resampler.push(&sample, 1, output));
    assert(output.size() == 1); // No output is due until another input sample arrives.
    resampler.reset();
    assert(resampler.push(&sample, 1, output));
    assert(output.size() == 2);
    assert(resampler.push(nullptr, 1, output).status == stt::AudioStatus::INVALID_ARGUMENT);
}
