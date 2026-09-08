#ifndef STT_SHA256_H
#define STT_SHA256_H

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string_view>

namespace stt {

/**
 * Dependency-free incremental SHA-256 implementation.
 *
 * The digest operation is non-destructive, so callers may inspect a running
 * hash and continue feeding it afterwards.
 */
class Sha256 {
public:
    using Digest = std::array<std::uint8_t, 32>;

    Sha256() noexcept { reset(); }

    void reset() noexcept {
        state_ = {{
            0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
            0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U
        }};
        buffer_.fill(0);
        bufferedBytes_ = 0;
        totalBytes_ = 0;
    }

    bool update(const void* data, std::size_t size) noexcept {
        if (size == 0) return true;
        if (!data) return false;

        const auto* input = static_cast<const std::uint8_t*>(data);
        totalBytes_ += static_cast<std::uint64_t>(size);

        if (bufferedBytes_ != 0) {
            const std::size_t needed = kBlockSize - bufferedBytes_;
            const std::size_t copied = size < needed ? size : needed;
            std::memcpy(buffer_.data() + bufferedBytes_, input, copied);
            bufferedBytes_ += copied;
            input += copied;
            size -= copied;
            if (bufferedBytes_ == kBlockSize) {
                transform(buffer_.data());
                bufferedBytes_ = 0;
            }
        }

        while (size >= kBlockSize) {
            transform(input);
            input += kBlockSize;
            size -= kBlockSize;
        }

        if (size != 0) {
            std::memcpy(buffer_.data(), input, size);
            bufferedBytes_ = size;
        }
        return true;
    }

    Digest digest() const noexcept {
        Sha256 copy(*this);
        return copy.finalize();
    }

    static Digest hash(const void* data, std::size_t size) noexcept {
        Sha256 hash;
        (void)hash.update(data, size);
        return hash.digest();
    }

    static Digest hash(std::string_view value) noexcept {
        return hash(value.data(), value.size());
    }

private:
    static constexpr std::size_t kBlockSize = 64;

    static constexpr std::array<std::uint32_t, 64> kRoundConstants{{
        0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
        0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
        0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
        0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
        0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
        0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
        0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
        0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
        0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
        0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
        0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
        0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
        0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
        0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
        0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
        0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U
    }};

    static constexpr std::uint32_t rotateRight(std::uint32_t value, unsigned bits) noexcept {
        return (value >> bits) | (value << (32U - bits));
    }

    static constexpr std::uint32_t choose(
        std::uint32_t x, std::uint32_t y, std::uint32_t z
    ) noexcept {
        return (x & y) ^ (~x & z);
    }

    static constexpr std::uint32_t majority(
        std::uint32_t x, std::uint32_t y, std::uint32_t z
    ) noexcept {
        return (x & y) ^ (x & z) ^ (y & z);
    }

    static constexpr std::uint32_t bigSigma0(std::uint32_t value) noexcept {
        return rotateRight(value, 2U) ^ rotateRight(value, 13U) ^ rotateRight(value, 22U);
    }

    static constexpr std::uint32_t bigSigma1(std::uint32_t value) noexcept {
        return rotateRight(value, 6U) ^ rotateRight(value, 11U) ^ rotateRight(value, 25U);
    }

    static constexpr std::uint32_t smallSigma0(std::uint32_t value) noexcept {
        return rotateRight(value, 7U) ^ rotateRight(value, 18U) ^ (value >> 3U);
    }

    static constexpr std::uint32_t smallSigma1(std::uint32_t value) noexcept {
        return rotateRight(value, 17U) ^ rotateRight(value, 19U) ^ (value >> 10U);
    }

    void transform(const std::uint8_t* block) noexcept {
        std::array<std::uint32_t, 64> words{};
        for (std::size_t i = 0; i < 16; ++i) {
            const std::size_t offset = i * 4;
            words[i] = (static_cast<std::uint32_t>(block[offset]) << 24U) |
                       (static_cast<std::uint32_t>(block[offset + 1]) << 16U) |
                       (static_cast<std::uint32_t>(block[offset + 2]) << 8U) |
                       static_cast<std::uint32_t>(block[offset + 3]);
        }
        for (std::size_t i = 16; i < words.size(); ++i) {
            words[i] = smallSigma1(words[i - 2]) + words[i - 7] +
                       smallSigma0(words[i - 15]) + words[i - 16];
        }

        std::uint32_t a = state_[0];
        std::uint32_t b = state_[1];
        std::uint32_t c = state_[2];
        std::uint32_t d = state_[3];
        std::uint32_t e = state_[4];
        std::uint32_t f = state_[5];
        std::uint32_t g = state_[6];
        std::uint32_t h = state_[7];

        for (std::size_t i = 0; i < words.size(); ++i) {
            const std::uint32_t first = h + bigSigma1(e) + choose(e, f, g) +
                                        kRoundConstants[i] + words[i];
            const std::uint32_t second = bigSigma0(a) + majority(a, b, c);
            h = g;
            g = f;
            f = e;
            e = d + first;
            d = c;
            c = b;
            b = a;
            a = first + second;
        }

        state_[0] += a;
        state_[1] += b;
        state_[2] += c;
        state_[3] += d;
        state_[4] += e;
        state_[5] += f;
        state_[6] += g;
        state_[7] += h;
    }

    Digest finalize() noexcept {
        const std::uint64_t totalBits = totalBytes_ * 8U;
        buffer_[bufferedBytes_++] = 0x80U;

        if (bufferedBytes_ > 56) {
            while (bufferedBytes_ < kBlockSize) buffer_[bufferedBytes_++] = 0;
            transform(buffer_.data());
            bufferedBytes_ = 0;
        }
        while (bufferedBytes_ < 56) buffer_[bufferedBytes_++] = 0;

        for (std::size_t i = 0; i < 8; ++i) {
            const unsigned shift = static_cast<unsigned>((7 - i) * 8);
            buffer_[56 + i] = static_cast<std::uint8_t>(totalBits >> shift);
        }
        transform(buffer_.data());

        Digest result{};
        for (std::size_t i = 0; i < state_.size(); ++i) {
            result[i * 4] = static_cast<std::uint8_t>(state_[i] >> 24U);
            result[i * 4 + 1] = static_cast<std::uint8_t>(state_[i] >> 16U);
            result[i * 4 + 2] = static_cast<std::uint8_t>(state_[i] >> 8U);
            result[i * 4 + 3] = static_cast<std::uint8_t>(state_[i]);
        }
        return result;
    }

    std::array<std::uint32_t, 8> state_{};
    std::array<std::uint8_t, kBlockSize> buffer_{};
    std::size_t bufferedBytes_ = 0;
    std::uint64_t totalBytes_ = 0;
};

} // namespace stt

#endif // STT_SHA256_H
