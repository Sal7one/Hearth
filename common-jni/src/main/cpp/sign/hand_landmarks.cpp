// =============================================================================
// Hand landmark pipeline implementation — verified MediaPipe semantics over
// ONNX Runtime. See hand_landmarks.h for the pinned constants and their
// provenance (scripts/sign/fidelity_check.py).
// =============================================================================

#include "hand_landmarks.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>

namespace stt::sign {

namespace {
constexpr int kPalmInput = 192;
constexpr int kLandmarkInput = 224;
constexpr int kNumAnchors = 2016;
constexpr int kPalmKeyPoints = 7;
constexpr int kBoxStride = 4;                             // cx, cy, w, h
constexpr int kRegressionStride = 4 + 2 * kPalmKeyPoints;  // box + 7 keypoints
constexpr float kNmsIou = 0.3f;
constexpr float kSigmoidClip = 100.0f;

// Verified preprocessing: both graphs take RGB in [0,1].
constexpr float kInputValueMax = 255.0f;
// Verified decode divisor (MediaPipe x/y/w/h scales for the palm graph).
constexpr float kDecodeDivisor = 192.0f;
// Verified rect constants (RectTransformation: scale 2.6, shift_y -0.5) plus
// the deployed wheel's constant rotation phase (90 - 26.6202 deg; measured
// to 4e-4 deg over 12 images — see the fidelity report).
constexpr float kRectScale = 2.6f;
constexpr float kRotationBaseDeg = 63.3798f;
// Verified landmark normalization (normalize_z = 0.4).
constexpr float kZDivisor = 224.0f * 0.4f;
constexpr float kPresenceGate = 0.5f;

// Palm model output sizes used to identify scores ([2016]) vs regressions
// ([2016*18]) regardless of output order.
constexpr size_t kScoresFloats = kNumAnchors;
constexpr size_t kRegressionsFloats = static_cast<size_t>(kNumAnchors) * kRegressionStride;

inline float sigmoid(float x) {
    return 1.0f / (1.0f + std::exp(-x));
}
}  // namespace

bool HandLandmarkPipeline::init(const std::string& modelDir, int numThreads) {
    ready_ = false;
    {
        std::ifstream palm(modelDir + "/hand_detector.onnx");
        std::ifstream lm(modelDir + "/hand_landmarks_detector.onnx");
        if (!palm.good() || !lm.good()) {
            lastError_ =
                "Need hand_detector.onnx and hand_landmarks_detector.onnx in " + modelDir;
            return false;
        }
    }
    if (!palm_.load(modelDir + "/hand_detector.onnx", numThreads)) {
        lastError_ = "palm model: " + palm_.lastError();
        return false;
    }
    if (!landmarks_.load(modelDir + "/hand_landmarks_detector.onnx", numThreads)) {
        lastError_ = "landmark model: " + landmarks_.lastError();
        return false;
    }
    buildAnchors(anchors_);
    if (anchors_.size() != static_cast<size_t>(kNumAnchors)) {
        lastError_ = "anchor table size mismatch";
        return false;
    }
    palmInput_.resize(static_cast<size_t>(kPalmInput) * kPalmInput * 3);
    cropInput_.resize(static_cast<size_t>(kLandmarkInput) * kLandmarkInput * 3);
    ready_ = true;
    lastError_.clear();
    return true;
}

// Verified MediaPipe anchor table (fixed_anchor_size -> w=h=1):
// rows 0..1151   : 24x24 grid (stride 8), y-major, 2 anchors per cell;
// rows 1152..2015: 12x12 grid (stride 16), y-major, 6 anchors per cell
// (the three stride-16 layers are cell-interleaved, not sequential blocks).
void HandLandmarkPipeline::buildAnchors(std::vector<Anchor>& anchors) const {
    anchors.clear();
    anchors.reserve(kNumAnchors);
    for (int r = 0; r < 1152; ++r) {
        const int cell = r / 2;
        Anchor a;
        a.cx = (static_cast<float>(cell % 24) + 0.5f) / 24.f;
        a.cy = (static_cast<float>(cell / 24) + 0.5f) / 24.f;
        a.w = 1.0f;
        a.h = 1.0f;
        anchors.push_back(a);
    }
    for (int r = 1152; r < kNumAnchors; ++r) {
        const int cell = (r - 1152) / 6;
        Anchor a;
        a.cx = (static_cast<float>(cell % 12) + 0.5f) / 12.f;
        a.cy = (static_cast<float>(cell / 12) + 0.5f) / 12.f;
        a.w = 1.0f;
        a.h = 1.0f;
        anchors.push_back(a);
    }
}

// Pure SSD decode + greedy NMS over raw (pre-sigmoid) scores and raw
// regressions, with the verified /192 absolute-offset semantics. Static so
// host tests can drive it with synthetic tensors.
bool HandLandmarkPipeline::decodePalms(
    const float* scoresRaw, const float* regressions, size_t regressionFloats,
    const std::vector<Anchor>& anchors, float threshold,
    std::vector<float>& outBoxes, std::vector<float>& outKeypoints,
    std::vector<float>& outScores) {
    outBoxes.clear();
    outKeypoints.clear();
    outScores.clear();
    if (anchors.empty() || regressions == nullptr || scoresRaw == nullptr) return false;
    if (regressionFloats < anchors.size() * kRegressionStride) return false;

    struct Candidate {
        float box[kBoxStride];
        float kps[2 * kPalmKeyPoints];
        float score;
    };
    std::vector<Candidate> candidates;
    for (size_t i = 0; i < anchors.size(); ++i) {
        const float clipped =
            std::max(-kSigmoidClip, std::min(kSigmoidClip, scoresRaw[i]));
        const float score = sigmoid(clipped);
        if (score < threshold) continue;

        const Anchor& a = anchors[i];
        const float* r = regressions + i * kRegressionStride;
        // Verified decode: absolute offsets divided by 192, x-first layout.
        const float cx = r[0] / kDecodeDivisor + a.cx;
        const float cy = r[1] / kDecodeDivisor + a.cy;
        const float w = r[2] / kDecodeDivisor;
        const float h = r[3] / kDecodeDivisor;

        Candidate c;
        c.box[0] = cx - w / 2;
        c.box[1] = cy - h / 2;
        c.box[2] = w;
        c.box[3] = h;
        c.score = score;
        for (int k = 0; k < kPalmKeyPoints; ++k) {
            c.kps[2 * k] = r[4 + 2 * k] / kDecodeDivisor + a.cx;
            c.kps[2 * k + 1] = r[5 + 2 * k] / kDecodeDivisor + a.cy;
        }
        candidates.push_back(c);
    }

    std::sort(candidates.begin(), candidates.end(),
              [](const Candidate& x, const Candidate& y) { return x.score > y.score; });
    std::vector<bool> suppressed(candidates.size(), false);
    for (size_t i = 0; i < candidates.size(); ++i) {
        if (suppressed[i]) continue;
        for (size_t j = i + 1; j < candidates.size(); ++j) {
            if (suppressed[j]) continue;
            const float x1 = std::max(candidates[i].box[0], candidates[j].box[0]);
            const float y1 = std::max(candidates[i].box[1], candidates[j].box[1]);
            const float x2 = std::min(candidates[i].box[0] + candidates[i].box[2],
                                      candidates[j].box[0] + candidates[j].box[2]);
            const float y2 = std::min(candidates[i].box[1] + candidates[i].box[3],
                                      candidates[j].box[1] + candidates[j].box[3]);
            const float inter = std::max(0.f, x2 - x1) * std::max(0.f, y2 - y1);
            const float areaI = candidates[i].box[2] * candidates[i].box[3];
            const float areaJ = candidates[j].box[2] * candidates[j].box[3];
            const float unionArea = areaI + areaJ - inter;
            if (unionArea > 0.f && inter / unionArea > kNmsIou) suppressed[j] = true;
        }
        outBoxes.insert(outBoxes.end(), candidates[i].box, candidates[i].box + kBoxStride);
        outKeypoints.insert(outKeypoints.end(), candidates[i].kps,
                            candidates[i].kps + 2 * kPalmKeyPoints);
        outScores.push_back(candidates[i].score);
    }
    return true;
}

// Verified MediaPipe hand-rect math (DetectionsToRects + RectTransformation,
// measured against the official runtime):
//   rotation = 63.3798deg - atan2(-dy_px, dx_px)   [dx,dy = kp2-kp0 in pixels]
//   side_px  = max(2.6*w0*W, 2.6*h0*H)             [square_long in pixels]
//   center  += (0.5*h0px*sin(rot), -0.5*h0px*cos(rot))
bool HandLandmarkPipeline::handRect(const float* box, const float* keypoints, int width,
                                    int height, PointF& outCenterPx, float& outSidePx,
                                    float& outRotationDeg) {
    const float cx = box[0] + box[2] / 2.f;
    const float cy = box[1] + box[3] / 2.f;
    const float dx = (keypoints[2 * 2] - keypoints[0]) * static_cast<float>(width);
    const float dy = (keypoints[2 * 2 + 1] - keypoints[1]) * static_cast<float>(height);
    const float rotationDeg =
        kRotationBaseDeg - std::atan2(-dy, dx) * 180.f / static_cast<float>(M_PI);
    const float w0px = box[2] * static_cast<float>(width);
    const float h0px = box[3] * static_cast<float>(height);
    const float sidePx = std::max(kRectScale * w0px, kRectScale * h0px);
    const float rot = rotationDeg * static_cast<float>(M_PI) / 180.f;
    outCenterPx.x = cx * static_cast<float>(width) + 0.5f * h0px * std::sin(rot);
    outCenterPx.y = cy * static_cast<float>(height) - 0.5f * h0px * std::cos(rot);
    outSidePx = sidePx;
    outRotationDeg = rotationDeg;
    return sidePx > 0.f && std::isfinite(sidePx);
}

bool HandLandmarkPipeline::detectPalms(const uint8_t* rgba, int width, int height,
                                       float threshold, std::vector<float>& boxes,
                                       std::vector<float>& keypoints,
                                       std::vector<float>& scores) {
    Letterbox box2;
    letterboxRgbaToRgbFloat(rgba, width, height, palmInput_.data(), kPalmInput, box2);
    for (float& v : palmInput_) v = v / kInputValueMax;

    std::vector<std::vector<float>> outputs;
    if (!palm_.invoke(palmInput_.data(), palmInput_.size(), outputs)) {
        lastError_ = "palm invoke: " + palm_.lastError();
        return false;
    }
    // Identify scores ([2016]) and regressions ([2016*18]) by size: the
    // conversion's output order is regressions-first, but shape detection
    // keeps this robust against a re-conversion reordering outputs.
    const float* scoresData = nullptr;
    const float* regData = nullptr;
    for (const auto& output : outputs) {
        if (output.size() == kScoresFloats) {
            scoresData = output.data();
        } else if (output.size() == kRegressionsFloats) {
            regData = output.data();
        }
    }
    if (scoresData == nullptr || regData == nullptr) {
        lastError_ = "palm model outputs do not match the expected shapes";
        return false;
    }
    if (!decodePalms(scoresData, regData, kRegressionsFloats, anchors_, threshold, boxes,
                     keypoints, scores)) {
        lastError_ = "palm decode failed";
        return false;
    }

    // Undo the letterbox: detection coordinates -> image-normalized.
    const float invW = 1.f / static_cast<float>(box2.width);
    const float invH = 1.f / static_cast<float>(box2.height);
    const auto unmapX = [&](float v) {
        return (v * static_cast<float>(kPalmInput) - static_cast<float>(box2.offsetX)) *
               invW;
    };
    const auto unmapY = [&](float v) {
        return (v * static_cast<float>(kPalmInput) - static_cast<float>(box2.offsetY)) *
               invH;
    };
    for (size_t i = 0; i < scores.size(); ++i) {
        float* b = boxes.data() + i * kBoxStride;
        const float x0 = unmapX(b[0]);
        const float y0 = unmapY(b[1]);
        const float x1 = unmapX(b[0] + b[2]);
        const float y1 = unmapY(b[1] + b[3]);
        b[0] = x0;
        b[1] = y0;
        b[2] = x1 - x0;
        b[3] = y1 - y0;
        float* kps = keypoints.data() + i * 2 * kPalmKeyPoints;
        for (int k = 0; k < kPalmKeyPoints; ++k) {
            kps[2 * k] = unmapX(kps[2 * k]);
            kps[2 * k + 1] = unmapY(kps[2 * k + 1]);
        }
    }
    return true;
}

// Builds the 224x224 landmark input for one rotated square crop in a single
// composed bilinear pass (dst pixel -> resized rotated space -> inverse
// homography -> source RGBA), RGB, [0,1]. The verified graph feeds this
// straight into the landmark model.
bool HandLandmarkPipeline::landmarksForCrop(const uint8_t* rgba, int width, int height,
                                            const PointF center, float side,
                                            float angleDeg, HandLandmarkResult& out) {
    if (!(side > 0.f) || !std::isfinite(side)) return false;

    PointF srcPts[4];
    rotatedRectPoints(center.x, center.y, side, side, angleDeg, srcPts);
    const PointF dstPts[4] = {{0.f, side}, {0.f, 0.f}, {side, 0.f}, {side, side}};
    double h[9], inverse[9];
    if (!homography4(srcPts, dstPts, h) || !invert3x3(h, inverse)) return false;

    const float scale = side / static_cast<float>(kLandmarkInput);
    for (int y = 0; y < kLandmarkInput; ++y) {
        const float ry = (static_cast<float>(y) + 0.5f) * scale - 0.5f;
        for (int x = 0; x < kLandmarkInput; ++x) {
            const float rx = (static_cast<float>(x) + 0.5f) * scale - 0.5f;
            float sx, sy;
            applyHomography(inverse, rx, ry, sx, sy);
            const float x0f = std::floor(sx);
            const float y0f = std::floor(sy);
            const float tx = sx - x0f, ty = sy - y0f;
            const int x0 = static_cast<int>(x0f), y0 = static_cast<int>(y0f);
            float* dstPixel =
                cropInput_.data() + (static_cast<size_t>(y) * kLandmarkInput + x) * 3;
            for (int c = 0; c < 3; ++c) {
                float value = 0.f;
                for (int dy = 0; dy < 2; ++dy) {
                    const int yy = y0 + dy;
                    const bool yIn = yy >= 0 && yy < height;
                    const float wy = dy == 0 ? 1.f - ty : ty;
                    for (int dx = 0; dx < 2; ++dx) {
                        const int xx = x0 + dx;
                        const bool xIn = xx >= 0 && xx < width;
                        const float wx = dx == 0 ? 1.f - tx : tx;
                        const float sample =
                            (xIn && yIn)
                                ? static_cast<float>(
                                      rgba[(static_cast<size_t>(yy) * width + xx) * 4 + c])
                                : 0.f;
                        value += sample * wx * wy;
                    }
                }
                dstPixel[c] = value / kInputValueMax;
            }
        }
    }

    std::vector<std::vector<float>> outputs;
    if (!landmarks_.invoke(cropInput_.data(), cropInput_.size(), outputs)) {
        lastError_ = "landmark invoke: " + landmarks_.lastError();
        return false;
    }
    // Verified tensor roles by size: landmarks [63], presence [1],
    // handedness [1], world [63]. Landmarks = first 63-float output.
    const float* lm = nullptr;
    const float* presence = nullptr;
    const float* handedness = nullptr;
    for (const auto& output : outputs) {
        if (lm == nullptr && output.size() >= HandLandmarkResult::kPoints * 3) {
            lm = output.data();
        } else if (presence == nullptr && output.size() == 1) {
            presence = output.data();
        } else if (handedness == nullptr && output.size() == 1) {
            handedness = output.data();
        }
    }
    if (lm == nullptr) {
        lastError_ = "landmark tensor missing";
        return false;
    }

    out.handPresence = presence != nullptr ? sigmoid(presence[0]) : 1.f;
    out.handednessRight = handedness != nullptr ? sigmoid(handedness[0]) : 0.5f;
    out.pointCount = HandLandmarkResult::kPoints;
    for (int i = 0; i < HandLandmarkResult::kPoints; ++i) {
        const float nx = lm[i * 3] / static_cast<float>(kLandmarkInput);
        const float ny = lm[i * 3 + 1] / static_cast<float>(kLandmarkInput);
        const float nz = lm[i * 3 + 2] / kZDivisor;
        // Crop-normalized [0,1] -> rotated pixel space -> original pixels ->
        // normalized image space (inverse homography).
        const float aroundX = nx * side;
        const float aroundY = ny * side;
        float imgX, imgY;
        applyHomography(inverse, aroundX, aroundY, imgX, imgY);
        out.points[i].x = imgX / static_cast<float>(width);
        out.points[i].y = imgY / static_cast<float>(height);
        out.points[i].z = nz;
    }
    return true;
}

std::vector<HandLandmarkResult> HandLandmarkPipeline::detect(const uint8_t* rgba,
                                                             int width, int height,
                                                             float palmThreshold,
                                                             int maxHands) {
    std::vector<HandLandmarkResult> results;
    if (!ready_) {
        lastError_ = "pipeline not initialized";
        return results;
    }
    if (rgba == nullptr || width <= 0 || height <= 0) {
        lastError_ = "invalid frame";
        return results;
    }

    std::vector<float> boxes, keypoints, scores;
    if (!detectPalms(rgba, width, height, palmThreshold, boxes, keypoints, scores)) {
        return results;
    }

    const int count = std::min<int>(static_cast<int>(scores.size()), maxHands);
    for (int i = 0; i < count; ++i) {
        PointF center;
        float side, rotationDeg;
        if (!handRect(boxes.data() + i * kBoxStride,
                      keypoints.data() + i * 2 * kPalmKeyPoints, width, height, center,
                      side, rotationDeg)) {
            continue;
        }

        HandLandmarkResult r;
        r.score = scores[i];
        if (landmarksForCrop(rgba, width, height, center, side, rotationDeg, r) &&
            r.handPresence >= kPresenceGate) {
            results.push_back(r);
        }
    }
    return results;
}

}  // namespace stt::sign
