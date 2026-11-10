// Independent benchmark of PCM fold-down. The BS.775 coefficients and
// explicit WAVE/AAC orders are described in FilmCraft audio-dsp/channels.rs.
#include "../../../common-jni/src/main/cpp/common/audio_utils.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <limits>
#include <stdexcept>
#include <vector>

#if defined(__aarch64__) && (defined(__ARM_NEON) || defined(__ARM_NEON__))
#include <arm_neon.h>
#define HEARTH_MIX_NEON 1
#else
#define HEARTH_MIX_NEON 0
#endif

namespace {
using stt::AudioStatus;
using Clock = std::chrono::steady_clock;
constexpr float halfPower = 0.7071067811865475F;
constexpr float surroundMonoScale = 1.0F / (2.0F * halfPower + 2.0F);
constexpr float surroundStereoScale = 1.0F / (1.0F + 2.0F * halfPower);

enum class Layout { Mono, Stereo, Wave51, Aac51 };
enum class Speaker : std::size_t { Left, Right, Center, Lfe, LeftSurround, RightSurround };

struct MixPlan {
    Layout layout;
    std::size_t inputs;
    std::size_t outputs;
    std::array<std::array<float, 6>, 2> weights{};
    float headroom;
};

MixPlan make_plan(Layout layout, std::size_t outputs, bool headroom) {
    if (outputs < 1 || outputs > 2) throw std::invalid_argument("Expected mono or stereo output");
    const std::size_t inputs = layout == Layout::Mono ? 1 : layout == Layout::Stereo ? 2 : 6;
    MixPlan plan{layout, inputs, outputs, {}, 1.0F};
    if (inputs == 1) {
        plan.weights[0][0] = 1.0F;
        if (outputs == 2) plan.weights[1][0] = 1.0F;
    } else if (inputs == 2) {
        if (outputs == 2) {
            plan.weights[0][0] = 1.0F;
            plan.weights[1][1] = 1.0F;
        } else {
            plan.weights[0] = {halfPower, halfPower};
            plan.headroom = headroom ? 1.0F / (2.0F * halfPower) : 1.0F;
        }
    } else {
        const std::array<float, 6> mono{halfPower, halfPower, 1.0F, 0.0F, 0.5F, 0.5F};
        const std::array<float, 6> left{1.0F, 0.0F, halfPower, 0.0F, halfPower, 0.0F};
        const std::array<float, 6> right{0.0F, 1.0F, halfPower, 0.0F, 0.0F, halfPower};
        // MPEG-4 AAC native 5.1 order is C, L, R, Ls, Rs, LFE.
        const std::array<Speaker, 6> aacOrder{Speaker::Center, Speaker::Left, Speaker::Right,
                                             Speaker::LeftSurround, Speaker::RightSurround, Speaker::Lfe};
        for (std::size_t i = 0; i < inputs; ++i) {
            const auto index = layout == Layout::Aac51 ? static_cast<std::size_t>(aacOrder[i]) : i;
            plan.weights[0][i] = outputs == 1 ? mono[index] : left[index];
            if (outputs == 2) plan.weights[1][i] = right[index];
        }
        plan.headroom = headroom ? (outputs == 1 ? surroundMonoScale : surroundStereoScale) : 1.0F;
    }
    return plan;
}

template <typename Sample>
AudioStatus validate_buffers(const Sample* input, std::size_t samples, std::size_t channels,
                             Sample* output, std::size_t capacity, std::size_t outputs) noexcept {
    if (samples % channels != 0) return AudioStatus::INVALID_ARGUMENT;
    if (samples == 0) return AudioStatus::OK;
    if (!input || !output) return AudioStatus::INVALID_ARGUMENT;
    if (reinterpret_cast<std::uintptr_t>(input) % alignof(Sample) != 0 ||
        reinterpret_cast<std::uintptr_t>(output) % alignof(Sample) != 0) return AudioStatus::MISALIGNED;
    std::size_t required = 0, inputBytes = 0, outputBytes = 0;
    if (!stt::buffers::checkedMultiply(samples / channels, outputs, required) ||
        !stt::buffers::checkedMultiply(samples, sizeof(Sample), inputBytes) ||
        !stt::buffers::checkedMultiply(required, sizeof(Sample), outputBytes)) return AudioStatus::SIZE_OVERFLOW;
    if (capacity < required) return AudioStatus::INSUFFICIENT_CAPACITY;
    if (stt::buffers::byteRangesOverlap(input, inputBytes, output, outputBytes)) {
        return AudioStatus::OVERLAPPING_BUFFERS;
    }
    return AudioStatus::OK;
}

template <bool Specialized>
AudioStatus pcm16_mix(const std::int16_t* input, std::size_t samples, std::int16_t* output,
                      std::size_t capacity) noexcept {
    const auto status = validate_buffers(input, samples, 2, output, capacity, 1);
    if (status != AudioStatus::OK) return status;
    std::size_t frame = 0;
#if HEARTH_MIX_NEON
    if constexpr (Specialized) {
        const auto zero = vdupq_n_s16(0);
        const auto ones = vdupq_n_u16(1);
        for (; frame + 8 <= samples / 2; frame += 8) {
            const auto pair = vld2q_s16(input + frame * 2);
            const auto floor = vhaddq_s16(pair.val[0], pair.val[1]);
            const auto odd = vandq_u16(vreinterpretq_u16_s16(veorq_s16(pair.val[0], pair.val[1])), ones);
            // vhadd floors negative odd sums. Match Kotlin Int division toward zero.
            const auto correction = vandq_u16(odd, vcltq_s16(floor, zero));
            vst1q_s16(output + frame, vaddq_s16(floor, vreinterpretq_s16_u16(correction)));
        }
    }
#endif
    for (; frame < samples / 2; ++frame) {
        output[frame] = static_cast<std::int16_t>((static_cast<int>(input[frame * 2]) + input[frame * 2 + 1]) / 2);
    }
    return AudioStatus::OK;
}

void generic_mix(const MixPlan& plan, const float* input, std::size_t frames, float* output) noexcept {
    for (std::size_t frame = 0; frame < frames; ++frame) {
        for (std::size_t channel = 0; channel < plan.outputs; ++channel) {
            float value = 0;
            for (std::size_t i = 0; i < plan.inputs; ++i) value += input[i] * plan.weights[channel][i];
            output[channel] = value * plan.headroom;
        }
        input += plan.inputs;
        output += plan.outputs;
    }
}

void specialized_wave51(const MixPlan& plan, const float* input, std::size_t frames, float* output) noexcept {
    std::size_t frame = 0;
#if HEARTH_MIX_NEON
    for (; frame + 4 <= frames; frame += 4, input += 24, output += 4 * plan.outputs) {
        // Each vld3 separates two L/R/C triplets alternating with LFE/Ls/Rs.
        const auto first = vld3q_f32(input);
        const auto second = vld3q_f32(input + 12);
        const auto left = vuzp1q_f32(first.val[0], second.val[0]);
        const auto right = vuzp1q_f32(first.val[1], second.val[1]);
        const auto center = vuzp1q_f32(first.val[2], second.val[2]);
        const auto ls = vuzp2q_f32(first.val[1], second.val[1]);
        const auto rs = vuzp2q_f32(first.val[2], second.val[2]);
        if (plan.outputs == 1) {
            auto mono = vaddq_f32(vaddq_f32(vmulq_n_f32(left, halfPower), vmulq_n_f32(right, halfPower)), center);
            mono = vaddq_f32(vaddq_f32(mono, vmulq_n_f32(ls, 0.5F)), vmulq_n_f32(rs, 0.5F));
            vst1q_f32(output, vmulq_n_f32(mono, plan.headroom));
        } else {
            const auto c = vmulq_n_f32(center, halfPower);
            const auto l = vmulq_n_f32(vaddq_f32(vaddq_f32(left, c), vmulq_n_f32(ls, halfPower)), plan.headroom);
            const auto r = vmulq_n_f32(vaddq_f32(vaddq_f32(right, c), vmulq_n_f32(rs, halfPower)), plan.headroom);
            const float32x4x2_t stereo{{l, r}};
            vst2q_f32(output, stereo);
        }
    }
#endif
    for (; frame < frames; ++frame, input += 6, output += plan.outputs) {
        if (plan.outputs == 1) {
            output[0] = (halfPower * input[0] + halfPower * input[1] + input[2] +
                         0.5F * input[4] + 0.5F * input[5]) * plan.headroom;
        } else {
            output[0] = (input[0] + halfPower * input[2] + halfPower * input[4]) * plan.headroom;
            output[1] = (input[1] + halfPower * input[2] + halfPower * input[5]) * plan.headroom;
        }
    }
}

template <bool Specialized>
AudioStatus mix_f32(const MixPlan& plan, const float* input, std::size_t samples, float* output,
                    std::size_t capacity) noexcept {
    const auto status = validate_buffers(input, samples, plan.inputs, output, capacity, plan.outputs);
    if (status != AudioStatus::OK || samples == 0) return status;
    const auto finite = stt::AudioUtils::validateFinitePcm(input, samples);
    if (finite != AudioStatus::OK) return finite;
    const auto frames = samples / plan.inputs;
    if constexpr (Specialized) {
        if (plan.layout == Layout::Wave51) {
            specialized_wave51(plan, input, frames, output);
            return AudioStatus::OK;
        }
    }
    generic_mix(plan, input, frames, output);
    return AudioStatus::OK;
}

void check(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

// Force each full output buffer to escape between timed blocks.
inline void observe(const void* output) noexcept {
    __asm__ volatile("" : : "r"(output) : "memory");
}

void verify_mix() {
    // Test enough frames to exercise the SIMD and scalar tails with each isolated speaker.
    constexpr std::size_t frames = 7;
    constexpr std::array<float, 6> monoExpected{0.70710678F, 0.70710678F, 1.0F, 0.0F, 0.5F, 0.5F};
    constexpr std::array<float, 6> leftExpected{1.0F, 0.0F, 0.70710678F, 0.0F, 0.70710678F, 0.0F};
    constexpr std::array<float, 6> rightExpected{0.0F, 1.0F, 0.70710678F, 0.0F, 0.0F, 0.70710678F};
    constexpr std::array<std::size_t, 6> aacInputIndex{1, 2, 0, 5, 3, 4};
    for (auto layout : {Layout::Wave51, Layout::Aac51}) {
        for (const std::size_t channels : {1, 2}) {
            const auto plan = make_plan(layout, channels, false);
            for (std::size_t speaker = 0; speaker < 6; ++speaker) {
                std::array<float, frames * 6> input{};
                std::array<float, frames * 2> output{};
                const auto index = layout == Layout::Aac51 ? aacInputIndex[speaker] : speaker;
                for (std::size_t frame = 0; frame < frames; ++frame) input[frame * 6 + index] = 1.0F;
                check(mix_f32<true>(plan, input.data(), input.size(), output.data(), output.size()) == AudioStatus::OK,
                      "Impulse mix failed");
                for (std::size_t frame = 0; frame < frames; ++frame) {
                    const float expected = channels == 1 ? monoExpected[speaker] : leftExpected[speaker];
                    check(std::abs(output[frame * channels] - expected) < 1e-6F, "Wrong mono/left impulse gain");
                    if (channels == 2) check(std::abs(output[frame * 2 + 1] - rightExpected[speaker]) < 1e-6F,
                                             "Wrong right impulse gain");
                }
            }
            std::array<float, frames * 6> coherent;
            coherent.fill(1.0F);
            std::array<float, frames * 2> output{};
            check(mix_f32<true>(plan, coherent.data(), coherent.size(), output.data(), output.size()) == AudioStatus::OK &&
                  output[0] > 1.0F, "Raw matrix should expose coherent full-scale overshoot");
            const auto safe = make_plan(layout, channels, true);
            check(mix_f32<true>(safe, coherent.data(), coherent.size(), output.data(), output.size()) == AudioStatus::OK,
                  "Headroom mix failed");
            for (std::size_t i = 0; i < frames * channels; ++i)
                check(std::abs(output[i] - 1.0F) < 1e-6F, "Headroom changed worst-case peak");
        }
    }

    const std::array<float, 4> stereo{1.0F, -1.0F, 0.25F, 0.25F};
    std::array<float, 4> output{};
    const auto stereoMono = make_plan(Layout::Stereo, 1, true);
    check(mix_f32<true>(stereoMono, stereo.data(), stereo.size(), output.data(), output.size()) == AudioStatus::OK &&
          output[0] == 0.0F && std::abs(output[1] - 0.25F) < 1e-6F,
          "Stereo control lost cancellation or common signal");
    const auto mono = make_plan(Layout::Mono, 1, true);
    check(mix_f32<true>(mono, stereo.data(), stereo.size(), output.data(), output.size()) == AudioStatus::OK &&
          output == stereo, "Mono control changed samples");

    std::array<float, 6> invalid{0.0F, 0.0F, 0.0F, std::numeric_limits<float>::quiet_NaN()};
    output.fill(42.0F);
    const auto surround = make_plan(Layout::Wave51, 1, true);
    check(mix_f32<true>(surround, invalid.data(), invalid.size(), output.data(), output.size()) == AudioStatus::NON_FINITE,
          "Nonfinite LFE bypassed ingress checks");
    check(mix_f32<true>(surround, invalid.data(), 5, output.data(), output.size()) == AudioStatus::INVALID_ARGUMENT,
          "Accepted partial interleaved frame");
    check(mix_f32<true>(surround, invalid.data(), invalid.size(), output.data(), 0) == AudioStatus::INSUFFICIENT_CAPACITY,
          "Accepted short destination");
    check(mix_f32<true>(surround, invalid.data(), invalid.size(), invalid.data(), invalid.size()) ==
          AudioStatus::OVERLAPPING_BUFFERS, "Accepted overlapping buffers");
    alignas(float) std::array<std::uint8_t, 32> misaligned{};
    const auto* bad = reinterpret_cast<const float*>(misaligned.data() + 1);
    check(mix_f32<true>(surround, bad, 6, output.data(), output.size()) == AudioStatus::MISALIGNED,
          "Accepted unaligned float source");
    check(output == std::array<float, 4>{42, 42, 42, 42}, "Failed mix modified its output");

    const std::array<std::int16_t, 18> pcm{
        -32768, -32768, 32767, 32767, -32768, 32767, -3, 0, 3, 0, -5, 2, -1, -2, 9, -2, -32768, 0};
    const std::array<std::int16_t, 9> expected{-32768, 32767, 0, -1, 1, -1, -1, 3, -16384};
    std::array<std::int16_t, 9> actual{};
    check(pcm16_mix<true>(pcm.data(), pcm.size(), actual.data(), actual.size()) == AudioStatus::OK &&
          actual == expected, "PCM16 fold-down changed truncation or full-scale samples");
}

// One timing covers many blocks; p95 describes the distribution of trial averages.
constexpr std::size_t trials = 21;
constexpr std::size_t blocksPerTrial = 256;

template <typename A, typename B>
void measure_pair(const char* scenario, std::size_t frames, A generic, B specialized) {
    const auto time = [](auto call) {
        const auto start = Clock::now();
        for (std::size_t i = 0; i < blocksPerTrial; ++i) call();
        return std::chrono::duration<double, std::nano>(Clock::now() - start).count() / blocksPerTrial;
    };
    generic();
    specialized();
    std::array<double, trials> a{}, b{};
    for (std::size_t i = 0; i < trials; ++i) {
        if (i % 2 == 0) { a[i] = time(generic); b[i] = time(specialized); }
        else { b[i] = time(specialized); a[i] = time(generic); }
    }
    std::sort(a.begin(), a.end());
    std::sort(b.begin(), b.end());
    const std::size_t p95 = (95 * trials + 99) / 100 - 1;
    std::printf("{\"suite\":\"mix\",\"metric\":\"equivalent_policy_speed\",\"scenario\":\"%s\","
                "\"frames_per_block\":%zu,\"trials\":%zu,\"blocks_per_trial\":%zu,"
                "\"generic_trial_median_ns_per_block\":%.3f,\"specialized_trial_median_ns_per_block\":%.3f,"
                "\"generic_trial_p95_ns_per_block\":%.3f,\"specialized_trial_p95_ns_per_block\":%.3f,"
                "\"simd_neon\":%s,\"parity_verified\":true}\n",
                scenario, frames, trials, blocksPerTrial, a[trials / 2], b[trials / 2], a[p95], b[p95],
                HEARTH_MIX_NEON ? "true" : "false");
}
}  // namespace

void run_mix_benchmark() {
    verify_mix();
    // Equal-average surround is a deliberately naive reference. The actual
    // common-jni PcmWave reader accepts mono/stereo only.
    std::printf("{\"suite\":\"mix\",\"metric\":\"policy_controls\","
                "\"surround_mono_center_gain_naive_equal_average\":%.6f,"
                "\"surround_mono_center_gain_raw_matrix\":1.0,"
                "\"surround_mono_center_gain_headroom\":%.6f,"
                "\"surround_mono_lfe_gain_naive_equal_average\":%.6f,"
                "\"surround_mono_lfe_gain_matrix\":0.0,"
                "\"surround_mono_raw_peak_coherent\":%.6f,\"surround_stereo_raw_peak_coherent\":%.6f,"
                "\"explicit_wave_and_aac_order_verified\":true,\"mono_and_stereo_controls_verified\":true}\n",
                1.0 / 6.0, surroundMonoScale, 1.0 / 6.0, 1.0F / surroundMonoScale, 1.0F / surroundStereoScale);
    std::uint32_t rng = 0x56FA0C3D;
    const auto sample = [&rng]() {
        rng ^= rng << 13;
        rng ^= rng >> 17;
        rng ^= rng << 5;
        return static_cast<int>(rng & 65535U) - 32768;
    };
    for (const std::size_t frames : {256, 1024, 4096}) {
        std::vector<std::int16_t> pcm(frames * 2), pcmGeneric(frames), pcmSpecialized(frames);
        for (auto& value : pcm) value = static_cast<std::int16_t>(sample());
        const auto pcm_call = [&](auto method, auto& output) {
            check(method(pcm.data(), pcm.size(), output.data(), output.size()) == AudioStatus::OK,
                  "PCM16 timing input failed validation");
            observe(output.data());
        };
        pcm_call(pcm16_mix<false>, pcmGeneric);
        pcm_call(pcm16_mix<true>, pcmSpecialized);
        check(pcmGeneric == pcmSpecialized, "PCM16 timing outputs differ");
        measure_pair("pcm16_stereo_to_mono_legacy_average", frames, [&] { pcm_call(pcm16_mix<false>, pcmGeneric); },
                     [&] { pcm_call(pcm16_mix<true>, pcmSpecialized); });

        std::vector<float> input(frames * 6), generic(frames * 2), specialized(frames * 2);
        for (auto& value : input) value = sample() / 32768.0F;
        for (const std::size_t outputs : {1, 2}) {
            const auto plan = make_plan(Layout::Wave51, outputs, true);
            const auto call = [&](auto method, auto& output) {
                check(method(plan, input.data(), input.size(), output.data(), output.size()) == AudioStatus::OK,
                      "Float timing input failed validation");
                observe(output.data());
            };
            call(mix_f32<false>, generic);
            call(mix_f32<true>, specialized);
            for (std::size_t i = 0; i < frames * outputs; ++i) {
                check(std::abs(generic[i] - specialized[i]) < 2e-6F, "Float timing outputs differ");
            }
            measure_pair(outputs == 1 ? "f32_wave51_to_mono_headroom" : "f32_wave51_to_stereo_headroom", frames,
                         [&] { call(mix_f32<false>, generic); }, [&] { call(mix_f32<true>, specialized); });
        }
    }
}
