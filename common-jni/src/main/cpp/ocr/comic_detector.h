#pragma once
#include "ocr_geometry.h"
#include <memory>
#include <string>

namespace hearth::ocr {
// A mask is encoded as ordered page-space (y, x, length) foreground spans.
struct ComicDetection { Box box; int label; std::vector<int32_t> mask; };
// Pinned ONNX adapters: compact RT-DETR (0), Koharu RF-DETR segmentation (1), LaMa (2).
class ComicDetector {
public:
    explicit ComicDetector(const std::string& model, int mode = 0);
    ~ComicDetector();
    std::vector<ComicDetection> detect(const uint32_t* pixels, int width, int height);
    std::vector<int32_t> inpaint(const uint32_t* pixels, int width, int height, const std::vector<int32_t>& spans);
    void cancel();
private:
    struct Impl;
    std::unique_ptr<Impl> impl;
    std::vector<ComicDetection> detectKoharu(const uint32_t* pixels, int width, int height);
};
}
