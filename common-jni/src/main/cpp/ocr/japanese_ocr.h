#pragma once
#include "paddle_ocr.h"
namespace hearth::ocr {
class JapaneseOcr {
public:
    JapaneseOcr(int kind, const std::string& first, const std::string& second, const std::string& third);
    ~JapaneseOcr();
    std::vector<Line> recognize(const uint32_t* pixels, int width, int height);
    void cancel();
private:
    struct Impl; std::unique_ptr<Impl> impl;
};
}
