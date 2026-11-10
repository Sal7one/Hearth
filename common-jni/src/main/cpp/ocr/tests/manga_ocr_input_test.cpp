#include "../manga_ocr_input.h"

#include <cassert>
#include <chrono>
#include <cstring>
#include <iostream>
#include <random>

using hearth::ocr::kMangaImageSide;
using hearth::ocr::mangaOcrInput;

static std::vector<float> legacyInput(const uint32_t* pixels, int width, int height) {
    constexpr int side = kMangaImageSide;
    std::vector<float> output(3 * side * side, 0.f);
    for (int y = 0; y < side; ++y) for (int x = 0; x < side; ++x) {
        const float fx = std::clamp((x + .5f) * width / side - .5f, 0.f, float(width - 1));
        const float fy = std::clamp((y + .5f) * height / side - .5f, 0.f, float(height - 1));
        const int x0 = int(fx), y0 = int(fy), x1 = std::min(x0 + 1, width - 1), y1 = std::min(y0 + 1, height - 1);
        const float dx = fx - x0, dy = fy - y0;
        for (int channel = 0; channel < 3; ++channel) {
            const auto gray = [&](int a, int b) {
                const auto p = pixels[b * width + a];
                return std::round(.299f * ((p >> 16) & 255) + .587f * ((p >> 8) & 255) + .114f * (p & 255)) / 255.f;
            };
            const float value = (gray(x0, y0) * (1 - dx) + gray(x1, y0) * dx) * (1 - dy)
                + (gray(x0, y1) * (1 - dx) + gray(x1, y1) * dx) * dy;
            output[(channel * side + y) * side + x] = (value - .5f) * 2;
        }
    }
    return output;
}

int main(int argc, char** argv) {
    const bool benchmark = argc == 2 && std::strcmp(argv[1], "--bench") == 0;
    std::mt19937 generator(1486);
    constexpr int sizes[][2] = {{1, 1}, {1, 300}, {330, 1}, {32, 55}, {228, 184}, {224, 224}, {117, 1024}, {2048, 2048}};
    for (const auto& size : sizes) {
        const int width = size[0], height = size[1];
        std::vector<uint32_t> pixels(width * height);
        for (auto& pixel : pixels) pixel = generator();
        const auto before = legacyInput(pixels.data(), width, height);
        const auto after = mangaOcrInput(pixels.data(), width, height);
        assert(before.size() == after.size());
        assert(std::memcmp(before.data(), after.data(), before.size() * sizeof(float)) == 0);
        if (!benchmark) continue;

        constexpr size_t iterations = 31;
        std::array<double, iterations> legacyTimes{}, newTimes{};
        float checksum = 0.f;
        using Clock = std::chrono::steady_clock;
        for (size_t i = 0; i < iterations; ++i) {
            for (size_t turn = 0; turn < 2; ++turn) {
                const bool legacy = (i + turn) % 2 == 0;
                const auto start = Clock::now();
                const auto result = legacy ? legacyInput(pixels.data(), width, height) : mangaOcrInput(pixels.data(), width, height);
                (legacy ? legacyTimes : newTimes)[i] = std::chrono::duration<double, std::micro>(Clock::now() - start).count();
                checksum += result[i * 41];
            }
        }
        std::sort(legacyTimes.begin(), legacyTimes.end());
        std::sort(newTimes.begin(), newTimes.end());
        std::cout << width << 'x' << height << " median μs " << legacyTimes[iterations / 2]
                  << " -> " << newTimes[iterations / 2] << " checksum " << checksum << '\n';
    }
    std::cout << "Manga OCR input tensors are bit-exact for all eight crop sizes\n";
}
