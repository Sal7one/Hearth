#pragma once
#include "ocr_geometry.h"
#include <memory>
#include <string>

namespace hearth::ocr {
struct ComicDetection { Box box; int label; };
// ogkalu's pinned RT-DETR-v2 small ONNX export. No OCR, translation or image reconstruction.
class ComicDetector {
public:
    explicit ComicDetector(const std::string& model);
    ~ComicDetector();
    std::vector<ComicDetection> detect(const uint32_t* pixels, int width, int height);
    void cancel();
private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};
}
