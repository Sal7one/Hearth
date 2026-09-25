// =============================================================================
// Hand landmark pipeline (sign language) — MediaPipe hand-landmark semantics
// running as ONNX on the vendored ONNX Runtime. No Google runtimes (LiteRT /
// TFLite / ML Kit): the shipped models are ONNX conversions of the
// Apache-2.0 MediaPipe hand landmarker bundle (scripts/sign/convert/).
// Portable C++: no Android code, no JNI, no UI.
//
//   <dir>/hand_detector.onnx              palm detection  [1,192,192,3]
//   <dir>/hand_landmarks_detector.onnx    landmarks       [1,224,224,3]
//
// Every semantic constant below (anchors, decode, rect, normalization) was
// verified against the official MediaPipe 0.10.14 runtime by
// scripts/sign/fidelity_check.py; the mirror reaches the official-runtime
// agreement floor (mean |dxy| 0.0135 vs 0.0139 for MediaPipe's own Tasks
// pipeline, 60-image AASL set). See research/sign/fidelity/REPORT.md before
// changing anything.
//
//   palm input : letterboxed 192x192, RGB, [0,1]
//   anchors    : 2016, all w=h=1 (fixed_anchor_size); rows 0..1151 = 24x24
//                grid (stride 8) 2/cell y-major; rows 1152..2015 = 12x12
//                grid (stride 16) 6/cell (three layers cell-interleaved)
//   decode     : cx=r0/192+ax, cy=r1/192+ay, w=r2/192, h=r3/192 (x-first);
//                keypoints (r[4+2k]/192+ax, r[5+2k]/192+ay);
//                scores sigmoid(clip +-100), threshold 0.5, greedy NMS 0.3
//   hand rect  : center = box center; rot = 63.3798deg - atan2(-dy_px,dx_px)
//                (kp2-kp0 in pixels); side_px = max(2.6*w0*W, 2.6*h0*H);
//                center += (0.5*h0px*sin(rot), -0.5*h0px*cos(rot))
//   landmarks  : outputs [landmarks(63), presence, handedness, world(63)];
//                x=raw/224, y=raw/224, z=raw/(224*0.4), then inverse-projected
//                through the crop rect; hand kept when sigmoid(presence)>=0.5
// =============================================================================

#ifndef STT_SIGN_HAND_LANDMARKS_H
#define STT_SIGN_HAND_LANDMARKS_H

#include "hand_geometry.h"
#include "ort_session.h"

#include <cstddef>
#include <string>
#include <vector>

namespace stt::sign {

struct HandLandmarkPoint {
    float x = 0.f;  // normalized image space [0,1]
    float y = 0.f;  // normalized image space [0,1]
    float z = 0.f;  // MediaPipe z semantics: raw/(224*0.4), crop-relative
};

struct HandLandmarkResult {
    static constexpr int kPoints = 21;

    HandLandmarkPoint points[kPoints];
    int pointCount = 0;
    float score = 0.f;           // palm detection score (post-sigmoid)
    float handPresence = 0.f;    // sigmoid(hand_flag)
    float handednessRight = 0.f;  // sigmoid of the Right-class score
};

class HandLandmarkPipeline {
public:
    // Both .onnx files must exist in [modelDir].
    bool init(const std::string& modelDir, int numThreads = 4);

    bool isReady() const { return ready_; }

    // rgba: tightly packed RGBA (row-major), width x height. Returns 0..maxHands
    // results, best first. A bad frame yields zero hands, never an error after
    // init() succeeded; check lastError() for diagnosis.
    std::vector<HandLandmarkResult> detect(const uint8_t* rgba, int width, int height,
                                           float palmThreshold = 0.5f,
                                           int maxHands = 2);

    const std::string& lastError() const { return lastError_; }

    // Exposed for host tests: anchor table + SSD decode.
    struct Anchor {
        float cx, cy, w, h;
    };
    void buildAnchors(std::vector<Anchor>& anchors) const;
    static bool decodePalms(const float* scoresRaw, const float* regressions,
                            size_t regressionFloats, const std::vector<Anchor>& anchors,
                            float threshold,
                            std::vector<float>& outBoxes,     // x, y, w, h per box
                            std::vector<float>& outKeypoints,  // 7 x (x, y) per box
                            std::vector<float>& outScores);
    // Exposed for host tests: verified MediaPipe hand-rect math. Inputs are
    // image-normalized (box, keypoints, center); width/height are pixels.
    static bool handRect(const float* box, const float* keypoints, int width,
                         int height, PointF& outCenterPx, float& outSidePx,
                         float& outRotationDeg);

private:
    bool detectPalms(const uint8_t* rgba, int width, int height, float threshold,
                     std::vector<float>& boxes, std::vector<float>& keypoints,
                     std::vector<float>& scores);
    bool landmarksForCrop(const uint8_t* rgba, int width, int height,
                          const PointF centerPx, float sidePx, float angleDeg,
                          HandLandmarkResult& out);

    OrtSession palm_;
    OrtSession landmarks_;
    std::vector<Anchor> anchors_;
    bool ready_ = false;
    std::string lastError_;

    // Scratch buffers reused across frames (detect() is externally serialized).
    std::vector<float> palmInput_;
    std::vector<float> cropInput_;
};

}  // namespace stt::sign

#endif  // STT_SIGN_HAND_LANDMARKS_H
