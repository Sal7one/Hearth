#ifndef IMAGE_UTILS_H
#define IMAGE_UTILS_H

#include "image_types.h"
#include "logging.h"
#include <cstring>
#include <algorithm>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define USE_NEON_IMAGE 1
#else
#define USE_NEON_IMAGE 0
#endif

namespace vision {

/**
 * Image utility functions - pure C++ implementations.
 * These work without OpenCV for basic operations.
 * OpenCV-specific conversions are in opencv_utils.h
 */
class ImageUtils {
public:
    /**
     * Convert NV21 (Android camera) to RGB888.
     * NV21: Y plane followed by interleaved VU plane.
     */
    static bool nv21ToRgb(const ImageView& src, ImageFrame& dst) {
        if (src.format != PixelFormat::NV21 || !src.isValid()) {
            return false;
        }
        
        dst = ImageFrame(src.width, src.height, PixelFormat::RGB888);
        
        const uint8_t* yPlane = src.data;
        const uint8_t* vuPlane = src.planeV ? src.planeV : (src.data + src.width * src.height);
        
        uint8_t* rgb = dst.data();
        int dstStride = dst.stride();
        
        for (int y = 0; y < src.height; y++) {
            const uint8_t* yRow = yPlane + y * src.width;
            const uint8_t* vuRow = vuPlane + (y / 2) * src.width;
            uint8_t* rgbRow = rgb + y * dstStride;
            
            for (int x = 0; x < src.width; x++) {
                int Y = yRow[x];
                int V = vuRow[(x / 2) * 2] - 128;
                int U = vuRow[(x / 2) * 2 + 1] - 128;
                
                // YUV to RGB conversion
                int R = Y + (1.370705f * V);
                int G = Y - (0.337633f * U) - (0.698001f * V);
                int B = Y + (1.732446f * U);
                
                rgbRow[x * 3 + 0] = static_cast<uint8_t>(std::clamp(R, 0, 255));
                rgbRow[x * 3 + 1] = static_cast<uint8_t>(std::clamp(G, 0, 255));
                rgbRow[x * 3 + 2] = static_cast<uint8_t>(std::clamp(B, 0, 255));
            }
        }
        
        return true;
    }
    
    /**
     * Convert RGB888 to BGR888 (OpenCV format).
     */
    static bool rgbToBgr(const ImageView& src, ImageFrame& dst) {
        if (src.format != PixelFormat::RGB888 || !src.isValid()) {
            return false;
        }
        
        dst = ImageFrame(src.width, src.height, PixelFormat::BGR888);
        
        const uint8_t* srcData = src.data;
        uint8_t* dstData = dst.data();
        int srcStride = src.stride > 0 ? src.stride : src.width * 3;
        int dstStride = dst.stride();
        
#if USE_NEON_IMAGE
        // NEON optimized RGB to BGR swap
        for (int y = 0; y < src.height; y++) {
            const uint8_t* srcRow = srcData + y * srcStride;
            uint8_t* dstRow = dstData + y * dstStride;
            
            int x = 0;
            // Process 16 pixels at a time
            for (; x + 16 <= src.width; x += 16) {
                uint8x16x3_t rgb = vld3q_u8(srcRow + x * 3);
                uint8x16x3_t bgr;
                bgr.val[0] = rgb.val[2];  // B = R
                bgr.val[1] = rgb.val[1];  // G = G
                bgr.val[2] = rgb.val[0];  // R = B
                vst3q_u8(dstRow + x * 3, bgr);
            }
            
            // Handle remaining pixels
            for (; x < src.width; x++) {
                dstRow[x * 3 + 0] = srcRow[x * 3 + 2];  // B
                dstRow[x * 3 + 1] = srcRow[x * 3 + 1];  // G
                dstRow[x * 3 + 2] = srcRow[x * 3 + 0];  // R
            }
        }
#else
        // Scalar fallback
        for (int y = 0; y < src.height; y++) {
            const uint8_t* srcRow = srcData + y * srcStride;
            uint8_t* dstRow = dstData + y * dstStride;
            
            for (int x = 0; x < src.width; x++) {
                dstRow[x * 3 + 0] = srcRow[x * 3 + 2];  // B
                dstRow[x * 3 + 1] = srcRow[x * 3 + 1];  // G
                dstRow[x * 3 + 2] = srcRow[x * 3 + 0];  // R
            }
        }
#endif
        
        return true;
    }
    
    /**
     * Convert RGB888 to Grayscale.
     * Uses standard luminance formula: Y = 0.299*R + 0.587*G + 0.114*B
     */
    static bool rgbToGray(const ImageView& src, ImageFrame& dst) {
        if (src.format != PixelFormat::RGB888 || !src.isValid()) {
            return false;
        }
        
        dst = ImageFrame(src.width, src.height, PixelFormat::GRAY8);
        
        const uint8_t* srcData = src.data;
        uint8_t* dstData = dst.data();
        int srcStride = src.stride > 0 ? src.stride : src.width * 3;
        int dstStride = dst.stride();
        
        // Fixed-point coefficients (scaled by 256)
        constexpr int rCoef = 77;   // 0.299 * 256
        constexpr int gCoef = 150;  // 0.587 * 256
        constexpr int bCoef = 29;   // 0.114 * 256
        
        for (int y = 0; y < src.height; y++) {
            const uint8_t* srcRow = srcData + y * srcStride;
            uint8_t* dstRow = dstData + y * dstStride;
            
            for (int x = 0; x < src.width; x++) {
                int r = srcRow[x * 3 + 0];
                int g = srcRow[x * 3 + 1];
                int b = srcRow[x * 3 + 2];
                dstRow[x] = static_cast<uint8_t>((r * rCoef + g * gCoef + b * bCoef) >> 8);
            }
        }
        
        return true;
    }
    
    /**
     * Resize image using bilinear interpolation.
     */
    static bool resize(const ImageView& src, ImageFrame& dst, int newWidth, int newHeight) {
        if (!src.isValid() || newWidth <= 0 || newHeight <= 0) {
            return false;
        }
        
        dst = ImageFrame(newWidth, newHeight, src.format);
        
        int bpp = bytesPerPixel(src.format);
        if (bpp == 0) {
            LOG_E("ImageUtils", "Resize not supported for planar formats");
            return false;
        }
        
        int srcStride = src.stride > 0 ? src.stride : src.width * bpp;
        int dstStride = dst.stride();
        
        float xScale = static_cast<float>(src.width) / newWidth;
        float yScale = static_cast<float>(src.height) / newHeight;
        
        for (int y = 0; y < newHeight; y++) {
            float srcY = y * yScale;
            int y0 = static_cast<int>(srcY);
            int y1 = std::min(y0 + 1, src.height - 1);
            float yFrac = srcY - y0;
            
            uint8_t* dstRow = dst.data() + y * dstStride;
            
            for (int x = 0; x < newWidth; x++) {
                float srcX = x * xScale;
                int x0 = static_cast<int>(srcX);
                int x1 = std::min(x0 + 1, src.width - 1);
                float xFrac = srcX - x0;
                
                for (int c = 0; c < bpp; c++) {
                    // Bilinear interpolation
                    float v00 = src.data[y0 * srcStride + x0 * bpp + c];
                    float v01 = src.data[y0 * srcStride + x1 * bpp + c];
                    float v10 = src.data[y1 * srcStride + x0 * bpp + c];
                    float v11 = src.data[y1 * srcStride + x1 * bpp + c];
                    
                    float v0 = v00 + (v01 - v00) * xFrac;
                    float v1 = v10 + (v11 - v10) * xFrac;
                    float v = v0 + (v1 - v0) * yFrac;
                    
                    dstRow[x * bpp + c] = static_cast<uint8_t>(std::clamp(v, 0.0f, 255.0f));
                }
            }
        }
        
        return true;
    }
    
    /**
     * Crop image to ROI.
     */
    static bool crop(const ImageView& src, ImageFrame& dst, const Rect& roi) {
        if (!src.isValid() || !roi.isValid()) {
            return false;
        }
        
        Rect clampedRoi = roi.clampTo(src.width, src.height);
        if (!clampedRoi.isValid()) {
            return false;
        }
        
        int bpp = bytesPerPixel(src.format);
        if (bpp == 0) {
            LOG_E("ImageUtils", "Crop not supported for planar formats");
            return false;
        }
        
        dst = ImageFrame(clampedRoi.width, clampedRoi.height, src.format);
        
        int srcStride = src.stride > 0 ? src.stride : src.width * bpp;
        int dstStride = dst.stride();
        
        for (int y = 0; y < clampedRoi.height; y++) {
            const uint8_t* srcRow = src.data + (clampedRoi.y + y) * srcStride + clampedRoi.x * bpp;
            uint8_t* dstRow = dst.data() + y * dstStride;
            std::memcpy(dstRow, srcRow, clampedRoi.width * bpp);
        }
        
        return true;
    }
    
    /**
     * Rotate image by 90, 180, or 270 degrees.
     */
    static bool rotate(const ImageView& src, ImageFrame& dst, int angleDegrees) {
        if (!src.isValid()) {
            return false;
        }
        
        int bpp = bytesPerPixel(src.format);
        if (bpp == 0) {
            LOG_E("ImageUtils", "Rotate not supported for planar formats");
            return false;
        }
        
        int srcStride = src.stride > 0 ? src.stride : src.width * bpp;
        
        // Normalize angle
        angleDegrees = ((angleDegrees % 360) + 360) % 360;
        
        int dstWidth, dstHeight;
        if (angleDegrees == 90 || angleDegrees == 270) {
            dstWidth = src.height;
            dstHeight = src.width;
        } else {
            dstWidth = src.width;
            dstHeight = src.height;
        }
        
        dst = ImageFrame(dstWidth, dstHeight, src.format);
        int dstStride = dst.stride();
        
        for (int y = 0; y < src.height; y++) {
            for (int x = 0; x < src.width; x++) {
                int dstX, dstY;
                
                switch (angleDegrees) {
                    case 90:
                        dstX = src.height - 1 - y;
                        dstY = x;
                        break;
                    case 180:
                        dstX = src.width - 1 - x;
                        dstY = src.height - 1 - y;
                        break;
                    case 270:
                        dstX = y;
                        dstY = src.width - 1 - x;
                        break;
                    default:  // 0 degrees
                        dstX = x;
                        dstY = y;
                        break;
                }
                
                const uint8_t* srcPixel = src.data + y * srcStride + x * bpp;
                uint8_t* dstPixel = dst.data() + dstY * dstStride + dstX * bpp;
                std::memcpy(dstPixel, srcPixel, bpp);
            }
        }
        
        return true;
    }
    
    /**
     * Flip image horizontally or vertically.
     */
    static bool flip(const ImageView& src, ImageFrame& dst, bool horizontal, bool vertical) {
        if (!src.isValid()) {
            return false;
        }
        
        int bpp = bytesPerPixel(src.format);
        if (bpp == 0) {
            LOG_E("ImageUtils", "Flip not supported for planar formats");
            return false;
        }
        
        dst = ImageFrame(src.width, src.height, src.format);
        
        int srcStride = src.stride > 0 ? src.stride : src.width * bpp;
        int dstStride = dst.stride();
        
        for (int y = 0; y < src.height; y++) {
            int srcY = vertical ? (src.height - 1 - y) : y;
            const uint8_t* srcRow = src.data + srcY * srcStride;
            uint8_t* dstRow = dst.data() + y * dstStride;
            
            if (horizontal) {
                for (int x = 0; x < src.width; x++) {
                    int srcX = src.width - 1 - x;
                    std::memcpy(dstRow + x * bpp, srcRow + srcX * bpp, bpp);
                }
            } else {
                std::memcpy(dstRow, srcRow, src.width * bpp);
            }
        }
        
        return true;
    }
    
    /**
     * Normalize pixel values to [0, 1] range for ML models.
     */
    static bool normalizeToFloat(const ImageView& src, std::vector<float>& dst,
                                  float mean = 0.0f, float std = 1.0f) {
        if (!src.isValid()) {
            return false;
        }
        
        int bpp = bytesPerPixel(src.format);
        if (bpp == 0) {
            return false;
        }
        
        int srcStride = src.stride > 0 ? src.stride : src.width * bpp;
        size_t totalPixels = static_cast<size_t>(src.width) * src.height * bpp;
        dst.resize(totalPixels);
        
        float scale = 1.0f / (255.0f * std);
        float offset = -mean / std;
        
        size_t idx = 0;
        for (int y = 0; y < src.height; y++) {
            const uint8_t* srcRow = src.data + y * srcStride;
            for (int x = 0; x < src.width * bpp; x++) {
                dst[idx++] = srcRow[x] * scale + offset;
            }
        }
        
        return true;
    }
};

} // namespace vision

#endif // IMAGE_UTILS_H

