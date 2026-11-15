#include "../manga_line_geometry.h"

#include <cassert>
#include <limits>
#include <vector>

using hearth::ocr::mangaRegions;

static void paint(std::vector<float>& map, int stride, int x, int y, int width, int height, float value) {
    for (int row = y; row < y + height; ++row) {
        std::fill_n(map.begin() + row * stride + x, width, value);
    }
}

int main() {
    constexpr int side = 48;
    std::vector<float> map(side * side);
    assert(mangaRegions(map.data(), side, side, side, side).empty());

    paint(map, side, 3, 3, 8, 5, 0.85f);
    const auto enlarged = mangaRegions(map.data(), side, side, 2 * side, 2 * side);
    assert(enlarged.size() == 1);
    const auto& box = enlarged.front();
    assert(box.x == 2 && box.y == 2 && box.width == 22 && box.height == 16);
    assert(std::abs(box.score - 0.85f) < 0.00001f);

    paint(map, side, 25, 0, 5, 13, 0.9f);
    const auto lines = mangaRegions(map.data(), side, side, side, side);
    assert(lines.size() == 2);
    assert(lines[0].y == 0 && lines[0].x == 23 && lines[0].width == 8 && lines[0].height == 14);
    assert(lines[1].x == 1 && lines[1].y == 1);

    std::fill(map.begin(), map.end(), 0);
    paint(map, side, 5, 5, 37, 37, 0.99f);
    paint(map, side, 6, 6, 35, 35, 0);
    assert(mangaRegions(map.data(), side, side, side, side).empty());
    for (int i = 0; i < side; ++i) map[i * side + i] = 0.9f;
    assert(mangaRegions(map.data(), side, side, side, side).empty());

    constexpr int crowdedSide = 81;
    std::vector<float> crowded(crowdedSide * crowdedSide);
    for (int i = 0; i < 64; ++i) paint(crowded, crowdedSide, i % 9 * 9, i / 9 * 9, 5, 5, 0.8f);
    assert(mangaRegions(crowded.data(), crowdedSide, crowdedSide, crowdedSide, crowdedSide).size() == 64);
    paint(crowded, crowdedSide, 9, 63, 5, 5, 0.8f);
    bool rejected = false;
    try {
        (void)mangaRegions(crowded.data(), crowdedSide, crowdedSide, crowdedSide, crowdedSide);
    } catch (const std::runtime_error&) {
        rejected = true;
    }
    assert(rejected);

    map[0] = std::numeric_limits<float>::quiet_NaN();
    rejected = false;
    try {
        (void)mangaRegions(map.data(), side, side, side, side);
    } catch (const std::runtime_error&) {
        rejected = true;
    }
    assert(rejected);
}
