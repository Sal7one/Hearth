#pragma once
#include "buffer_view.h"
#include <cstdint>

namespace stt {
inline const char* pcmBufferRangeError(std::int64_t capacity, int offset, int count, int rate) {
    if (rate <= 0) return "PCM sampleRate must be positive";
    if (offset < 0 || (offset & 1)) return "PCM byteOffset must be non-negative and even";
    if (count <= 0 || (count & 1)) return "PCM byteCount must be positive and even";
    std::size_t end = 0;
    if (capacity < 0 || !buffers::checkedAdd(static_cast<std::size_t>(offset),
            static_cast<std::size_t>(count), end) || end > static_cast<std::uint64_t>(capacity)) {
        return "PCM direct buffer range out of bounds";
    }
    return nullptr;
}
}
