#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <vector>

namespace hearth::ocr {

inline constexpr int kMangaImageSide = 224;

// Manga OCR expects three identical grayscale planes in [-1, 1]. Round each
// source pixel to an eight-bit gray code before interpolation, as in the export.
inline std::vector<float> mangaOcrInput(const uint32_t* pixels, int width, int height) {
    constexpr int side = kMangaImageSide;
    constexpr int plane = side * side;
    const auto gray = [](uint32_t argb) {
        return std::round(.299f * ((argb >> 16) & 255) + .587f * ((argb >> 8) & 255) + .114f * (argb & 255)) / 255.f;
    };
    struct AxisSample { int first; int second; float fraction; };
    const auto sample = [](int destination, int extent) -> AxisSample {
        const float coordinate = std::clamp((destination + .5f) * extent / side - .5f, 0.f, float(extent - 1));
        const int first = static_cast<int>(coordinate);
        return {first, std::min(first + 1, extent - 1), coordinate - first};
    };
    std::array<AxisSample, side> columns;
    for (int x = 0; x < side; ++x) columns[x] = sample(x, width);

    std::vector<float> output(3 * plane);
    for (int y = 0; y < side; ++y) {
        const auto [y0, y1, dy] = sample(y, height);
        const auto* top = pixels + y0 * width;
        const auto* bottom = pixels + y1 * width;
        for (int x = 0; x < side; ++x) {
            const auto [x0, x1, dx] = columns[x];
            const float value = (gray(top[x0]) * (1 - dx) + gray(top[x1]) * dx) * (1 - dy)
                + (gray(bottom[x0]) * (1 - dx) + gray(bottom[x1]) * dx) * dy;
            output[y * side + x] = (value - .5f) * 2;
        }
    }
    std::copy_n(output.data(), plane, output.data() + plane);
    std::copy_n(output.data(), plane, output.data() + 2 * plane);
    return output;
}

} // namespace hearth::ocr
