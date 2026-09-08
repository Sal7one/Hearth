#ifndef IMAGE_ENGINE_H
#define IMAGE_ENGINE_H

#include "image_types.h"
#include <string>
#include <vector>
#include <memory>

namespace vision {

/**
 * Configuration for image processing operations.
 */
struct ImageProcessingConfig {
    // Resize settings
    int targetWidth = 0;
    int targetHeight = 0;
    bool preserveAspectRatio = true;
    
    // Normalization for ML
    float mean[3] = {0.0f, 0.0f, 0.0f};
    float std[3] = {1.0f, 1.0f, 1.0f};
    bool normalizeToFloat = false;
    
    // Color conversion
    PixelFormat targetFormat = PixelFormat::UNKNOWN;  // UNKNOWN = keep original
    
    // ROI
    Rect roi;  // Empty = full image
    
    // Rotation (0, 90, 180, 270)
    int rotation = 0;
    
    // Flip
    bool flipHorizontal = false;
    bool flipVertical = false;
    
    // Detection settings
    float confidenceThreshold = 0.5f;
    float nmsThreshold = 0.4f;  // Non-maximum suppression
    int maxDetections = 100;
    
    // Threading
    int numThreads = 4;
};

/**
 * Capability flags for image engines.
 */
enum class ImageEngineCapability : int {
    NONE = 0,
    COLOR_CONVERSION = 1 << 0,
    RESIZE = 1 << 1,
    CROP = 1 << 2,
    ROTATE = 1 << 3,
    FLIP = 1 << 4,
    FACE_DETECTION = 1 << 5,
    OBJECT_DETECTION = 1 << 6,
    IMAGE_CLASSIFICATION = 1 << 7,
    FEATURE_EXTRACTION = 1 << 8,
    TEXT_DETECTION = 1 << 9,
    BARCODE_DETECTION = 1 << 10,
    EDGE_DETECTION = 1 << 11,
    BLUR = 1 << 12,
    THRESHOLD = 1 << 13,
};

inline ImageEngineCapability operator|(ImageEngineCapability a, ImageEngineCapability b) {
    return static_cast<ImageEngineCapability>(static_cast<int>(a) | static_cast<int>(b));
}

inline bool hasCapability(ImageEngineCapability caps, ImageEngineCapability cap) {
    return (static_cast<int>(caps) & static_cast<int>(cap)) != 0;
}

/**
 * Abstract interface for image processing engines.
 * Implementations: OpenCVImageEngine, (future: TFLiteImageEngine, etc.)
 */
class IImageEngine {
public:
    virtual ~IImageEngine() = default;
    
    // =========================================================================
    // Lifecycle
    // =========================================================================
    
    /**
     * Initialize the engine.
     * @param config Optional configuration JSON
     * @return true on success
     */
    virtual bool initialize(const std::string& configJson = "") = 0;
    
    /**
     * Release resources.
     */
    virtual void release() = 0;
    
    /**
     * Check if initialized.
     */
    virtual bool isInitialized() const = 0;
    
    // =========================================================================
    // Basic Image Processing
    // =========================================================================
    
    /**
     * Process image with given configuration.
     * This is the main entry point for image processing.
     */
    virtual ProcessingResult process(const ImageView& input, const ImageProcessingConfig& config) = 0;
    
    /**
     * Convert image to different pixel format.
     */
    virtual bool convert(const ImageView& input, ImageFrame& output, PixelFormat targetFormat) = 0;
    
    /**
     * Resize image.
     */
    virtual bool resize(const ImageView& input, ImageFrame& output, int width, int height) = 0;
    
    /**
     * Crop image to ROI.
     */
    virtual bool crop(const ImageView& input, ImageFrame& output, const Rect& roi) = 0;
    
    /**
     * Rotate image.
     */
    virtual bool rotate(const ImageView& input, ImageFrame& output, int angleDegrees) = 0;
    
    // =========================================================================
    // Detection & Analysis
    // =========================================================================
    
    /**
     * Detect faces in image.
     * @return Vector of detection results with face bounding boxes and landmarks.
     */
    virtual std::vector<DetectionResult> detectFaces(const ImageView& input, float confidenceThreshold = 0.5f) = 0;
    
    /**
     * Detect objects using a loaded model.
     */
    virtual std::vector<DetectionResult> detectObjects(const ImageView& input, float confidenceThreshold = 0.5f) = 0;
    
    /**
     * Extract feature vector from image (for ML models).
     */
    virtual std::vector<float> extractFeatures(const ImageView& input) = 0;
    
    // =========================================================================
    // Model Loading
    // =========================================================================
    
    /**
     * Load a detection/classification model.
     * @param modelPath Path to model file (e.g., .xml for OpenCV DNN, .tflite, etc.)
     * @param configPath Optional config file path
     */
    virtual bool loadModel(const std::string& modelPath, const std::string& configPath = "") = 0;
    
    /**
     * Check if a model is loaded.
     */
    virtual bool hasModel() const = 0;
    
    // =========================================================================
    // Info & Capabilities
    // =========================================================================
    
    /**
     * Get engine capabilities.
     */
    virtual ImageEngineCapability getCapabilities() const = 0;
    
    /**
     * Get engine version string.
     */
    virtual std::string getVersion() const = 0;
    
    /**
     * Get last error message.
     */
    virtual std::string getLastError() const = 0;
    
    /**
     * Check if engine is available (library loaded).
     */
    static bool isAvailable();
};

/**
 * Factory function to create image engine.
 */
std::unique_ptr<IImageEngine> createImageEngine();

} // namespace vision

#endif // IMAGE_ENGINE_H

