#ifndef IMAGE_TYPES_H
#define IMAGE_TYPES_H

#include <cstdint>
#include <cstddef>
#include <memory>
#include <vector>
#include <string>

namespace vision {

/**
 * Pixel formats supported by the vision module.
 * Matches Android's common formats for efficient conversion.
 */
enum class PixelFormat {
    UNKNOWN = 0,
    
    // 8-bit grayscale
    GRAY8,
    
    // RGB formats
    RGB888,      // 3 bytes per pixel: R, G, B
    RGBA8888,    // 4 bytes per pixel: R, G, B, A
    BGR888,      // 3 bytes per pixel: B, G, R (OpenCV default)
    BGRA8888,    // 4 bytes per pixel: B, G, R, A
    
    // YUV formats (common from Android Camera)
    NV21,        // YUV 4:2:0 - Y plane + interleaved VU
    NV12,        // YUV 4:2:0 - Y plane + interleaved UV
    YUV420P,     // YUV 4:2:0 planar - Y, U, V separate planes
    YUV422,      // YUV 4:2:2
    
    // Floating point (for ML models)
    FLOAT32_GRAY,    // Single channel float
    FLOAT32_RGB,     // 3 channel float
};

/**
 * Get bytes per pixel for a format (0 for planar formats).
 */
inline int bytesPerPixel(PixelFormat format) {
    switch (format) {
        case PixelFormat::GRAY8: return 1;
        case PixelFormat::RGB888:
        case PixelFormat::BGR888: return 3;
        case PixelFormat::RGBA8888:
        case PixelFormat::BGRA8888: return 4;
        case PixelFormat::FLOAT32_GRAY: return 4;
        case PixelFormat::FLOAT32_RGB: return 12;
        default: return 0;  // Planar formats
    }
}

/**
 * Get number of channels for a format.
 */
inline int channels(PixelFormat format) {
    switch (format) {
        case PixelFormat::GRAY8:
        case PixelFormat::FLOAT32_GRAY: return 1;
        case PixelFormat::RGB888:
        case PixelFormat::BGR888:
        case PixelFormat::FLOAT32_RGB:
        case PixelFormat::NV21:
        case PixelFormat::NV12:
        case PixelFormat::YUV420P:
        case PixelFormat::YUV422: return 3;
        case PixelFormat::RGBA8888:
        case PixelFormat::BGRA8888: return 4;
        default: return 0;
    }
}

/**
 * Non-owning view of image data.
 * Safe for passing across function boundaries without copying.
 */
struct ImageView {
    const uint8_t* data = nullptr;
    int width = 0;
    int height = 0;
    int stride = 0;  // Bytes per row (may include padding)
    PixelFormat format = PixelFormat::UNKNOWN;
    
    // For planar formats (YUV)
    const uint8_t* planeU = nullptr;
    const uint8_t* planeV = nullptr;
    int strideU = 0;
    int strideV = 0;
    
    bool isValid() const {
        return data != nullptr && width > 0 && height > 0 && format != PixelFormat::UNKNOWN;
    }
    
    size_t totalBytes() const {
        if (!isValid()) return 0;
        int bpp = bytesPerPixel(format);
        if (bpp > 0) {
            return static_cast<size_t>(stride > 0 ? stride : width * bpp) * height;
        }
        // Planar format estimation: Y + interlaced UV, each row carrying the
        // real stride when the caller provided one (padded camera frames).
        const size_t rowBytes = stride > 0 ? static_cast<size_t>(stride)
                                           : static_cast<size_t>(width);
        return rowBytes * height * 3 / 2;
    }
};

/**
 * Owning image frame with automatic memory management.
 * Use this when you need to store/return image data.
 */
class ImageFrame {
public:
    ImageFrame() = default;
    
    ImageFrame(int width, int height, PixelFormat format)
        : width_(width), height_(height), format_(format) {
        allocate();
    }
    
    // Move semantics
    ImageFrame(ImageFrame&& other) noexcept
        : data_(std::move(other.data_))
        , width_(other.width_)
        , height_(other.height_)
        , stride_(other.stride_)
        , format_(other.format_) {
        other.width_ = other.height_ = other.stride_ = 0;
        other.format_ = PixelFormat::UNKNOWN;
    }
    
    ImageFrame& operator=(ImageFrame&& other) noexcept {
        if (this != &other) {
            data_ = std::move(other.data_);
            width_ = other.width_;
            height_ = other.height_;
            stride_ = other.stride_;
            format_ = other.format_;
            other.width_ = other.height_ = other.stride_ = 0;
            other.format_ = PixelFormat::UNKNOWN;
        }
        return *this;
    }
    
    // No copy - use clone() explicitly
    ImageFrame(const ImageFrame&) = delete;
    ImageFrame& operator=(const ImageFrame&) = delete;
    
    // Create a deep copy
    ImageFrame clone() const {
        ImageFrame copy(width_, height_, format_);
        if (!data_.empty() && !copy.data_.empty()) {
            std::copy(data_.begin(), data_.end(), copy.data_.begin());
        }
        return copy;
    }
    
    // Accessors
    uint8_t* data() { return data_.data(); }
    const uint8_t* data() const { return data_.data(); }
    int width() const { return width_; }
    int height() const { return height_; }
    int stride() const { return stride_; }
    PixelFormat format() const { return format_; }
    bool isValid() const { return !data_.empty() && width_ > 0 && height_ > 0; }
    
    // Get non-owning view
    ImageView view() const {
        ImageView v;
        v.data = data_.data();
        v.width = width_;
        v.height = height_;
        v.stride = stride_;
        v.format = format_;
        return v;
    }
    
    // Mutable view for writing
    ImageView mutableView() {
        ImageView v;
        v.data = data_.data();
        v.width = width_;
        v.height = height_;
        v.stride = stride_;
        v.format = format_;
        return v;
    }

private:
    void allocate() {
        int bpp = bytesPerPixel(format_);
        if (bpp > 0) {
            stride_ = width_ * bpp;
            // Align stride to 16 bytes for SIMD
            stride_ = (stride_ + 15) & ~15;
            data_.resize(static_cast<size_t>(stride_) * height_);
        } else {
            // Planar YUV420
            stride_ = width_;
            size_t ySize = static_cast<size_t>(width_) * height_;
            size_t uvSize = ySize / 2;  // Combined U+V for NV21/NV12
            data_.resize(ySize + uvSize);
        }
    }
    
    std::vector<uint8_t> data_;
    int width_ = 0;
    int height_ = 0;
    int stride_ = 0;
    PixelFormat format_ = PixelFormat::UNKNOWN;
};

/**
 * Rectangle for ROI (Region of Interest).
 */
struct Rect {
    int x = 0;
    int y = 0;
    int width = 0;
    int height = 0;
    
    bool isValid() const { return width > 0 && height > 0; }
    int area() const { return width * height; }
    int right() const { return x + width; }
    int bottom() const { return y + height; }
    
    // Check if this rect is within bounds
    bool fitsIn(int imgWidth, int imgHeight) const {
        return x >= 0 && y >= 0 && right() <= imgWidth && bottom() <= imgHeight;
    }
    
    // Clamp to image bounds
    Rect clampTo(int imgWidth, int imgHeight) const {
        Rect r;
        r.x = std::max(0, std::min(x, imgWidth - 1));
        r.y = std::max(0, std::min(y, imgHeight - 1));
        r.width = std::max(0, std::min(width, imgWidth - r.x));
        r.height = std::max(0, std::min(height, imgHeight - r.y));
        return r;
    }
};

/**
 * Point structure.
 */
struct Point {
    int x = 0;
    int y = 0;
};

struct PointF {
    float x = 0.0f;
    float y = 0.0f;
};

/**
 * Size structure.
 */
struct Size {
    int width = 0;
    int height = 0;
    
    bool isValid() const { return width > 0 && height > 0; }
    int area() const { return width * height; }
};

/**
 * Detection result from object detection / face detection.
 */
struct DetectionResult {
    Rect boundingBox;
    float confidence = 0.0f;
    int classId = -1;
    std::string label;
    
    // Optional: landmarks (for face detection)
    std::vector<PointF> landmarks;
};

/**
 * Image processing result.
 */
struct ProcessingResult {
    bool success = false;
    std::string error;
    int64_t processingTimeMs = 0;
    
    // Detection results (if applicable)
    std::vector<DetectionResult> detections;
    
    // Output image (if applicable)
    ImageFrame outputImage;
    
    // Extracted features (for ML)
    std::vector<float> features;
};

} // namespace vision

#endif // IMAGE_TYPES_H

