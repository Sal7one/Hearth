#ifndef STT_BUFFER_VIEW_H
#define STT_BUFFER_VIEW_H

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>

namespace stt::buffers {

enum class Status : std::uint8_t {
    OK = 0,
    NULL_DATA,
    INVALID_GEOMETRY,
    SIZE_OVERFLOW,
    SOURCE_TOO_SMALL,
    DESTINATION_TOO_SMALL,
    MISMATCHED_GEOMETRY,
    OVERLAPPING_BUFFERS,
};

inline const char* statusMessage(Status status) noexcept {
    switch (status) {
        case Status::OK: return "ok";
        case Status::NULL_DATA: return "null buffer data";
        case Status::INVALID_GEOMETRY: return "invalid buffer geometry";
        case Status::SIZE_OVERFLOW: return "buffer size overflow";
        case Status::SOURCE_TOO_SMALL: return "source buffer is too small";
        case Status::DESTINATION_TOO_SMALL: return "destination buffer is too small";
        case Status::MISMATCHED_GEOMETRY: return "plane geometry does not match";
        case Status::OVERLAPPING_BUFFERS: return "source and destination buffers overlap";
    }
    return "unknown buffer status";
}

inline bool checkedAdd(std::size_t lhs, std::size_t rhs, std::size_t& result) noexcept {
    if (rhs > std::numeric_limits<std::size_t>::max() - lhs) return false;
    result = lhs + rhs;
    return true;
}

inline bool checkedMultiply(std::size_t lhs, std::size_t rhs, std::size_t& result) noexcept {
    if (lhs != 0 && rhs > std::numeric_limits<std::size_t>::max() / lhs) return false;
    result = lhs * rhs;
    return true;
}

inline bool byteRangesOverlap(
    const void* first,
    std::size_t firstBytes,
    const void* second,
    std::size_t secondBytes
) noexcept {
    if (firstBytes == 0 || secondBytes == 0) return false;
    const std::uintptr_t firstBegin = reinterpret_cast<std::uintptr_t>(first);
    const std::uintptr_t secondBegin = reinterpret_cast<std::uintptr_t>(second);
    if (firstBegin > std::numeric_limits<std::uintptr_t>::max() - firstBytes ||
        secondBegin > std::numeric_limits<std::uintptr_t>::max() - secondBytes) {
        return true;
    }
    return firstBegin < secondBegin + secondBytes && secondBegin < firstBegin + firstBytes;
}

struct ConstByteView {
    const std::uint8_t* data = nullptr;
    std::size_t size = 0;
};

struct MutableByteView {
    std::uint8_t* data = nullptr;
    std::size_t size = 0;
};

/**
 * A non-owning view of one image plane. `columns` is the number of logical
 * samples per row, not the number of bytes. This keeps validation independent
 * of Android, JNI, OpenCV, and the owning buffer implementation.
 */
struct StridedPlaneView {
    ConstByteView bytes;
    std::size_t rows = 0;
    std::size_t columns = 0;
    std::size_t rowStride = 0;
    std::size_t pixelStride = 0;

    Status requiredBytes(std::size_t& required) const noexcept {
        required = 0;
        if (rows == 0 || columns == 0 || rowStride == 0 || pixelStride == 0) {
            return Status::INVALID_GEOMETRY;
        }

        std::size_t rowSpan = 0;
        std::size_t lastColumnOffset = 0;
        if (!checkedMultiply(columns - 1, pixelStride, lastColumnOffset) ||
            !checkedAdd(lastColumnOffset, 1, rowSpan)) {
            return Status::SIZE_OVERFLOW;
        }
        if (rowStride < rowSpan) return Status::INVALID_GEOMETRY;

        std::size_t lastRowOffset = 0;
        if (!checkedMultiply(rows - 1, rowStride, lastRowOffset) ||
            !checkedAdd(lastRowOffset, rowSpan, required)) {
            return Status::SIZE_OVERFLOW;
        }
        return Status::OK;
    }

    Status validate() const noexcept {
        if (!bytes.data) return Status::NULL_DATA;
        std::size_t required = 0;
        const Status status = requiredBytes(required);
        if (status != Status::OK) return status;
        return required <= bytes.size ? Status::OK : Status::SOURCE_TOO_SMALL;
    }
};

inline Status packedPlaneSize(
    std::size_t rows,
    std::size_t columns,
    std::size_t& required
) noexcept {
    required = 0;
    if (rows == 0 || columns == 0) return Status::INVALID_GEOMETRY;
    return checkedMultiply(rows, columns, required) ? Status::OK : Status::SIZE_OVERFLOW;
}

/** Copy a possibly padded/strided plane into tightly packed storage. */
inline Status copyPlanePacked(
    const StridedPlaneView& source,
    MutableByteView destination
) noexcept {
    const Status sourceStatus = source.validate();
    if (sourceStatus != Status::OK) return sourceStatus;

    std::size_t required = 0;
    const Status sizeStatus = packedPlaneSize(source.rows, source.columns, required);
    if (sizeStatus != Status::OK) return sizeStatus;
    if (!destination.data) return Status::NULL_DATA;
    if (destination.size < required) return Status::DESTINATION_TOO_SMALL;

    std::size_t sourceRequired = 0;
    (void)source.requiredBytes(sourceRequired);
    if (source.bytes.data == destination.data && source.pixelStride == 1 &&
        source.rowStride == source.columns) {
        return Status::OK;
    }
    if (byteRangesOverlap(
            source.bytes.data, sourceRequired, destination.data, required
        )) {
        return Status::OVERLAPPING_BUFFERS;
    }

    if (source.pixelStride == 1 && source.rowStride == source.columns) {
        std::memcpy(destination.data, source.bytes.data, required);
        return Status::OK;
    }

    for (std::size_t row = 0; row < source.rows; ++row) {
        const std::uint8_t* sourceRow = source.bytes.data + row * source.rowStride;
        std::uint8_t* destinationRow = destination.data + row * source.columns;
        if (source.pixelStride == 1) {
            std::memcpy(destinationRow, sourceRow, source.columns);
            continue;
        }
        for (std::size_t column = 0; column < source.columns; ++column) {
            destinationRow[column] = sourceRow[column * source.pixelStride];
        }
    }
    return Status::OK;
}

/**
 * Pack two equally shaped planes into A,B,A,B byte order. A common Android
 * fast path is detected when the two views are adjacent aliases of an already
 * interleaved plane; complete rows are copied in that case.
 */
inline Status interleavePlanes(
    const StridedPlaneView& first,
    const StridedPlaneView& second,
    MutableByteView destination
) noexcept {
    const Status firstStatus = first.validate();
    if (firstStatus != Status::OK) return firstStatus;
    const Status secondStatus = second.validate();
    if (secondStatus != Status::OK) return secondStatus;
    if (first.rows != second.rows || first.columns != second.columns) {
        return Status::MISMATCHED_GEOMETRY;
    }

    std::size_t samples = 0;
    std::size_t required = 0;
    if (!checkedMultiply(first.rows, first.columns, samples) ||
        !checkedMultiply(samples, 2, required)) {
        return Status::SIZE_OVERFLOW;
    }
    if (!destination.data) return Status::NULL_DATA;
    if (destination.size < required) return Status::DESTINATION_TOO_SMALL;
    std::size_t firstRequired = 0;
    std::size_t secondRequired = 0;
    (void)first.requiredBytes(firstRequired);
    (void)second.requiredBytes(secondRequired);

    const std::uintptr_t firstAddress = reinterpret_cast<std::uintptr_t>(first.bytes.data);
    const std::uintptr_t secondAddress = reinterpret_cast<std::uintptr_t>(second.bytes.data);
    std::size_t rowBytes = 0;
    std::size_t directRequired = 0;
    const bool directSizeValid =
        checkedMultiply(first.columns, 2, rowBytes) &&
        checkedMultiply(first.rows - 1, first.rowStride, directRequired) &&
        checkedAdd(directRequired, rowBytes, directRequired);
    const bool alreadyInterleaved =
        first.pixelStride == 2 && second.pixelStride == 2 &&
        first.rowStride == second.rowStride && first.rowStride >= rowBytes &&
        firstAddress != std::numeric_limits<std::uintptr_t>::max() &&
        firstAddress + 1 == secondAddress && directSizeValid &&
        directRequired <= first.bytes.size;

    // The direct row-copy path reads both channels, including the byte after
    // the last logical sample. Include that complete access span in overlap
    // validation rather than only the first plane's logical footprint.
    const std::size_t firstAccessBytes = alreadyInterleaved ? directRequired : firstRequired;
    if (byteRangesOverlap(first.bytes.data, firstAccessBytes, destination.data, required) ||
        byteRangesOverlap(second.bytes.data, secondRequired, destination.data, required)) {
        return Status::OVERLAPPING_BUFFERS;
    }

    if (alreadyInterleaved) {
        for (std::size_t row = 0; row < first.rows; ++row) {
            std::memcpy(
                destination.data + row * rowBytes,
                first.bytes.data + row * first.rowStride,
                rowBytes
            );
        }
        return Status::OK;
    }

    for (std::size_t row = 0; row < first.rows; ++row) {
        const std::uint8_t* firstRow = first.bytes.data + row * first.rowStride;
        const std::uint8_t* secondRow = second.bytes.data + row * second.rowStride;
        std::uint8_t* destinationRow = destination.data + row * first.columns * 2;
        for (std::size_t column = 0; column < first.columns; ++column) {
            destinationRow[column * 2] = firstRow[column * first.pixelStride];
            destinationRow[column * 2 + 1] = secondRow[column * second.pixelStride];
        }
    }
    return Status::OK;
}

} // namespace stt::buffers

#endif // STT_BUFFER_VIEW_H
