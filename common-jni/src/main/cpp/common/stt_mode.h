#ifndef STT_MODE_H
#define STT_MODE_H

#include <string>

namespace stt {

/**
 * STT processing mode - controls quality/speed tradeoff.
 */
enum class SttMode {
    FAST,       // Use lightweight engine (Vosk/small ONNX), lower accuracy
    BALANCED,   // Use medium model, good balance
    ACCURATE    // Use Whisper/large model, highest accuracy
};

/**
 * Quality level for generic processing (reusable across ML tasks).
 */
enum class Quality {
    LOW = 0,
    MEDIUM = 1,
    HIGH = 2
};

/**
 * STT job descriptor for the engine router.
 */
struct SttJob {
    SttMode mode = SttMode::BALANCED;
    const void* audioData = nullptr;
    size_t audioSamples = 0;
    int sampleRate = 16000;
    const char* language = "auto";
    
    // Output
    std::string* resultText = nullptr;
    float* confidence = nullptr;
};

/**
 * Policy for selecting STT engine based on mode and device capabilities.
 */
class SttModePolicy {
public:
    /**
     * Get recommended engine type for the given mode.
     * Returns: 1=Whisper, 2=Vosk, 4=ONNX
     */
    static int getRecommendedEngine(SttMode mode, int availableEngines) {
        switch (mode) {
            case SttMode::FAST:
                // Prefer Vosk > ONNX > Whisper for speed
                if (availableEngines & 2) return 2;  // Vosk
                if (availableEngines & 4) return 4;  // ONNX
                if (availableEngines & 1) return 1;  // Whisper (fallback)
                break;
                
            case SttMode::BALANCED:
                // Prefer ONNX > Vosk > Whisper for balance
                if (availableEngines & 4) return 4;  // ONNX
                if (availableEngines & 2) return 2;  // Vosk
                if (availableEngines & 1) return 1;  // Whisper
                break;
                
            case SttMode::ACCURATE:
                // Prefer Whisper > ONNX > Vosk for accuracy
                if (availableEngines & 1) return 1;  // Whisper
                if (availableEngines & 4) return 4;  // ONNX
                if (availableEngines & 2) return 2;  // Vosk
                break;
        }
        return 0;  // No engine available
    }
    
    /**
     * Get recommended thread count based on mode.
     */
    static int getRecommendedThreads(SttMode mode) {
        switch (mode) {
            case SttMode::FAST:     return 2;
            case SttMode::BALANCED: return 4;
            case SttMode::ACCURATE: return 6;
        }
        return 4;
    }
    
    /**
     * Get recommended chunk size in samples based on mode.
     */
    static int getRecommendedChunkSize(SttMode mode, int sampleRate = 16000) {
        switch (mode) {
            case SttMode::FAST:     return sampleRate * 5;   // 5 seconds
            case SttMode::BALANCED: return sampleRate * 15;  // 15 seconds
            case SttMode::ACCURATE: return sampleRate * 30;  // 30 seconds
        }
        return sampleRate * 15;
    }
    
    /**
     * Convert mode to string for logging.
     */
    static const char* modeToString(SttMode mode) {
        switch (mode) {
            case SttMode::FAST:     return "Fast";
            case SttMode::BALANCED: return "Balanced";
            case SttMode::ACCURATE: return "Accurate";
        }
        return "Unknown";
    }
};

} // namespace stt

#endif // STT_MODE_H

