#ifndef STT_VAD_H
#define STT_VAD_H

#include <vector>
#include <cstdint>
#include <functional>
#include <atomic>
#include <string>

namespace stt {

struct VadSegment {
    int64_t startMs;
    int64_t endMs;
    float confidence;
    bool isSpeech;
};

using VadCallback = std::function<void(const VadSegment&)>;

/**
 * Voice Activity Detection using energy-based algorithm.
 * Lightweight, no external dependencies.
 */
class VadDetector {
public:
    struct Config {
        float speechThresholdDb = -30.0f;    // Energy threshold for speech
        float silenceThresholdDb = -45.0f;   // Energy threshold for silence
        int minSpeechMs = 250;               // Min speech duration
        int minSilenceMs = 300;              // Min silence to end segment
        int windowMs = 30;                   // Analysis window size
        int sampleRate = 16000;
        
        Config() = default;
    };
    
    VadDetector();
    explicit VadDetector(const Config& config);
    ~VadDetector();

    /** Validate every constructor invariant before allocating or processing. */
    static bool validateConfig(const Config& config, std::string* error = nullptr);
    
    // Process audio samples
    // Returns detected segments
    std::vector<VadSegment> process(const int16_t* samples, int count);
    std::vector<VadSegment> process(const float* samples, int count);
    
    // Streaming mode
    void pushAudio(const int16_t* samples, int count);
    void pushAudio(const float* samples, int count);
    
    // Get segments detected so far
    std::vector<VadSegment> getSegments();
    
    // Finalize and get remaining segment
    VadSegment finalize();
    
    // Reset state
    void reset();
    
    // Real-time callback
    void setCallback(VadCallback cb) { callback_ = std::move(cb); }
    
    // Check if currently in speech
    bool isSpeaking() const { return inSpeech_; }
    
    // Get current energy level (dB)
    float getCurrentEnergyDb() const { return currentEnergyDb_; }

private:
    Config config_;
    VadCallback callback_;
    
    // State
    std::atomic<bool> inSpeech_{false};
    int64_t speechStartMs_{0};
    int64_t silenceStartMs_{0};
    int64_t totalSamplesProcessed_{0};
    float currentEnergyDb_{-100.0f};
    
    // Accumulated segments
    std::vector<VadSegment> segments_;
    
    // Ring buffer for smoothing
    std::vector<float> energyHistory_;
    size_t energyHistoryPos_{0};
    float energySum_{0.0f};          // Incremental running sum of energyHistory_
    bool historyPrimed_{false};      // Becomes true once the ring wraps
    int windowSamples_{0};           // Validated non-zero processing step

    float calculateEnergy(const float* samples, int count);
    float calculateEnergyI16(const int16_t* samples, int count);
    float dbFromEnergy(float energy);
    void processWindow(float energyDb, int64_t windowStartMs);
    void processWindowI16(const int16_t* s, int n);
    void processWindowF32(const float* s, int n);
};

} // namespace stt

#endif // STT_VAD_H
