#include "../image_utils.h"

#include <array>
#include <cassert>

using vision::ImageFrame;
using vision::ImageUtils;
using vision::ImageView;
using vision::PixelFormat;

int main() {
    const std::array<uint8_t, 12> packed{
        0, 16, 31, 63,
        120, 255, 128, 192,
        0, 255, 128, 128,
    };
    const std::array<uint8_t, 24> expected{
        0, 46, 220, 0, 62, 236, 31, 31, 31, 63, 63, 63,
        0, 166, 255, 79, 255, 255, 128, 128, 128, 192, 192, 192,
    };
    ImageFrame rgb;
    ImageView view{packed.data(), 4, 2, 4, PixelFormat::NV21};
    const auto check = [&] {
        assert(rgb.format() == PixelFormat::RGB888 && rgb.width() == 4 && rgb.height() == 2);
        for (int row = 0; row < rgb.height(); ++row) {
            const auto* output = rgb.data() + row * rgb.stride();
            assert(std::equal(output, output + 12, expected.data() + row * 12));
            assert(std::all_of(output + 12, output + rgb.stride(), [](uint8_t value) { return value == 0; }));
        }
    };
    assert(ImageUtils::nv21ToRgb(view, rgb));
    check();

    view.planeV = packed.data() + 8;
    assert(ImageUtils::nv21ToRgb(view, rgb));
    check();

    view.width = 3;
    assert(!ImageUtils::nv21ToRgb(view, rgb));
    check();
    view.width = std::numeric_limits<int>::max() - 1;
    assert(!ImageUtils::nv21ToRgb(view, rgb));
    check();

    ImageFrame nv21(4, 2, PixelFormat::NV21);
    std::copy(packed.begin(), packed.end(), nv21.data());
    assert(ImageUtils::nv21ToRgb(nv21.view(), nv21));
    rgb = std::move(nv21);
    check();
}
