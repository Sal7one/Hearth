// =============================================================================
// Hand-pipeline geometry — the resampling and homography kernels the sign
// pipeline needs, with no image-library dependency.
//
// Every function reproduces the exact numeric semantics verified against the
// official MediaPipe runtime by scripts/sign/fidelity_check.py (see
// research/sign/fidelity/REPORT.md before changing anything here):
//   - palm input is LETTERBOXED (keep aspect ratio, zero border, centered)
//     into 192x192, matching MediaPipe's keep_aspect_ratio preprocessing;
//   - crop sampling follows cv bilinear center conventions with a
//     constant-0 border;
//   - rotated-rect corners use the cv::RotatedRect::points formula so the
//     homography corner pairing (bl, tl, tr, br) is unchanged.
// Plain C++ over caller-owned buffers: portable to every host and testable
// without a model.
// =============================================================================

#ifndef STT_HAND_GEOMETRY_H
#define STT_HAND_GEOMETRY_H

#include <cstddef>
#include <cstdint>

namespace stt::sign {

struct PointF {
    float x = 0.f;
    float y = 0.f;
};

// Letterbox placement of the source rectangle inside a square destination:
// the mapping needed to undo detection coordinates after inference.
struct Letterbox {
    int offsetX = 0;  // top-left of the scaled image inside the square
    int offsetY = 0;
    int width = 0;    // scaled image size inside the square
    int height = 0;
};

// Bilinear letterbox resize of an RGBA image into an RGB float plane
// (0..255, no normalization — callers scale), keep-aspect-ratio, centered,
// BORDER_CONSTANT 0, matching MediaPipe keep_aspect_ratio preprocessing.
// The undo-mapping is returned in [box].
void letterboxRgbaToRgbFloat(const uint8_t* rgba, int srcWidth, int srcHeight,
                             float* dst, int dstSize, Letterbox& box);

// Corners of a rotated square in cv::RotatedRect::points order
// (index 0..3 = bottom-left, top-left, top-right, bottom-right for the
// angles this pipeline produces). The pairing src[i] -> dst[i] is what the
// homography below must preserve.
void rotatedRectPoints(float centerX, float centerY, float width, float height,
                       float angleDeg, PointF out[4]);

// Exact 4-point homography H (row-major 3x3) with dst = H(src), equivalent to
// cv::getPerspectiveTransform for four non-degenerate correspondences.
// Solves the 8x8 linear system by Gaussian elimination with partial
// pivoting. Returns false for degenerate point sets (three collinear).
bool homography4(const PointF src[4], const PointF dst[4], double h[9]);

// Inverse of a 3x3 row-major matrix via the adjugate. Returns false when the
// determinant is ~0.
bool invert3x3(const double h[9], double out[9]);

// Projects a point through a 3x3 row-major homography (perspective divide).
void applyHomography(const double h[9], float x, float y, float& outX, float& outY);

}  // namespace stt::sign

#endif  // STT_HAND_GEOMETRY_H
