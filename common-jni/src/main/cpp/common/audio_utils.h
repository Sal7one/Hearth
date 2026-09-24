#ifndef AUDIO_UTILS_H
#define AUDIO_UTILS_H

#include "buffer_view.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <vector>

#if !defined(STT_AUDIO_UTILS_FORCE_SCALAR) && \
    (defined(__ARM_NEON) || defined(__ARM_NEON__))
#include <arm_neon.h>
#define STT_AUDIO_UTILS_HAS_NEON 1
#else
#define STT_AUDIO_UTILS_HAS_NEON 0
#endif

#if STT_AUDIO_UTILS_HAS_NEON && defined(__aarch64__)
#define STT_AUDIO_UTILS_HAS_AARCH64_NEON 1
#else
#define STT_AUDIO_UTILS_HAS_AARCH64_NEON 0
#endif

namespace stt {

enum class AudioStatus : std::uint8_t {
    OK = 0,
    INVALID_ARGUMENT,
    MISALIGNED,
    NON_FINITE,
    SIZE_OVERFLOW,
    INSUFFICIENT_CAPACITY,
    OVERLAPPING_BUFFERS,
};

enum class NonFinitePolicy : std::uint8_t {
    REJECT,
    ZERO,
};

struct AudioResult {
    AudioStatus status = AudioStatus::OK;
    std::size_t written = 0;
    std::size_t required = 0;

    explicit operator bool() const noexcept { return status == AudioStatus::OK; }
};

inline const char* audioStatusMessage(AudioStatus status) noexcept {
    switch (status) {
        case AudioStatus::OK: return "ok";
        case AudioStatus::INVALID_ARGUMENT: return "invalid argument";
        case AudioStatus::MISALIGNED: return "misaligned typed buffer";
        case AudioStatus::NON_FINITE: return "non-finite sample";
        case AudioStatus::SIZE_OVERFLOW: return "audio size overflow";
        case AudioStatus::INSUFFICIENT_CAPACITY: return "insufficient output capacity";
        case AudioStatus::OVERLAPPING_BUFFERS: return "unsupported buffer overlap";
    }
    return "unknown audio status";
}

/**
 * Portable, allocation-free audio kernels with optional ARM NEON acceleration.
 *
 * The checked APIs return an AudioResult, validate complete output capacity
 * before writing, and never allocate or throw. Convenience vector wrappers are
 * retained for existing engine callers. The linear resampler is intentionally
 * named: it is suitable for speech buffers that are already band-limited, not
 * a replacement for a stateful anti-aliasing sample-rate converter.
 */
class AudioUtils {
public:
    static constexpr bool hasNeon() noexcept {
        return STT_AUDIO_UTILS_HAS_NEON != 0;
    }

    /** Validate float PCM before an engine buffers or decodes it unchanged. */
    static AudioStatus validateFinitePcm(
        const float* samples, std::size_t count
    ) noexcept {
        if (!samples && count != 0) return AudioStatus::INVALID_ARGUMENT;
        for (std::size_t index = 0; index < count; ++index) {
            if (!std::isfinite(samples[index])) return AudioStatus::NON_FINITE;
        }
        return AudioStatus::OK;
    }

    static AudioResult pcm16ToFloat(
        const std::int16_t* source,
        std::size_t count,
        float* destination,
        std::size_t destinationCapacity
    ) noexcept {
        const AudioResult validation = validateConversion(
            source, count, destination, destinationCapacity
        );
        if (!validation) return validation;
        if (count == 0) return validation;

#if STT_AUDIO_UTILS_HAS_NEON
        const float32x4_t scale = vdupq_n_f32(1.0F / 32768.0F);
        std::size_t index = 0;
        for (; count - index >= 8; index += 8) {
            const int16x8_t samples = vld1q_s16(source + index);
            const int32x4_t low = vmovl_s16(vget_low_s16(samples));
            const int32x4_t high = vmovl_s16(vget_high_s16(samples));
            vst1q_f32(destination + index, vmulq_f32(vcvtq_f32_s32(low), scale));
            vst1q_f32(destination + index + 4, vmulq_f32(vcvtq_f32_s32(high), scale));
        }
        for (; index < count; ++index) {
            destination[index] = static_cast<float>(source[index]) / 32768.0F;
        }
#else
        constexpr float scale = 1.0F / 32768.0F;
        for (std::size_t index = 0; index < count; ++index) {
            destination[index] = static_cast<float>(source[index]) * scale;
        }
#endif
        return validation;
    }

    static void int16ToFloat(
        const std::int16_t* source,
        float* destination,
        std::size_t count
    ) noexcept {
        (void)pcm16ToFloat(source, count, destination, count);
    }

    static AudioResult floatToPcm16(
        const float* source,
        std::size_t count,
        std::int16_t* destination,
        std::size_t destinationCapacity,
        NonFinitePolicy nonFinitePolicy = NonFinitePolicy::REJECT
    ) noexcept {
        const AudioResult validation = validateConversion(
            source, count, destination, destinationCapacity
        );
        if (!validation) return validation;
        if (count == 0) return validation;
        if (nonFinitePolicy != NonFinitePolicy::REJECT &&
            nonFinitePolicy != NonFinitePolicy::ZERO) {
            return {AudioStatus::INVALID_ARGUMENT, 0, count};
        }

        if (nonFinitePolicy == NonFinitePolicy::REJECT) {
            for (std::size_t index = 0; index < count; ++index) {
                if (!std::isfinite(source[index])) {
                    return {AudioStatus::NON_FINITE, 0, count};
                }
            }
        }

#if STT_AUDIO_UTILS_HAS_NEON
        const float32x4_t zero = vdupq_n_f32(0.0F);
        const float32x4_t negativeOne = vdupq_n_f32(-1.0F);
        const float32x4_t positiveOne = vdupq_n_f32(1.0F);
        const float32x4_t largestFinite = vdupq_n_f32(std::numeric_limits<float>::max());
        const float32x4_t scale = vdupq_n_f32(32768.0F);
        const float32x4_t minimum = vdupq_n_f32(-32768.0F);
        const float32x4_t maximum = vdupq_n_f32(32767.0F);
        std::size_t index = 0;
        for (; count - index >= 8; index += 8) {
            float32x4_t low = vld1q_f32(source + index);
            float32x4_t high = vld1q_f32(source + index + 4);
            if (nonFinitePolicy == NonFinitePolicy::ZERO) {
                const uint32x4_t lowFinite = vcleq_f32(vabsq_f32(low), largestFinite);
                const uint32x4_t highFinite = vcleq_f32(vabsq_f32(high), largestFinite);
                low = vbslq_f32(lowFinite, low, zero);
                high = vbslq_f32(highFinite, high, zero);
            }
            low = vmulq_f32(vmaxq_f32(vminq_f32(low, positiveOne), negativeOne), scale);
            high = vmulq_f32(vmaxq_f32(vminq_f32(high, positiveOne), negativeOne), scale);
            low = vmaxq_f32(vminq_f32(low, maximum), minimum);
            high = vmaxq_f32(vminq_f32(high, maximum), minimum);
            const int16x8_t converted = vcombine_s16(
                vqmovn_s32(vcvtq_s32_f32(low)),
                vqmovn_s32(vcvtq_s32_f32(high))
            );
            vst1q_s16(destination + index, converted);
        }
        for (; index < count; ++index) {
            destination[index] = floatSampleToPcm16(source[index], nonFinitePolicy);
        }
#else
        for (std::size_t index = 0; index < count; ++index) {
            destination[index] = floatSampleToPcm16(source[index], nonFinitePolicy);
        }
#endif
        return validation;
    }

    static void floatToInt16(
        const float* source,
        std::int16_t* destination,
        std::size_t count
    ) noexcept {
        (void)floatToPcm16(
            source, count, destination, count, NonFinitePolicy::ZERO
        );
    }

    static AudioStatus requiredResampleOutputSize(
        std::size_t sourceCount,
        int sourceRate,
        int destinationRate,
        std::size_t& required
    ) noexcept {
        required = 0;
        if (sourceRate <= 0 || destinationRate <= 0) {
            return AudioStatus::INVALID_ARGUMENT;
        }
        if (sourceCount == 0) return AudioStatus::OK;
        if (sourceRate == destinationRate) {
            required = sourceCount;
            return AudioStatus::OK;
        }

        const std::size_t sourceRateSize = static_cast<std::size_t>(sourceRate);
        const std::size_t destinationRateSize = static_cast<std::size_t>(destinationRate);
        const std::size_t quotient = sourceCount / sourceRateSize;
        const std::size_t remainder = sourceCount % sourceRateSize;
        std::size_t whole = 0;
        if (!buffers::checkedMultiply(quotient, destinationRateSize, whole)) {
            return AudioStatus::SIZE_OVERFLOW;
        }

        const std::uint64_t remainderProduct =
            static_cast<std::uint64_t>(remainder) *
            static_cast<std::uint64_t>(destinationRateSize);
        const std::uint64_t roundedTail =
            (remainderProduct + static_cast<std::uint64_t>(sourceRateSize) - 1U) /
            static_cast<std::uint64_t>(sourceRateSize);
        if (roundedTail > std::numeric_limits<std::size_t>::max() ||
            !buffers::checkedAdd(whole, static_cast<std::size_t>(roundedTail), required)) {
            return AudioStatus::SIZE_OVERFLOW;
        }
        return AudioStatus::OK;
    }

    static AudioResult resampleLinearInto(
        const float* source,
        std::size_t sourceCount,
        int sourceRate,
        int destinationRate,
        float* destination,
        std::size_t destinationCapacity
    ) noexcept {
        std::size_t required = 0;
        const AudioStatus sizeStatus = requiredResampleOutputSize(
            sourceCount, sourceRate, destinationRate, required
        );
        if (sizeStatus != AudioStatus::OK) return {sizeStatus, 0, 0};
        if (sourceCount == 0) return {AudioStatus::OK, 0, 0};
        if (!source || !destination) return {AudioStatus::INVALID_ARGUMENT, 0, required};
        if (!isAligned(source) || !isAligned(destination)) {
            return {AudioStatus::MISALIGNED, 0, required};
        }
        if (destinationCapacity < required) {
            return {AudioStatus::INSUFFICIENT_CAPACITY, 0, required};
        }

        std::size_t sourceBytes = 0;
        std::size_t destinationBytes = 0;
        if (!buffers::checkedMultiply(sourceCount, sizeof(float), sourceBytes) ||
            !buffers::checkedMultiply(required, sizeof(float), destinationBytes)) {
            return {AudioStatus::SIZE_OVERFLOW, 0, required};
        }
        if (sourceRate == destinationRate) {
            for (std::size_t index = 0; index < sourceCount; ++index) {
                if (!std::isfinite(source[index])) {
                    return {AudioStatus::NON_FINITE, 0, required};
                }
            }
            std::memmove(destination, source, sourceBytes);
            return {AudioStatus::OK, required, required};
        }
        if (rangesOverlap(source, sourceBytes, destination, destinationBytes)) {
            return {AudioStatus::OVERLAPPING_BUFFERS, 0, required};
        }
        for (std::size_t index = 0; index < sourceCount; ++index) {
            if (!std::isfinite(source[index])) {
                return {AudioStatus::NON_FINITE, 0, required};
            }
        }

        resampleLinearKernel(
            source, sourceCount, sourceRate, destinationRate, destination, required
        );
        return {AudioStatus::OK, required, required};
    }

    static std::vector<float> resampleLinear(
        const float* source,
        std::size_t sourceCount,
        int sourceRate,
        int destinationRate
    ) {
        std::size_t required = 0;
        if (requiredResampleOutputSize(sourceCount, sourceRate, destinationRate, required) !=
            AudioStatus::OK) {
            return {};
        }
        if (required == 0) return {};
        if (!source) return {};
        std::vector<float> destination(required);
        const AudioResult result = resampleLinearInto(
            source, sourceCount, sourceRate, destinationRate,
            destination.data(), destination.size()
        );
        return result ? destination : std::vector<float>{};
    }

    // Compatibility wrappers retained for current engine and JNI callers.
    static std::vector<float> resample(
        const float* source,
        std::size_t sourceCount,
        int sourceRate,
        int destinationRate
    ) {
        return resampleLinear(source, sourceCount, sourceRate, destinationRate);
    }

    static std::size_t resampleInto(
        const float* source,
        std::size_t sourceCount,
        int sourceRate,
        int destinationRate,
        float* destination,
        std::size_t destinationCapacity
    ) noexcept {
        std::size_t required = 0;
        if (requiredResampleOutputSize(
                sourceCount, sourceRate, destinationRate, required
            ) != AudioStatus::OK || sourceCount == 0 || destinationCapacity == 0 ||
            !source || !destination || !isAligned(source) || !isAligned(destination)) {
            return 0;
        }
        if (destinationCapacity >= required) {
            const AudioResult result = resampleLinearInto(
                source, sourceCount, sourceRate, destinationRate,
                destination, destinationCapacity
            );
            return result ? result.written : 0;
        }

        // Preserve the historical prefix-writing contract for existing native
        // callers. New integrations should use resampleLinearInto(), whose
        // explicit result is deliberately all-or-nothing.
        std::size_t sourceBytes = 0;
        std::size_t destinationBytes = 0;
        if (!buffers::checkedMultiply(sourceCount, sizeof(float), sourceBytes) ||
            !buffers::checkedMultiply(
                destinationCapacity, sizeof(float), destinationBytes
            )) {
            return 0;
        }
        for (std::size_t index = 0; index < sourceCount; ++index) {
            if (!std::isfinite(source[index])) return 0;
        }
        if (sourceRate == destinationRate) {
            std::memmove(destination, source, destinationBytes);
            return destinationCapacity;
        }
        if (rangesOverlap(source, sourceBytes, destination, destinationBytes)) return 0;
        resampleLinearKernel(
            source, sourceCount, sourceRate, destinationRate,
            destination, destinationCapacity
        );
        return destinationCapacity;
    }

    static std::size_t calculateResampleOutputSize(
        std::size_t sourceCount,
        int sourceRate,
        int destinationRate
    ) noexcept {
        std::size_t required = 0;
        return requiredResampleOutputSize(
            sourceCount, sourceRate, destinationRate, required
        ) == AudioStatus::OK ? required : 0;
    }

    static float calculateRms(const float* samples, std::size_t count) noexcept {
        if (count == 0) return 0.0F;
        if (!samples || !isAligned(samples)) {
            return std::numeric_limits<float>::quiet_NaN();
        }

        double sum = 0.0;
#if STT_AUDIO_UTILS_HAS_AARCH64_NEON
        float64x2_t lowSum = vdupq_n_f64(0.0);
        float64x2_t highSum = vdupq_n_f64(0.0);
        std::size_t index = 0;
        for (; count - index >= 4; index += 4) {
            const float32x4_t values = vld1q_f32(samples + index);
            const float64x2_t low = vcvt_f64_f32(vget_low_f32(values));
            const float64x2_t high = vcvt_f64_f32(vget_high_f32(values));
            lowSum = vfmaq_f64(lowSum, low, low);
            highSum = vfmaq_f64(highSum, high, high);
        }
        sum = vaddvq_f64(lowSum) + vaddvq_f64(highSum);
        for (; index < count; ++index) {
            const double value = samples[index];
            sum += value * value;
        }
#else
        for (std::size_t index = 0; index < count; ++index) {
            const double value = samples[index];
            sum += value * value;
        }
#endif
        if (!std::isfinite(sum)) return std::numeric_limits<float>::quiet_NaN();
        return static_cast<float>(std::sqrt(sum / static_cast<double>(count)));
    }

    static float rmsToDb(float rms, float floorDb = -100.0F) noexcept {
        if (!std::isfinite(rms) || rms <= 0.0F) return floorDb;
        return std::max(floorDb, 20.0F * std::log10(rms));
    }

    static float normalize(
        float* samples,
        std::size_t count,
        float targetPeak = 0.9F
    ) noexcept {
        if (!samples || count == 0 || !isAligned(samples) ||
            !std::isfinite(targetPeak) || targetPeak <= 0.0F || targetPeak > 1.0F) {
            return 1.0F;
        }

        float maximum = 0.0F;
        for (std::size_t index = 0; index < count; ++index) {
            if (!std::isfinite(samples[index])) return 1.0F;
            maximum = std::max(maximum, std::abs(samples[index]));
        }
        if (maximum < 0.0001F || maximum >= targetPeak) return 1.0F;

        const float gain = targetPeak / maximum;
#if STT_AUDIO_UTILS_HAS_NEON
        const float32x4_t gainVector = vdupq_n_f32(gain);
        std::size_t index = 0;
        for (; count - index >= 4; index += 4) {
            vst1q_f32(
                samples + index,
                vmulq_f32(vld1q_f32(samples + index), gainVector)
            );
        }
        for (; index < count; ++index) samples[index] *= gain;
#else
        for (std::size_t index = 0; index < count; ++index) samples[index] *= gain;
#endif
        return gain;
    }

    static bool isSilent(
        const std::int16_t* samples,
        std::size_t count,
        std::int16_t threshold = 10
    ) noexcept {
        if (!samples && count != 0) return false;
        if (threshold < 0) return count == 0;
        const std::uint32_t boundedThreshold = static_cast<std::uint32_t>(threshold);
        for (std::size_t index = 0; index < count; ++index) {
            const std::int32_t value = samples[index];
            const std::uint32_t magnitude = static_cast<std::uint32_t>(
                value < 0 ? -value : value
            );
            if (magnitude > boundedThreshold) return false;
        }
        return true;
    }

    static std::uint32_t getMaxSample(
        const std::int16_t* samples,
        std::size_t count
    ) noexcept {
        if (!samples) return 0;
        std::uint32_t maximum = 0;
        for (std::size_t index = 0; index < count; ++index) {
            const std::int32_t value = samples[index];
            const std::uint32_t magnitude = static_cast<std::uint32_t>(
                value < 0 ? -value : value
            );
            maximum = std::max(maximum, magnitude);
        }
        return maximum;
    }

    static void processBatch(
        const std::int16_t* input,
        float* output,
        std::size_t count,
        bool shouldNormalize = true,
        float targetPeak = 0.9F
    ) noexcept {
        const AudioResult converted = pcm16ToFloat(input, count, output, count);
        if (converted && shouldNormalize) {
            (void)normalize(output, count, targetPeak);
        }
    }

private:
    static void resampleLinearKernel(
        const float* source,
        std::size_t sourceCount,
        int sourceRate,
        int destinationRate,
        float* destination,
        std::size_t outputCount
    ) noexcept {
        const std::size_t lastSourceIndex = sourceCount - 1;
        const std::uint64_t sourceRateValue = static_cast<std::uint64_t>(sourceRate);
        const std::uint64_t destinationRateValue = static_cast<std::uint64_t>(destinationRate);
        std::size_t sourceIndex = 0;
        std::uint64_t phase = 0;
        for (std::size_t outputIndex = 0; outputIndex < outputCount; ++outputIndex) {
            const std::size_t first = std::min(sourceIndex, lastSourceIndex);
            const std::size_t second = first < lastSourceIndex ? first + 1 : first;
            const double fraction =
                static_cast<double>(phase) / static_cast<double>(destinationRateValue);
            const double firstValue = static_cast<double>(source[first]);
            const double secondValue = static_cast<double>(source[second]);
            destination[outputIndex] = static_cast<float>(
                firstValue + (secondValue - firstValue) * fraction
            );

            phase += sourceRateValue;
            const std::uint64_t advance = phase / destinationRateValue;
            phase %= destinationRateValue;
            if (advance > lastSourceIndex - std::min(sourceIndex, lastSourceIndex)) {
                sourceIndex = lastSourceIndex;
            } else {
                sourceIndex += static_cast<std::size_t>(advance);
            }
        }
    }

    template <typename Source, typename Destination>
    static AudioResult validateConversion(
        const Source* source,
        std::size_t count,
        Destination* destination,
        std::size_t destinationCapacity
    ) noexcept {
        if (count == 0) return {AudioStatus::OK, 0, 0};
        if (!source || !destination) return {AudioStatus::INVALID_ARGUMENT, 0, count};
        if (!isAligned(source) || !isAligned(destination)) {
            return {AudioStatus::MISALIGNED, 0, count};
        }
        if (destinationCapacity < count) {
            return {AudioStatus::INSUFFICIENT_CAPACITY, 0, count};
        }

        std::size_t sourceBytes = 0;
        std::size_t destinationBytes = 0;
        if (!buffers::checkedMultiply(count, sizeof(Source), sourceBytes) ||
            !buffers::checkedMultiply(count, sizeof(Destination), destinationBytes)) {
            return {AudioStatus::SIZE_OVERFLOW, 0, count};
        }
        if (rangesOverlap(source, sourceBytes, destination, destinationBytes)) {
            return {AudioStatus::OVERLAPPING_BUFFERS, 0, count};
        }
        return {AudioStatus::OK, count, count};
    }

    template <typename Type>
    static bool isAligned(const Type* pointer) noexcept {
        return reinterpret_cast<std::uintptr_t>(pointer) % alignof(Type) == 0;
    }

    static bool rangesOverlap(
        const void* first,
        std::size_t firstBytes,
        const void* second,
        std::size_t secondBytes
    ) noexcept {
        if (firstBytes == 0 || secondBytes == 0) return false;
        const std::uintptr_t firstBegin = reinterpret_cast<std::uintptr_t>(first);
        const std::uintptr_t secondBegin = reinterpret_cast<std::uintptr_t>(second);
        if (firstBegin > std::numeric_limits<std::uintptr_t>::max() - firstBytes ||
            secondBegin > std::numeric_limits<std::uintptr_t>::max() - secondBytes) {
            return true;
        }
        const std::uintptr_t firstEnd = firstBegin + firstBytes;
        const std::uintptr_t secondEnd = secondBegin + secondBytes;
        return firstBegin < secondEnd && secondBegin < firstEnd;
    }

    static std::int16_t floatSampleToPcm16(
        float sample,
        NonFinitePolicy nonFinitePolicy
    ) noexcept {
        if (!std::isfinite(sample)) {
            if (nonFinitePolicy == NonFinitePolicy::ZERO) return 0;
            return 0;
        }
        const float scaled = std::clamp(sample, -1.0F, 1.0F) * 32768.0F;
        if (scaled >= 32767.0F) return 32767;
        if (scaled <= -32768.0F) return -32768;
        return static_cast<std::int16_t>(static_cast<std::int32_t>(scaled));
    }
};

/**
 * Linear streaming resampler with a continuous integer sample clock.
 *
 * Each output is published when its right-hand input sample arrives. This
 * keeps output identical for one large push and arbitrary chunk boundaries.
 * The caller owns this mutable instance and serializes pushes. Input must be
 * band-limited before downsampling; interpolation is not an anti-alias filter.
 */
class AudioStreamResampler {
public:
    bool configure(int sourceRate, int destinationRate) noexcept {
        if (sourceRate <= 0 || destinationRate <= 0) return false;
        sourceRate_ = static_cast<std::uint64_t>(sourceRate);
        destinationRate_ = static_cast<std::uint64_t>(destinationRate);
        reset();
        return true;
    }

    void reset() noexcept {
        inputIndex_ = 0;
        nextOutputNumerator_ = 0;
        previous_ = 0.0F;
    }

    int sourceRate() const noexcept { return static_cast<int>(sourceRate_); }
    int destinationRate() const noexcept { return static_cast<int>(destinationRate_); }

    /** Append this chunk's output; invalid input leaves both state and output intact. */
    AudioResult push(
        const float* source, std::size_t sourceCount, std::vector<float>& destination
    ) {
        if (sourceRate_ == 0 || destinationRate_ == 0 ||
            (!source && sourceCount != 0)) {
            return {AudioStatus::INVALID_ARGUMENT, 0, 0};
        }
        if (sourceCount == 0) return {AudioStatus::OK, 0, 0};
        const auto maximum = std::numeric_limits<std::uint64_t>::max();
        if (sourceCount > maximum - inputIndex_ ||
            inputIndex_ + sourceCount - 1 >
                (maximum - sourceRate_) / destinationRate_) {
            return {AudioStatus::SIZE_OVERFLOW, 0, 0};
        }
        const auto pcmStatus = AudioUtils::validateFinitePcm(source, sourceCount);
        if (pcmStatus != AudioStatus::OK) return {pcmStatus, 0, 0};

        const std::size_t before = destination.size();
        for (std::size_t index = 0; index < sourceCount; ++index) {
            const float sample = source[index];
            const std::uint64_t end = inputIndex_ * destinationRate_;
            while (nextOutputNumerator_ <= end) {
                const double fraction = inputIndex_ == 0 ? 0.0 :
                    static_cast<double>(nextOutputNumerator_ -
                        (inputIndex_ - 1) * destinationRate_) /
                    static_cast<double>(destinationRate_);
                destination.push_back(static_cast<float>(
                    static_cast<double>(previous_) * (1.0 - fraction) +
                    static_cast<double>(sample) * fraction));
                nextOutputNumerator_ += sourceRate_;
            }
            previous_ = sample;
            ++inputIndex_;
        }
        const std::size_t written = destination.size() - before;
        return {AudioStatus::OK, written, written};
    }

private:
    std::uint64_t sourceRate_ = 0;
    std::uint64_t destinationRate_ = 0;
    std::uint64_t inputIndex_ = 0;
    std::uint64_t nextOutputNumerator_ = 0;
    float previous_ = 0.0F;
};

} // namespace stt

#undef STT_AUDIO_UTILS_HAS_AARCH64_NEON
#undef STT_AUDIO_UTILS_HAS_NEON

#endif // AUDIO_UTILS_H
