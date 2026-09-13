#pragma once
#include "ocr_geometry.h"
#include <memory>
#include <string>
namespace hearth::ocr {
struct Line { Box box; Decoded text; };
class PaddleOcr {
public:
    PaddleOcr(const std::string& detector,const std::string& recognizer,int classes);
    ~PaddleOcr();
    std::vector<Line> recognize(const uint32_t* pixels,int width,int height,int maxSide);
    void cancel();
private:
    struct Impl; std::unique_ptr<Impl> impl;
};
}
