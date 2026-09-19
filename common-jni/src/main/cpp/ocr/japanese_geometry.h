#pragma once
#include "ocr_geometry.h"
namespace hearth::ocr {
struct CharacterBox { int code; float start, end, confidence; };
inline std::vector<CharacterBox> characters(std::vector<CharacterBox> candidates) {
    for (const auto& c : candidates)
        if (!std::isfinite(c.start) || !std::isfinite(c.end) || !std::isfinite(c.confidence) || c.end <= c.start ||
            c.code < 32 || c.code > 0x10ffff || (c.code >= 0xd800 && c.code <= 0xdfff))
            throw std::runtime_error("Invalid Meiki character geometry");
    std::stable_sort(candidates.begin(), candidates.end(), [](auto& a, auto& b) { return a.confidence > b.confidence; });
    std::vector<CharacterBox> accepted;
    for (auto& c : candidates) {
        bool overlaps = false;
        for (auto& a : accepted) {
            auto intersection = std::max(0.f, std::min(c.end,a.end)-std::max(c.start,a.start));
            if (intersection / std::min(c.end-c.start,a.end-a.start) > .3f) { overlaps=true; break; }
        }
        if (!overlaps) accepted.push_back(c);
    }
    std::sort(accepted.begin(), accepted.end(), [](auto& a, auto& b) { return a.start < b.start; });
    return accepted;
}
}
