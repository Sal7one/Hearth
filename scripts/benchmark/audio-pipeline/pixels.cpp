#include "../../../common-jni/src/main/cpp/common/image_utils.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <stdexcept>
#include <vector>

namespace {

// FilmCraft moves work out of its per-pixel conversion loop and verifies exact
// parity. This benchmark instead shares the chroma codes of each 2x2 NV21
// block and preserves the legacy Hearth RGB888 coefficients and rounding.
// https://github.com/storytold/filmcraft/blob/f4090d7b34a467a1bcabc91652d166a94f68dfbe/crates/frame/src/tests.rs#L360

using vision::ImageFrame;
using vision::ImageUtils;
using vision::ImageView;
using vision::PixelFormat;
using Clock = std::chrono::steady_clock;

struct Plane {
    const uint8_t* bytes;
    size_t length;
    size_t stride;
};

struct RgbBuffer {
    uint8_t* bytes;
    size_t length;
    size_t stride;
};

void require(bool ok, const char* message) {
    if (!ok) throw std::runtime_error(message);
}

bool fits(size_t length, size_t stride, size_t row_bytes, size_t rows) {
    return rows > 0 && stride >= row_bytes && length >= row_bytes &&
        rows - 1 <= (length - row_bytes) / stride;
}

inline void rgb_pixel(uint8_t* rgb, int y, int u, int v) {
    const int r = y + 1.370705f * v;
    const int g = y - 0.337633f * u - 0.698001f * v;
    const int b = y + 1.732446f * u;
    rgb[0] = static_cast<uint8_t>(std::clamp(r, 0, 255));
    rgb[1] = static_cast<uint8_t>(std::clamp(g, 0, 255));
    rgb[2] = static_cast<uint8_t>(std::clamp(b, 0, 255));
}

bool valid_planes(Plane y, Plane vu, RgbBuffer rgb, size_t width, size_t height) {
    return y.bytes && vu.bytes && rgb.bytes && width && height &&
        width % 2 == 0 && height % 2 == 0 &&
        width <= std::numeric_limits<size_t>::max() / 3 &&
        fits(y.length, y.stride, width, height) &&
        fits(vu.length, vu.stride, width, height / 2) &&
        fits(rgb.length, rgb.stride, width * 3, height);
}

bool shared_chroma(Plane y, Plane vu, RgbBuffer rgb, size_t width, size_t height) {
    if (!valid_planes(y, vu, rgb, width, height)) return false;
    for (size_t row = 0; row < height; row += 2) {
        const auto* y0 = y.bytes + row * y.stride;
        const auto* y1 = y0 + y.stride;
        const auto* chroma = vu.bytes + (row / 2) * vu.stride;
        auto* rgb0 = rgb.bytes + row * rgb.stride;
        auto* rgb1 = rgb0 + rgb.stride;
        for (size_t x = 0; x < width; x += 2) {
            const int v = chroma[x] - 128;
            const int u = chroma[x + 1] - 128;
            rgb_pixel(rgb0 + 3 * x, y0[x], u, v);
            rgb_pixel(rgb0 + 3 * x + 3, y0[x + 1], u, v);
            rgb_pixel(rgb1 + 3 * x, y1[x], u, v);
            rgb_pixel(rgb1 + 3 * x + 3, y1[x + 1], u, v);
        }
    }
    return true;
}

// Caller-owned control for separating allocation from the per-pixel work.
// This has the same input checks and RGB arithmetic as the 2x2 implementation.
bool pixel_by_pixel(Plane y, Plane vu, RgbBuffer rgb, size_t width, size_t height) {
    if (!valid_planes(y, vu, rgb, width, height)) return false;
    for (size_t row = 0; row < height; ++row) {
        const auto* y_row = y.bytes + row * y.stride;
        const auto* chroma = vu.bytes + (row / 2) * vu.stride;
        auto* rgb_row = rgb.bytes + row * rgb.stride;
        for (size_t x = 0; x < width; ++x) {
            const size_t pair = (x / 2) * 2;
            rgb_pixel(rgb_row + 3 * x, y_row[x], chroma[pair + 1] - 128, chroma[pair] - 128);
        }
    }
    return true;
}

struct Input {
    int width;
    int height;
    size_t y_size;
    std::vector<uint8_t> bytes;

    Input(int w, int h) : width(w), height(h), y_size(size_t(w) * h), bytes(y_size + y_size / 2) {}
    Plane y() const { return {bytes.data(), y_size, size_t(width)}; }
    Plane vu() const { return {bytes.data() + y_size, y_size / 2, size_t(width)}; }
    ImageView view() const {
        return {bytes.data(), width, height, width, PixelFormat::NV21};
    }
};

RgbBuffer rgb_buffer(ImageFrame& image) {
    return {image.data(), size_t(image.stride()) * image.height(), size_t(image.stride())};
}

bool same_frame(const ImageFrame& a, const ImageFrame& b) {
    return a.width() == b.width() && a.height() == b.height() && a.stride() == b.stride() &&
        std::equal(a.data(), a.data() + size_t(a.stride()) * a.height(), b.data());
}

void check_exhaustive_codes() {
    // For each V, generate all 256 U and 256 Y codes. A 2x2 block has one
    // U/V pair and carries four consecutive Y codes.
    Input input(512, 128);
    ImageFrame current;
    ImageFrame candidate(input.width, input.height, PixelFormat::RGB888);
    ImageFrame control(input.width, input.height, PixelFormat::RGB888);
    for (int v = 0; v < 256; ++v) {
        for (int u = 0; u < 256; ++u) {
            for (int quarter = 0; quarter < 64; ++quarter) {
                const size_t x = size_t(u) * 2;
                const size_t y = size_t(quarter) * 2;
                const size_t y0 = y * input.width + x;
                const size_t y1 = y0 + input.width;
                const size_t uv = input.y_size + size_t(quarter) * input.width + x;
                input.bytes[y0] = static_cast<uint8_t>(quarter * 4);
                input.bytes[y0 + 1] = static_cast<uint8_t>(quarter * 4 + 1);
                input.bytes[y1] = static_cast<uint8_t>(quarter * 4 + 2);
                input.bytes[y1 + 1] = static_cast<uint8_t>(quarter * 4 + 3);
                input.bytes[uv] = static_cast<uint8_t>(v);
                input.bytes[uv + 1] = static_cast<uint8_t>(u);
            }
        }
        require(ImageUtils::nv21ToRgb(input.view(), current), "Hearth rejected valid NV21");
        require(shared_chroma(input.y(), input.vu(), rgb_buffer(candidate), input.width, input.height),
                "Chroma sharing rejected valid NV21");
        require(pixel_by_pixel(input.y(), input.vu(), rgb_buffer(control), input.width, input.height),
                "Pixel control rejected valid NV21");
        require(same_frame(current, candidate) && same_frame(current, control),
                "NV21 exhaustive RGB parity failed");
    }
}

void check_padded_planes() {
    Input input(18, 10);
    for (size_t i = 0; i < input.bytes.size(); ++i) {
        input.bytes[i] = static_cast<uint8_t>(i * 47 + 91);
    }
    ImageFrame current;
    require(ImageUtils::nv21ToRgb(input.view(), current), "Hearth rejected odd stride test width");
    require(current.stride() > input.width * 3, "Expected padded RGB rows");

    const size_t y_stride = 22;
    const size_t vu_stride = 26;
    const size_t rgb_stride = size_t(current.stride());
    const size_t width = size_t(input.width);
    const size_t height = size_t(input.height);
    std::vector<uint8_t> padded_y(y_stride * height, 0xE3);
    std::vector<uint8_t> padded_vu(vu_stride * (height / 2), 0xA2);
    std::vector<uint8_t> padded_rgb(rgb_stride * height, 0x7B);
    for (size_t row = 0; row < height; ++row) {
        std::copy_n(input.y().bytes + row * width, width, padded_y.data() + row * y_stride);
    }
    for (size_t row = 0; row < height / 2; ++row) {
        std::copy_n(input.vu().bytes + row * width, width, padded_vu.data() + row * vu_stride);
    }
    const Plane y{padded_y.data(), (height - 1) * y_stride + width, y_stride};
    const Plane vu{padded_vu.data(), (height / 2 - 1) * vu_stride + width, vu_stride};
    const RgbBuffer rgb{padded_rgb.data(), (height - 1) * rgb_stride + width * 3, rgb_stride};
    require(shared_chroma(y, vu, rgb, width, height), "Rejected valid padded NV21");
    for (size_t row = 0; row < height; ++row) {
        const auto* observed = rgb.bytes + row * rgb.stride;
        require(std::equal(observed, observed + 3 * width, current.data() + row * rgb_stride),
                "RGB parity failed for padded input");
        require(std::all_of(observed + 3 * width, observed + rgb_stride,
                            [](uint8_t byte) { return byte == 0x7B; }),
                "Conversion wrote into RGB row padding");
    }
    const auto saved = padded_rgb;
    require(!shared_chroma({y.bytes, y.length - 1, y.stride}, vu, rgb, width, height) &&
            !shared_chroma(y, {vu.bytes, vu.length - 1, vu.stride}, rgb, width, height) &&
            !shared_chroma(y, vu, {rgb.bytes, rgb.length - 1, rgb.stride}, width, height) &&
            !shared_chroma(y, vu, rgb, width - 1, height) &&
            !shared_chroma(y, vu, rgb, width, height - 1) &&
            !shared_chroma({y.bytes, y.length, width - 1}, vu, rgb, width, height) &&
            !shared_chroma(y, vu, rgb, std::numeric_limits<size_t>::max() - 1, height) &&
            padded_rgb == saved, "Invalid planes wrote output or were accepted");
}

void measure(int width, int height) {
    Input input(width, height);
    uint32_t rng = 0x4e563231;
    for (auto& byte : input.bytes) {
        rng ^= rng << 13;
        rng ^= rng >> 17;
        rng ^= rng << 5;
        byte = static_cast<uint8_t>(rng);
    }
    ImageFrame reference;
    ImageFrame scratch(width, height, PixelFormat::RGB888);
    const auto y = input.y();
    const auto vu = input.vu();
    auto run = [&](size_t mode) {
        if (mode == 0) {
            reference = ImageFrame(width, height, PixelFormat::RGB888);
            require(pixel_by_pixel(y, vu, rgb_buffer(reference), width, height), "Legacy conversion failed");
            return reference.data();
        }
        if (mode == 1) {
            require(ImageUtils::nv21ToRgb(input.view(), scratch), "Common JNI conversion failed");
            return scratch.data();
        }
        const auto dst = rgb_buffer(scratch);
        const bool ok = mode == 2 ? pixel_by_pixel(y, vu, dst, width, height)
                                  : shared_chroma(y, vu, dst, width, height);
        require(ok, "Candidate conversion failed");
        return scratch.data();
    };
    for (size_t mode = 0; mode < 4; ++mode) run(mode);
    require(same_frame(reference, scratch), "Full-frame RGB parity failed");

    constexpr size_t trials = 8;
    std::array<std::array<double, trials>, 4> samples{};
    std::array<uint64_t, 4> checksums{};
    const size_t output_length = size_t(scratch.stride()) * height;
    for (size_t trial = 0; trial < trials; ++trial) {
        for (size_t offset = 0; offset < samples.size(); ++offset) {
            const size_t mode = (trial + offset) % samples.size();
            const auto started = Clock::now();
            const auto* rgb = run(mode);
            samples[mode][trial] = std::chrono::duration<double, std::milli>(Clock::now() - started).count();
            checksums[mode] += rgb[(trial * 65537) % output_length];
        }
    }
    require(std::all_of(checksums.begin(), checksums.end(),
                        [&](uint64_t n) { return n == checksums[0]; }),
            "Measured RGB checksums differ");
    constexpr std::array<const char*, 4> labels{
        "legacy_nv21_allocating", "common_jni_nv21", "pixel_control_reused", "shared_chroma_reused"
    };
    for (size_t mode = 0; mode < samples.size(); ++mode) {
        auto& s = samples[mode];
        std::sort(s.begin(), s.end());
        std::printf("{\"suite\":\"pixels\",\"method\":\"%s\",\"width\":%d,\"height\":%d,"
                    "\"trials\":%zu,\"matrix_range\":\"hearth_legacy_full_y\",\"allocates_output\":%s,"
                    "\"median_ms\":%.6f,\"p95_ms\":%.6f,\"checksum\":%llu,\"exact_rgb\":true}\n",
                    labels[mode], width, height, trials, mode < 2 ? "true" : "false",
                    (s[trials / 2 - 1] + s[trials / 2]) / 2, s.back(),
                    static_cast<unsigned long long>(checksums[mode]));
    }
}

} // namespace

void run_pixels_benchmark() {
    check_exhaustive_codes();
    check_padded_planes();
    measure(1280, 720);
    measure(1920, 1080);
    measure(3840, 2160);
}
