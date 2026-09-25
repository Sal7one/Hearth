// =============================================================================
// Sign-language landmark classifier — plain-MLP ONNX runtime over the same
// vendored ONNX Runtime the Marian/OCR engines use. Portable C++: no Android.
//
// Frozen deployment contract (scripts/sign/hearth_ml.py is the source of
// truth; scripts/sign/verify_onnx.py gates every artifact against it):
//   input  "landmarks" [batch, 63] float32 (21 MediaPipe landmarks, x/y/z,
//          wrist-origin + palm-scale normalized)
//   output "logits"    [batch, N] float32 raw logits (softmax applied here)
//   opset <= 13, rank-2 tensors, <= 128 classes, <= 2 MB
// =============================================================================

#ifndef STT_SIGN_CLASSIFIER_H
#define STT_SIGN_CLASSIFIER_H

#include <cstddef>
#include <string>
#include <vector>

namespace stt::sign {

struct SignClassification {
    int classIndex = -1;
    float confidence = 0.f;       // softmax probability of the argmax class
    std::vector<float> probs;     // full softmax distribution
};

class SignClassifier {
public:
    ~SignClassifier();

    bool load(const std::string& modelPath, int numThreads);
    void release();

    bool isLoaded() const { return session_ != nullptr; }
    int inputSize() const { return inputSize_; }
    int classCount() const { return classCount_; }

    // [features, featureCount] must match inputSize(). Returns an empty
    // result (classIndex < 0) on failure; lastError() says why.
    SignClassification classify(const float* features, int featureCount);

    const std::string& lastError() const { return lastError_; }

private:
    void* env_ = nullptr;       // OrtEnv*
    void* options_ = nullptr;   // OrtSessionOptions*
    void* session_ = nullptr;   // OrtSession*
    int inputSize_ = 0;
    int classCount_ = 0;
    std::string lastError_;
};

}  // namespace stt::sign

#endif  // STT_SIGN_CLASSIFIER_H
