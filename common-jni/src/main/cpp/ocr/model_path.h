#pragma once
#include <filesystem>
#include <string>

namespace hearth::ocr {
// ORT consumes UTF-16 paths on Windows and UTF-8 paths on Android/macOS.
inline auto nativeModelPath(const std::string& utf8) { return std::filesystem::u8path(utf8).native(); }
}
