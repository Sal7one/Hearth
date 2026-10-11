#pragma once
#include <cstddef>
#include <cstdint>
#include <stdexcept>

namespace hearth::vision {
constexpr int kMaximumSide = 2048;
constexpr std::size_t kMaximumOutputBytes = 65536;
constexpr int kContextTokens = 4096;
constexpr int kOutputTokens = 1536;
constexpr int kImageTokens = 1024;
constexpr int kDeadlineSeconds = 120;

inline std::size_t validateImage(int width, int height, std::size_t count) {
    if (width < 1 || height < 1 || width > kMaximumSide || height > kMaximumSide)
        throw std::invalid_argument("Vision OCR image sides must be 1–2048 pixels");
    const auto expected = static_cast<std::size_t>(width) * static_cast<std::size_t>(height);
    if (count != expected) throw std::invalid_argument("Vision OCR pixel count does not match its dimensions");
    return expected;
}

inline unsigned char opaqueChannel(std::uint32_t argb, unsigned shift) {
    const unsigned alpha = argb >> 24;
    const unsigned channel = (argb >> shift) & 255;
    return static_cast<unsigned char>((channel * alpha + 255 * (255 - alpha) + 127) / 255);
}
} // namespace hearth::vision
