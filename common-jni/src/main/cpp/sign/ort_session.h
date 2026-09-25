// =============================================================================
// Minimal ONNX Runtime session wrapper for the sign pipeline's vision models
// (palm detector + hand landmarks). Runs on the same vendored ORT the
// Marian/OCR/TTS engines use — the sign pipeline deliberately ships no
// Google-specific runtime. Portable C++: no Android, no JNI.
//
// Contract per model: exactly one float32 input tensor with a static shape
// (batch 1), any number of float32 outputs. invoke() copies every output
// into freshly sized float vectors, preserving the model's output order.
// =============================================================================

#ifndef STT_SIGN_ORT_SESSION_H
#define STT_SIGN_ORT_SESSION_H

#include <cstddef>
#include <string>
#include <vector>

namespace stt::sign {

class OrtSession {
public:
    ~OrtSession();
    OrtSession() = default;
    OrtSession(const OrtSession&) = delete;
    OrtSession& operator=(const OrtSession&) = delete;

    bool load(const std::string& modelPath, int numThreads);
    void release();
    bool isLoaded() const { return session_ != nullptr; }

    // [inputFloats] must equal the model's input element count. On success
    // [outputs] holds every output tensor as floats, in model output order.
    bool invoke(const float* input, size_t inputFloats,
                std::vector<std::vector<float>>& outputs);

    const std::string& lastError() const { return lastError_; }

private:
    void* options_ = nullptr;  // OrtSessionOptions*
    void* session_ = nullptr;  // OrtSession*
    std::string inputName_;
    std::vector<int64_t> inputShape_;
    std::vector<std::string> outputNames_;
    std::string lastError_;
};

}  // namespace stt::sign

#endif  // STT_SIGN_ORT_SESSION_H
