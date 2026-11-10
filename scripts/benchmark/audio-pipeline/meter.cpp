// Inspired by SoundCraft's allocation-free block metering and peak accumulation.
// https://github.com/storytold/soundcraft/blob/ef58a90f4a418a282a15fc71d2e03c3d8e8f40fa/crates/mix/src/meter.rs
// The fused kernels are Hearth benchmark candidates. They measure sample peak,
// rather than the oversampled true peak or K-weighted LUFS of FilmCraft's meter.
#include "../../../common-jni/src/main/cpp/common/audio_utils.h"
#include "../../../common-jni/src/main/cpp/common/stt_kit.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <stdexcept>
#include <type_traits>
#include <vector>

#if defined(__aarch64__) && (defined(__ARM_NEON) || defined(__ARM_NEON__))
#include <arm_neon.h>
#define HEARTH_METER_ARM64_NEON 1
#else
#define HEARTH_METER_ARM64_NEON 0
#endif

namespace {

struct Meter {
    float rms = 0.0F;
    float peak = 0.0F;
    std::size_t clipped = 0;
};

constexpr float pcm16Scale = 1.0F / 32768.0F;

Meter invalid_meter() {
    const auto invalid = std::numeric_limits<float>::quiet_NaN();
    return {invalid, invalid, 0};
}

std::uint32_t pcm16_magnitude(std::int16_t sample) {
    const auto value = static_cast<std::int32_t>(sample);
    return static_cast<std::uint32_t>(value < 0 ? -value : value);
}

bool pcm16_at_rail(std::int16_t value) {
    return value == std::numeric_limits<std::int16_t>::min() ||
           value == std::numeric_limits<std::int16_t>::max();
}

#if HEARTH_METER_ARM64_NEON
uint16x8_t pcm16_rails(int16x8_t values) {
    const auto lo = vceqq_s16(values, vdupq_n_s16(-32768));
    const auto hi = vceqq_s16(values, vdupq_n_s16(32767));
    return vshrq_n_u16(vorrq_u16(lo, hi), 15);
}
#endif

template <typename Sample>
bool aligned(const Sample* samples) {
    return samples != nullptr &&
           reinterpret_cast<std::uintptr_t>(samples) % alignof(Sample) == 0;
}

template <typename Sample>
Meter rms_only(const Sample* samples, std::size_t count) {
    if constexpr (std::is_same_v<Sample, float>) {
        return {stt::AudioUtils::calculateRms(samples, count), 0.0F, 0};
    } else {
        return {stt::kit::rms_int16(samples, count), 0.0F, 0};
    }
}

std::uint32_t peak_pcm16(const std::int16_t* samples, std::size_t count) {
    std::uint32_t peak = 0;
    std::size_t i = 0;
#if HEARTH_METER_ARM64_NEON
    auto peaks = vdupq_n_u16(0);
    for (; count - i >= 8; i += 8) {
        // vabs leaves INT16_MIN as 0x8000. Treat that magnitude as unsigned.
        const auto values = vreinterpretq_u16_s16(vabsq_s16(vld1q_s16(samples + i)));
        peaks = vmaxq_u16(peaks, values);
    }
    peak = vmaxvq_u16(peaks);
#endif
    for (; i < count; ++i) peak = std::max(peak, pcm16_magnitude(samples[i]));
    return peak;
}

std::size_t clips_pcm16(const std::int16_t* samples, std::size_t count) {
    std::size_t clips = 0;
    std::size_t i = 0;
#if HEARTH_METER_ARM64_NEON
    auto counts = vdupq_n_u64(0);
    for (; count - i >= 8; i += 8) {
        const auto at_rail = pcm16_rails(vld1q_s16(samples + i));
        counts = vaddq_u64(counts, vpaddlq_u32(vpaddlq_u16(at_rail)));
    }
    clips = static_cast<std::size_t>(vaddvq_u64(counts));
#endif
    for (; i < count; ++i) clips += pcm16_at_rail(samples[i]);
    return clips;
}

float peak_float(const float* samples, std::size_t count) {
    float peak = 0.0F;
    std::size_t i = 0;
#if HEARTH_METER_ARM64_NEON
    auto peaks = vdupq_n_f32(0.0F);
    for (; count - i >= 4; i += 4) peaks = vmaxq_f32(peaks, vabsq_f32(vld1q_f32(samples + i)));
    peak = vmaxvq_f32(peaks);
#endif
    for (; i < count; ++i) peak = std::max(peak, std::abs(samples[i]));
    return peak;
}

std::size_t clips_float(const float* samples, std::size_t count) {
    std::size_t clips = 0;
    std::size_t i = 0;
#if HEARTH_METER_ARM64_NEON
    auto counts = vdupq_n_u64(0);
    const auto rail = vdupq_n_f32(1.0F);
    for (; count - i >= 4; i += 4) {
        const auto at_rail = vshrq_n_u32(vcgeq_f32(vabsq_f32(vld1q_f32(samples + i)), rail), 31);
        counts = vaddq_u64(counts, vpaddlq_u32(at_rail));
    }
    clips = static_cast<std::size_t>(vaddvq_u64(counts));
#endif
    for (; i < count; ++i) clips += std::abs(samples[i]) >= 1.0F;
    return clips;
}

template <typename Sample>
Meter separate(const Sample* samples, std::size_t count) {
    if (count == 0) return {};
    if (!aligned(samples)) return invalid_meter();
    const auto rms = rms_only(samples, count).rms;
    if (!std::isfinite(rms)) return invalid_meter();
    if constexpr (std::is_same_v<Sample, float>) {
        return {rms, peak_float(samples, count), clips_float(samples, count)};
    } else {
        return {rms, static_cast<float>(peak_pcm16(samples, count)) * pcm16Scale,
                clips_pcm16(samples, count)};
    }
}

Meter fused_pcm16(const std::int16_t* samples, std::size_t count) {
    if (count == 0) return {};
    if (!aligned(samples)) return invalid_meter();
    std::uint64_t sum = 0;
    std::uint32_t peak = 0;
    std::size_t clips = 0;
    std::size_t i = 0;
#if HEARTH_METER_ARM64_NEON
    auto sums = vdupq_n_u64(0);
    auto peaks = vdupq_n_u16(0);
    auto counts = vdupq_n_u64(0);
    for (; count - i >= 8; i += 8) {
        const auto samples8 = vld1q_s16(samples + i);
        const auto low = vget_low_s16(samples8);
        const auto high = vget_high_s16(samples8);
        const auto squares = vaddq_u32(vreinterpretq_u32_s32(vmull_s16(low, low)),
                                       vreinterpretq_u32_s32(vmull_s16(high, high)));
        sums = vaddq_u64(sums, vpaddlq_u32(squares));
        const auto magnitudes = vreinterpretq_u16_s16(vabsq_s16(samples8));
        peaks = vmaxq_u16(peaks, magnitudes);
        const auto at_rail = pcm16_rails(samples8);
        counts = vaddq_u64(counts, vpaddlq_u32(vpaddlq_u16(at_rail)));
    }
    sum = vaddvq_u64(sums);
    peak = vmaxvq_u16(peaks);
    clips = static_cast<std::size_t>(vaddvq_u64(counts));
#endif
    for (; i < count; ++i) {
        const auto magnitude = pcm16_magnitude(samples[i]);
        sum += static_cast<std::uint64_t>(magnitude) * magnitude;
        peak = std::max(peak, magnitude);
        clips += pcm16_at_rail(samples[i]);
    }
    const auto mean_square = static_cast<double>(sum) / static_cast<double>(count);
    return {static_cast<float>(std::sqrt(mean_square) * pcm16Scale),
            static_cast<float>(peak) * pcm16Scale, clips};
}

Meter fused_float(const float* samples, std::size_t count) {
    if (count == 0) return {};
    if (!aligned(samples)) return invalid_meter();
    double sum = 0.0;
    float peak = 0.0F;
    std::size_t clips = 0;
    std::size_t i = 0;
#if HEARTH_METER_ARM64_NEON
    auto low_sum = vdupq_n_f64(0.0);
    auto high_sum = vdupq_n_f64(0.0);
    auto peaks = vdupq_n_f32(0.0F);
    auto counts = vdupq_n_u64(0);
    const auto rail = vdupq_n_f32(1.0F);
    for (; count - i >= 4; i += 4) {
        const auto values = vld1q_f32(samples + i);
        const auto low = vcvt_f64_f32(vget_low_f32(values));
        const auto high = vcvt_f64_f32(vget_high_f32(values));
        low_sum = vfmaq_f64(low_sum, low, low);
        high_sum = vfmaq_f64(high_sum, high, high);
        const auto magnitudes = vabsq_f32(values);
        peaks = vmaxq_f32(peaks, magnitudes);
        const auto at_rail = vshrq_n_u32(vcgeq_f32(magnitudes, rail), 31);
        counts = vaddq_u64(counts, vpaddlq_u32(at_rail));
    }
    sum = vaddvq_f64(low_sum) + vaddvq_f64(high_sum);
    peak = vmaxvq_f32(peaks);
    clips = static_cast<std::size_t>(vaddvq_u64(counts));
#endif
    for (; i < count; ++i) {
        const auto value = static_cast<double>(samples[i]);
        sum += value * value;
        const auto magnitude = std::abs(samples[i]);
        peak = std::max(peak, magnitude);
        clips += magnitude >= 1.0F;
    }
    if (!std::isfinite(sum)) return invalid_meter();
    return {static_cast<float>(std::sqrt(sum / static_cast<double>(count))), peak, clips};
}

void check(bool condition, const char* error) {
    if (!condition) throw std::runtime_error(error);
}

void verify_equal(Meter expected, Meter actual) {
    check(expected.peak == actual.peak && expected.clipped == actual.clipped,
          "Fused meter changed peak or clip count");
    const auto tolerance = 2.0F * std::numeric_limits<float>::epsilon() * expected.rms;
    check(std::abs(expected.rms - actual.rms) <= tolerance, "Fused meter changed RMS");
}

void verify_contract() {
    constexpr std::array<std::int16_t, 9> rails{-32768, 32767, -32767, -1, 0, 1, 100, 32766, -32768};
    const auto pcm = fused_pcm16(rails.data(), rails.size());
    check(pcm.peak == 1.0F && pcm.clipped == 3, "PCM16 rail detection failed");
    verify_equal(separate(rails.data(), rails.size()), pcm);
    constexpr std::array<float, 5> over{1.2F, -1.0F, 1.0F, 0.9F, -0.1F};
    const auto float_meter = fused_float(over.data(), over.size());
    check(float_meter.peak == 1.2F && float_meter.clipped == 3, "Float clip detection failed");
    verify_equal(separate(over.data(), over.size()), float_meter);
    for (const auto bad : {std::numeric_limits<float>::quiet_NaN(),
                           std::numeric_limits<float>::infinity(),
                           -std::numeric_limits<float>::infinity()}) {
        for (std::size_t offset = 0; offset < over.size(); ++offset) {
            auto corrupt = over;
            corrupt[offset] = bad;
            check(std::isnan(rms_only(corrupt.data(), corrupt.size()).rms) &&
                      std::isnan(separate(corrupt.data(), corrupt.size()).rms) &&
                      std::isnan(fused_float(corrupt.data(), corrupt.size()).rms),
                  "Non-finite float meter input was accepted");
        }
    }
    verify_equal({}, fused_float(nullptr, 0));
    verify_equal({}, fused_pcm16(nullptr, 0));
    check(std::isnan(fused_float(nullptr, 1).rms) && std::isnan(fused_pcm16(nullptr, 1).rms),
          "Meter accepted null nonempty input");
}

constexpr std::size_t trialCount = 6;
constexpr std::size_t timedBatchesPerTrial = 64;
constexpr std::size_t blocksPerBatch = 8;
constexpr std::size_t inputBlocks = 128;
volatile double measurement_sink = 0.0;

template <typename Sample>
using Measure = Meter (*)(const Sample*, std::size_t);

template <typename Sample>
void time_batches(const std::vector<Sample>& samples, std::size_t block_size,
                  Measure<Sample> measure, std::vector<double>& results) {
    using Clock = std::chrono::steady_clock;
    for (std::size_t batch = 0; batch < timedBatchesPerTrial; ++batch) {
        double total = 0.0;
        const auto start = Clock::now();
        for (std::size_t block = 0; block < blocksPerBatch; ++block) {
            const auto offset = ((batch * blocksPerBatch + block) % inputBlocks) * block_size;
            const auto meter = measure(samples.data() + offset, block_size);
            total += static_cast<double>(meter.rms) + meter.peak + static_cast<double>(meter.clipped);
        }
        const auto elapsed = std::chrono::duration<double, std::micro>(Clock::now() - start);
        measurement_sink = total;
        results.push_back(elapsed.count() / blocksPerBatch);
    }
}

template <typename Sample>
void compare(std::size_t block_size, const char* format, Measure<Sample> fused) {
    const std::array<Measure<Sample>, 3> methods{rms_only<Sample>, separate<Sample>, fused};
    const std::array<const char*, 3> names{"hearth_rms_only", "separate_rms_peak_clips", "fused_rms_peak_clips"};
    std::vector<Sample> samples(inputBlocks * block_size);
    std::uint32_t rng = 0x71c348a5;
    for (std::size_t i = 0; i < samples.size(); ++i) {
        rng ^= rng << 13;
        rng ^= rng >> 17;
        rng ^= rng << 5;
        const auto value = i % 79 == 0 ? -32768 :
                           i % 89 == 0 ? 32767 : static_cast<int>(rng & 0xffffu) - 32768;
        if constexpr (std::is_same_v<Sample, float>) {
            samples[i] = static_cast<float>(value) * (1.2F * pcm16Scale);
        } else {
            samples[i] = static_cast<std::int16_t>(value);
        }
    }
    for (std::size_t i = 0; i < inputBlocks; ++i) {
        const auto* block = samples.data() + i * block_size;
        verify_equal(separate(block, block_size), fused(block, block_size));
    }
    std::array<std::vector<double>, 3> timings;
    for (auto& values : timings) values.reserve(trialCount * timedBatchesPerTrial);
    for (std::size_t trial = 0; trial < trialCount; ++trial) {
        for (std::size_t slot = 0; slot < methods.size(); ++slot) {
            const auto method = (trial + slot) % methods.size();
            time_batches(samples, block_size, methods[method], timings[method]);
        }
    }
    for (std::size_t method = 0; method < methods.size(); ++method) {
        auto& values = timings[method];
        std::sort(values.begin(), values.end());
        const auto p95 = values[(95 * values.size() + 99) / 100 - 1];
        std::printf(
            "{\"suite\":\"meter\",\"format\":\"%s\",\"method\":\"%s\","
            "\"block_samples\":%zu,\"trials\":%zu,\"timed_batches\":%zu,"
            "\"blocks_per_timing_sample\":%zu,\"p95_us_per_block\":%.6f,"
            "\"equal_work\":%s,\"parity_verified\":true,\"arm64_neon\":%s}\n",
            format, names[method], block_size, trialCount, values.size(), blocksPerBatch,
            p95, method == 0 ? "false" : "true",
            HEARTH_METER_ARM64_NEON ? "true" : "false");
    }
}

}  // namespace

void run_meter_benchmark() {
    verify_contract();
    // 128/480/1600 are common callback, short capture and 100 ms STT blocks.
    for (const std::size_t block : {128, 480, 1600}) {
        compare(block, "pcm16", fused_pcm16);
        compare(block, "float32", fused_float);
    }
}
