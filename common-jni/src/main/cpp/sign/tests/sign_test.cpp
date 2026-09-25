// Host tests for the sign-language pipeline's portable pieces: letterbox
// resampling, homography math, rotated-rect corners, the VERIFIED anchor
// table, palm SSD decode/NMS and the hand-rect transform. Golden constants
// come from research/sign/fidelity/golden.json (mediapipe-verified).
// Inference paths (ONNX Runtime) are gated by scripts/sign/fidelity_check.py
// and device tests, not here.

#include "../hand_geometry.h"
#include "../hand_landmarks.h"

#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

using stt::sign::HandLandmarkPipeline;
using stt::sign::PointF;

static void testRotatedRectPoints() {
    PointF c[4];
    // Axis-aligned square at angle 0: bl, tl, tr, br (y grows downward).
    stt::sign::rotatedRectPoints(100.f, 100.f, 40.f, 40.f, 0.f, c);
    assert(std::fabs(c[0].x - 80.f) < 1e-3f && std::fabs(c[0].y - 120.f) < 1e-3f);
    assert(std::fabs(c[1].x - 80.f) < 1e-3f && std::fabs(c[1].y - 80.f) < 1e-3f);
    assert(std::fabs(c[2].x - 120.f) < 1e-3f && std::fabs(c[2].y - 80.f) < 1e-3f);
    assert(std::fabs(c[3].x - 120.f) < 1e-3f && std::fabs(c[3].y - 120.f) < 1e-3f);
    // Diagonals cross at the center at any angle; square sides stay equal.
    for (float angle = -180.f; angle <= 180.f; angle += 17.f) {
        stt::sign::rotatedRectPoints(37.f, 53.f, 61.f, 45.f, angle, c);
        assert(std::fabs((c[0].x + c[2].x) / 2 - 37.f) < 1e-3f);
        assert(std::fabs((c[0].y + c[2].y) / 2 - 53.f) < 1e-3f);
        stt::sign::rotatedRectPoints(37.f, 53.f, 61.f, 61.f, angle, c);
        for (int i = 0; i < 4; ++i) {
            const int j = (i + 1) & 3;
            const float dx = c[j].x - c[i].x, dy = c[j].y - c[i].y;
            assert(std::fabs(std::sqrt(dx * dx + dy * dy) - 61.f) < 1e-2f);
        }
    }
    std::printf("  rotatedRectPoints OK\n");
}

static void testHomography() {
    PointF src[4] = {{0, 0}, {10, 0}, {10, 10}, {0, 10}};
    PointF dst[4] = {{0, 0}, {10, 0}, {10, 10}, {0, 10}};
    double h[9];
    assert(stt::sign::homography4(src, dst, h));
    for (int i = 0; i < 4; ++i) {
        float ox, oy;
        stt::sign::applyHomography(h, src[i].x, src[i].y, ox, oy);
        assert(std::fabs(ox - dst[i].x) < 1e-6f && std::fabs(oy - dst[i].y) < 1e-6f);
    }
    // Scale x2 + translate (3, 4).
    for (int i = 0; i < 4; ++i) {
        dst[i].x = src[i].x * 2 + 3;
        dst[i].y = src[i].y * 2 + 4;
    }
    assert(stt::sign::homography4(src, dst, h));
    for (int i = 0; i < 4; ++i) {
        float ox, oy;
        stt::sign::applyHomography(h, src[i].x, src[i].y, ox, oy);
        assert(std::fabs(ox - dst[i].x) < 1e-5f && std::fabs(oy - dst[i].y) < 1e-5f);
    }
    // Inverse roundtrip.
    double inv[9];
    assert(stt::sign::invert3x3(h, inv));
    for (int i = 0; i < 4; ++i) {
        float mx, my, ox, oy;
        stt::sign::applyHomography(h, src[i].x, src[i].y, mx, my);
        stt::sign::applyHomography(inv, mx, my, ox, oy);
        assert(std::fabs(ox - src[i].x) < 1e-5f && std::fabs(oy - src[i].y) < 1e-5f);
    }
    PointF badSrc[4] = {{0, 0}, {1, 1}, {2, 2}, {3, 3}};
    assert(!stt::sign::homography4(badSrc, dst, h));
    std::printf("  homography4/invert3x3/applyHomography OK\n");
}

static void testLetterbox() {
    // Source 2x4 (w x h) into a 4x4 square: scale = min(4/4, 4/2) = 1, so the
    // image lands 2 wide x 4 tall, offset x=1, with zero padding columns.
    const int w = 2, h = 4;
    uint8_t rgba[32];
    for (int i = 0; i < w * h; ++i) {
        rgba[i * 4] = 90 * (i % 3);       // R
        rgba[i * 4 + 1] = 180;            // G
        rgba[i * 4 + 2] = 45 * (i % 2);   // B
        rgba[i * 4 + 3] = 255;
    }
    std::vector<float> dst(4 * 4 * 3);
    stt::sign::Letterbox box;
    stt::sign::letterboxRgbaToRgbFloat(rgba, w, h, dst.data(), 4, box);
    assert(box.width == 2 && box.height == 4);
    assert(box.offsetX == 1 && box.offsetY == 0);
    // Identity-scale interior pixel (1,1): exact source pixel (1,1), RGB order.
    const float* px = &dst[(1 * 4 + 2) * 3];  // x = box.offsetX + 1
    assert(std::fabs(px[0] - 90.f * ((1 * w + 1) % 3)) < 1e-3f);
    assert(std::fabs(px[1] - 180.f) < 1e-3f);
    // Padding column x=0 stays zero.
    px = &dst[(1 * 4 + 0) * 3];
    assert(px[0] == 0.f && px[1] == 0.f && px[2] == 0.f);
    std::printf("  letterboxRgbaToRgbFloat OK\n");
}

// Golden values from research/sign/fidelity/golden.json (verified against the
// official MediaPipe runtime on a real detection).
static void testAnchors() {
    const HandLandmarkPipeline probe;
    std::vector<HandLandmarkPipeline::Anchor> anchors;
    probe.buildAnchors(anchors);
    assert(anchors.size() == 2016);
    // fixed_anchor_size: every anchor w = h = 1.
    for (const auto& a : anchors) assert(a.w == 1.f && a.h == 1.f);
    // Golden spot values.
    assert(std::fabs(anchors[0].cx - 0.020833333f) < 1e-6f);
    assert(std::fabs(anchors[0].cy - 0.020833333f) < 1e-6f);
    assert(std::fabs(anchors[1].cx - 0.020833333f) < 1e-6f);
    assert(std::fabs(anchors[1151].cx - 0.979166667f) < 1e-6f);
    assert(std::fabs(anchors[1151].cy - 0.979166667f) < 1e-6f);
    // Rows 0..1151 are the 24x24 grid (2/cell); row 1152 starts the 12x12
    // grid (6/cell): the two regions must not share cell centers.
    assert(std::fabs(anchors[1152].cx - 0.041666667f) < 1e-6f);
    assert(std::fabs(anchors[1153].cx - 0.041666667f) < 1e-6f);
    assert(std::fabs(anchors[2015].cx - 0.958333333f) < 1e-6f);
    assert(std::fabs(anchors[2015].cy - 0.958333333f) < 1e-6f);
    std::printf("  VERIFIED anchor table OK\n");
}

static void testDecodePalms() {
    const HandLandmarkPipeline probe;
    std::vector<HandLandmarkPipeline::Anchor> anchors;
    probe.buildAnchors(anchors);
    const size_t n = anchors.size();
    const size_t stride = 18;
    std::vector<float> scores(n, -10.f);
    std::vector<float> regs(n * stride, 0.f);

    // Verified decode: absolute offsets / 192, x-first. Row 500 sits in the
    // 24x24 grid (cell 250 -> x=10, y=10 -> center 10.5/24 = 0.4375).
    const size_t k = 500;
    assert(std::fabs(anchors[k].cx - 10.5f / 24.f) < 1e-6f);
    scores[k] = 2.2f;
    regs[k * stride + 0] = 192.0f;  // +1.0 in x
    regs[k * stride + 2] = 38.4f;   // w = 0.2
    regs[k * stride + 3] = 38.4f;   // h = 0.2
    regs[k * stride + 4] = 96.0f;   // kp0 x: +0.5

    std::vector<float> boxes, kps, outScores;
    assert(HandLandmarkPipeline::decodePalms(scores.data(), regs.data(), regs.size(),
                                             anchors, 0.5f, boxes, kps, outScores));
    assert(outScores.size() == 1);
    assert(std::fabs(outScores[0] - 1.f / (1.f + std::exp(-2.2f))) < 1e-5f);
    assert(std::fabs(boxes[0] + boxes[2] / 2 - (anchors[k].cx + 1.f)) < 1e-5f);
    assert(std::fabs(boxes[1] + boxes[3] / 2 - anchors[k].cy) < 1e-5f);
    assert(std::fabs(boxes[2] - 0.2f) < 1e-5f);
    assert(std::fabs(kps[0] - (anchors[k].cx + 0.5f)) < 1e-5f);
    assert(std::fabs(kps[1] - anchors[k].cy) < 1e-5f);

    // NMS: a same-cell detection with identical regressions is an exact
    // duplicate box -> suppressed; a distant detection survives; clipping:
    // raw score 1e6 must not overflow the sigmoid.
    scores[501] = 1.8f;  // same cell as 500 (2/cell in the 24-grid)
    std::memcpy(&regs[501 * stride], &regs[k * stride], stride * sizeof(float));
    scores[1900] = 1e6f;  // last-layer cell, far away; clipped to sigmoid(100)
    assert(HandLandmarkPipeline::decodePalms(scores.data(), regs.data(), regs.size(),
                                             anchors, 0.5f, boxes, kps, outScores));
    assert(outScores.size() == 2);
    // Sorted by score: the clipped 1e6 logit first (sigmoid(100) == 1), then
    // the row-500 detection (sigmoid(2.2)); the duplicate row 501 is gone.
    assert(std::fabs(outScores[0] - 1.f) < 1e-6f);
    assert(std::fabs(outScores[1] - 1.f / (1.f + std::exp(-2.2f))) < 1e-5f);
    std::printf("  VERIFIED palm SSD decode + NMS OK\n");
}

static void testHandRect() {
    // Golden detection from fidelity/golden.json (Ain_55.jpg, 256x192):
    // box [0.59338, 0.175461, 0.313768, 0.418416], kp0 (0.909132, 0.417436),
    // kp2 (0.607402, 0.333224) ->
    //   center_norm (0.598561, 0.438101), side_px 208.873318, rot -104.797456.
    const float box[4] = {0.59338f, 0.175461f, 0.313768f, 0.418416f};
    const float kps[14] = {0.909132f, 0.417436f, 0.f, 0.f, 0.607402f, 0.333224f};
    PointF center;
    float side, rot;
    assert(HandLandmarkPipeline::handRect(box, kps, 256, 192, center, side, rot));
    assert(std::fabs(center.x / 256.f - 0.598561f) < 2e-3f);
    assert(std::fabs(center.y / 192.f - 0.438101f) < 2e-3f);
    assert(std::fabs(side - 208.873318f) < 0.5f);
    assert(std::fabs(rot - (-104.797456f)) < 0.05f);
    std::printf("  VERIFIED hand rect OK\n");
}

int main() {
    std::printf("Sign pipeline host tests:\n");
    testRotatedRectPoints();
    testHomography();
    testLetterbox();
    testAnchors();
    testDecodePalms();
    testHandRect();
    std::printf("Sign pipeline PASS (geometry, letterbox, verified anchors, SSD "
                "decode, NMS, hand rect)\n");
    return 0;
}
