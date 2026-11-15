#pragma once

#include "ocr_geometry.h"

#include <memory>
#include <string>

namespace hearth::ocr {

class MangaLineOcr {
public:
    static constexpr int maxImageSide = 2048;
    static constexpr int maxLines = 64;

    MangaLineOcr(const std::string& detector, const std::string& recognizer, int workers);
    ~MangaLineOcr();

    std::vector<Box> detect(const uint32_t* pixels, int width, int height, int maxSide);
    std::vector<std::vector<int>> recognize(const uint32_t* pixels, int width, int height,
                                            const std::vector<Box>& boxes);
    // Cancellation is terminal for this handle and may race an active call.
    void cancel() noexcept;

private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};

} // namespace hearth::ocr
