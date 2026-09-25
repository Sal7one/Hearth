// =============================================================================
// Hand-pipeline geometry implementation — see hand_geometry.h.
// =============================================================================

#include "hand_geometry.h"

#include <algorithm>
#include <cmath>

namespace stt::sign {

namespace {
// Bilinear RGBA sample -> RGB float triple. Weights outside the source fade
// to the BORDER_CONSTANT 0 plane (cv::resize / MediaPipe letterbox behavior).
inline void bilinearRgbaToRgb(const uint8_t* rgba, int width, int height,
                              float fx, float fy, float* outRgb) {
    const float x0f = std::floor(fx);
    const float y0f = std::floor(fy);
    const float tx = fx - x0f;
    const float ty = fy - y0f;
    const int x0 = static_cast<int>(x0f);
    const int y0 = static_cast<int>(y0f);

    for (int c = 0; c < 3; ++c) {
        float value = 0.f;
        for (int dy = 0; dy < 2; ++dy) {
            const int y = y0 + dy;
            const bool yIn = y >= 0 && y < height;
            const float wy = dy == 0 ? 1.f - ty : ty;
            for (int dx = 0; dx < 2; ++dx) {
                const int x = x0 + dx;
                const bool xIn = x >= 0 && x < width;
                const float wx = dx == 0 ? 1.f - tx : tx;
                const float sample =
                    (xIn && yIn)
                        ? static_cast<float>(
                              rgba[(static_cast<size_t>(y) * width + x) * 4 + c])
                        : 0.f;
                value += sample * wx * wy;
            }
        }
        outRgb[c] = value;
    }
}
}  // namespace

void letterboxRgbaToRgbFloat(const uint8_t* rgba, int srcWidth, int srcHeight,
                             float* dst, int dstSize, Letterbox& box) {
    const float scale = std::min(static_cast<float>(dstSize) / static_cast<float>(srcHeight),
                                 static_cast<float>(dstSize) / static_cast<float>(srcWidth));
    // cv2.resize/round-to-nearest placement, matching the verified mirror.
    box.width = std::max(1, static_cast<int>(std::lround(srcWidth * scale)));
    box.height = std::max(1, static_cast<int>(std::lround(srcHeight * scale)));
    box.offsetX = (dstSize - box.width) / 2;
    box.offsetY = (dstSize - box.height) / 2;

    std::fill_n(dst, static_cast<size_t>(dstSize) * dstSize * 3, 0.f);
    const float invScaleX = static_cast<float>(srcWidth) / static_cast<float>(box.width);
    const float invScaleY = static_cast<float>(srcHeight) / static_cast<float>(box.height);
    for (int y = 0; y < box.height; ++y) {
        const float fy = (static_cast<float>(y) + 0.5f) * invScaleY - 0.5f;
        for (int x = 0; x < box.width; ++x) {
            const float fx = (static_cast<float>(x) + 0.5f) * invScaleX - 0.5f;
            bilinearRgbaToRgb(rgba, srcWidth, srcHeight, fx, fy,
                              dst + (static_cast<size_t>(box.offsetY + y) * dstSize +
                                     (box.offsetX + x)) * 3);
        }
    }
}

void rotatedRectPoints(float centerX, float centerY, float width, float height,
                       float angleDeg, PointF out[4]) {
    // cv::RotatedRect::points() formula, kept bit-compatible so the corner
    // order (bl, tl, tr, br) and the homography pairing are unchanged.
    const double angle = static_cast<double>(angleDeg) * 3.14159265358979323846 / 180.0;
    const double b = std::cos(angle) * 0.5;
    const double a = std::sin(angle) * 0.5;
    out[0].x = static_cast<float>(centerX - a * height - b * width);
    out[0].y = static_cast<float>(centerY + b * height - a * width);
    out[1].x = static_cast<float>(centerX + a * height - b * width);
    out[1].y = static_cast<float>(centerY - b * height - a * width);
    out[2].x = 2.f * centerX - out[0].x;
    out[2].y = 2.f * centerY - out[0].y;
    out[3].x = 2.f * centerX - out[1].x;
    out[3].y = 2.f * centerY - out[1].y;
}

bool homography4(const PointF src[4], const PointF dst[4], double h[9]) {
    // For each correspondence: dst = H(src) solves
    //   [x y 1 0 0 0 -x*x' -y*x'] h = x'
    //   [0 0 0 x y 1 -x*y' -y*y'] h = y'
    double m[8][9] = {};
    for (int i = 0; i < 4; ++i) {
        const double x = src[i].x, y = src[i].y;
        const double xp = dst[i].x, yp = dst[i].y;
        double* r0 = m[2 * i];
        double* r1 = m[2 * i + 1];
        r0[0] = x; r0[1] = y; r0[2] = 1; r0[6] = -x * xp; r0[7] = -y * xp; r0[8] = xp;
        r1[3] = x; r1[4] = y; r1[5] = 1; r1[6] = -x * yp; r1[7] = -y * yp; r1[8] = yp;
    }
    for (int col = 0; col < 8; ++col) {
        int pivot = col;
        for (int r = col + 1; r < 8; ++r) {
            if (std::fabs(m[r][col]) > std::fabs(m[pivot][col])) pivot = r;
        }
        if (std::fabs(m[pivot][col]) < 1e-12) return false;
        if (pivot != col) {
            for (int c = 0; c < 9; ++c) std::swap(m[pivot][c], m[col][c]);
        }
        for (int r = 0; r < 8; ++r) {
            if (r == col) continue;
            const double factor = m[r][col] / m[col][col];
            for (int c = col; c < 9; ++c) m[r][c] -= factor * m[col][c];
        }
    }
    h[0] = m[0][8] / m[0][0];
    h[1] = m[1][8] / m[1][1];
    h[2] = m[2][8] / m[2][2];
    h[3] = m[3][8] / m[3][3];
    h[4] = m[4][8] / m[4][4];
    h[5] = m[5][8] / m[5][5];
    h[6] = m[6][8] / m[6][6];
    h[7] = m[7][8] / m[7][7];
    h[8] = 1.0;
    return true;
}

bool invert3x3(const double h[9], double out[9]) {
    // Adjugate / determinant; inputs are small and well-conditioned crops.
    const double a11 = h[4] * h[8] - h[5] * h[7];
    const double a12 = h[3] * h[8] - h[5] * h[6];
    const double a13 = h[3] * h[7] - h[4] * h[6];
    const double a21 = h[1] * h[8] - h[2] * h[7];
    const double a22 = h[0] * h[8] - h[2] * h[6];
    const double a23 = h[0] * h[7] - h[1] * h[6];
    const double a31 = h[1] * h[5] - h[2] * h[4];
    const double a32 = h[0] * h[5] - h[2] * h[3];
    const double a33 = h[0] * h[4] - h[1] * h[3];
    const double det = h[0] * a11 - h[1] * a12 + h[2] * a13;
    if (std::fabs(det) < 1e-12) return false;
    out[0] = a11 / det;
    out[1] = -a21 / det;
    out[2] = a31 / det;
    out[3] = -a12 / det;
    out[4] = a22 / det;
    out[5] = -a32 / det;
    out[6] = a13 / det;
    out[7] = -a23 / det;
    out[8] = a33 / det;
    return true;
}

void applyHomography(const double h[9], float x, float y, float& outX, float& outY) {
    const double d = h[6] * x + h[7] * y + h[8];
    outX = static_cast<float>((h[0] * x + h[1] * y + h[2]) / d);
    outY = static_cast<float>((h[3] * x + h[4] * y + h[5]) / d);
}

}  // namespace stt::sign
