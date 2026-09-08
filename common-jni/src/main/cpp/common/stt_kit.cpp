// SPDX-License-Identifier: Apache-2.0
//
// common/stt_kit.cpp — Implementation of the stable public surface declared
// in `stt_kit.h`. Only the audio-math helpers are implemented for now; the
// engine handle API is declaration-only (see note in the header).

#include "stt_kit.h"

#include "audio_utils.h"
#include "logging.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

namespace {
constexpr int kVersionMajor = 1;
constexpr int kVersionMinor = 0;
constexpr int kVersionPatch = 0;

// NEON sum-of-squares for int16 buffers.
uint64_t sum_sq_int16(const int16_t* s, size_t n) noexcept {
    uint64_t acc = 0;
    size_t i = 0;
#if defined(__ARM_NEON) || defined(__aarch64__)
    uint64x2_t vacc = vdupq_n_u64(0);
    for (; i + 8 <= n; i += 8) {
        int16x8_t v = vld1q_s16(s + i);
        int16x4_t lo = vget_low_s16(v);
        int16x4_t hi = vget_high_s16(v);
        int32x4_t sq = vaddq_s32(vmull_s16(lo, lo), vmull_s16(hi, hi));
        vacc = vaddq_u64(vacc, vpaddlq_u32(vreinterpretq_u32_s32(sq)));
    }
    acc += vgetq_lane_u64(vacc, 0) + vgetq_lane_u64(vacc, 1);
#endif
    for (; i < n; ++i) {
        int32_t v = s[i];
        acc += static_cast<uint64_t>(v * v);
    }
    return acc;
}
}  // namespace

namespace stt::kit {

int version_code() noexcept {
    return kVersionMajor * 10000 + kVersionMinor * 100 + kVersionPatch;
}

const char* version_string() noexcept {
    return "1.0.0";  // Must match kVersionMajor.Minor.Patch above.
}

uint32_t capability_mask() noexcept {
    uint32_t m = 0;
#if defined(WITH_WHISPER) && WITH_WHISPER
    m |= CAP_WHISPER;
#endif
#if defined(WITH_VOSK) && WITH_VOSK
    m |= CAP_VOSK;
#endif
#if defined(WITH_ONNX) && WITH_ONNX
    m |= CAP_ONNX;
#endif
#if defined(WITH_FFMPEG) && WITH_FFMPEG
    m |= CAP_FFMPEG;
#endif
#if defined(WITH_OPENCV) && WITH_OPENCV
    m |= CAP_OPENCV;
#endif
    return m;
}

void int16_to_float(const int16_t* src, float* dst, size_t count) noexcept {
    stt::AudioUtils::int16ToFloat(src, dst, count);
}

void float_to_int16(const float* src, int16_t* dst, size_t count) noexcept {
    stt::AudioUtils::floatToInt16(src, dst, count);
}

float rms_int16(const int16_t* samples, size_t count) noexcept {
    if (!samples || count == 0) return 0.0f;
    const double mean_sq = static_cast<double>(sum_sq_int16(samples, count)) /
                           static_cast<double>(count);
    const double rms = std::sqrt(mean_sq) / 32768.0;
    return static_cast<float>(std::clamp(rms, 0.0, 1.0));
}

float rms_float(const float* samples, size_t count) noexcept {
    if (!samples || count == 0) return 0.0f;
    return stt::AudioUtils::calculateRms(samples, count);
}

size_t resample_int16_mono(
    const int16_t* src, size_t src_count, int src_rate,
    int16_t* dst, size_t dst_capacity, int dst_rate
) noexcept {
    if (!src || !dst || src_count == 0 || dst_capacity == 0 ||
        src_rate <= 0 || dst_rate <= 0) {
        return 0;
    }
    if (src_rate == dst_rate) {
        const size_t n = std::min(src_count, dst_capacity);
        std::memcpy(dst, src, n * sizeof(int16_t));
        return n;
    }

    // int16 → float → resample → int16. Use small stack buffers when possible
    // to avoid heap churn on hot paths; fall back to heap for very large
    // buffers (> 64 KB of floats = 16k samples).
    constexpr size_t kStackSamples = 4096;
    float stack_src[kStackSamples];
    float stack_dst[kStackSamples];

    const float* src_f;
    float* dst_f;
    std::vector<float> heap_src;
    std::vector<float> heap_dst;

    if (src_count <= kStackSamples) {
        stt::AudioUtils::int16ToFloat(src, stack_src, src_count);
        src_f = stack_src;
    } else {
        heap_src.resize(src_count);
        stt::AudioUtils::int16ToFloat(src, heap_src.data(), src_count);
        src_f = heap_src.data();
    }

    if (dst_capacity <= kStackSamples) {
        dst_f = stack_dst;
    } else {
        heap_dst.resize(dst_capacity);
        dst_f = heap_dst.data();
    }

    const size_t produced = stt::AudioUtils::resampleInto(
        src_f, src_count, src_rate, dst_rate, dst_f, dst_capacity);

    stt::AudioUtils::floatToInt16(dst_f, dst, produced);
    return produced;
}

// ---------------------------------------------------------------------------
// Per-thread last-error slot. Intentionally simple — no allocation on the
// hot path when there is no error.
// ---------------------------------------------------------------------------
namespace {
thread_local std::string t_last_error;
}  // namespace

const char* last_error() noexcept {
    return t_last_error.c_str();
}

void clear_error() noexcept {
    t_last_error.clear();
}

}  // namespace stt::kit
