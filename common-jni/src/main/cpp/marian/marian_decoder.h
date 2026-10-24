#ifndef HEARTH_MARIAN_DECODER_H
#define HEARTH_MARIAN_DECODER_H

#include "../onnx/include/onnxruntime/onnxruntime_c_api.h"
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <string>
#include <utility>
#include <vector>

namespace stt::marian::detail {

// Own the OrtValues, rather than copying their tensor storage into a second
// cache. ORT permits an output value to be passed directly to the next Run.
// All slots, including partially populated outputs from a failed Run, are
// released exactly once on every exit path.
template <size_t Count>
class OrtValues {
    const OrtApi* api_;
    std::array<OrtValue*, Count> values_{};
public:
    explicit OrtValues(const OrtApi* api) : api_(api) {}
    ~OrtValues() { for (auto* value : values_) if (value) api_->ReleaseValue(value); }
    OrtValues(const OrtValues&) = delete;
    OrtValues& operator=(const OrtValues&) = delete;
    OrtValue* operator[](size_t index) const { return values_[index]; }
    OrtValue** slot(size_t index) { return &values_[index]; }
    OrtValue* take(size_t index) { return std::exchange(values_[index], nullptr); }
    void reset(size_t index, OrtValue* value) {
        if (values_[index]) api_->ReleaseValue(values_[index]);
        values_[index] = value;
    }
};

// Contract of the pinned Optimum Marian merged decoder. Encoder presents from
// the initial call are cross-attention projections: keep them unchanged. On
// cached calls their output slots are empty dummies and must not replace them.
constexpr int kDecoderLayers = 6;
constexpr int kDecoderHeads = 8;
constexpr int kDecoderHeadDim = 64;
constexpr int kDecoderHiddenDim = 512;
constexpr size_t kDecoderPastCount = 24;
constexpr size_t kDecoderInputs = 4 + kDecoderPastCount;
constexpr size_t kDecoderOutputs = 1 + kDecoderPastCount;

inline size_t decoderPastIndex(int layer, int side, int kv) {
    return static_cast<size_t>(layer * 4 + side * 2 + kv);
}

// All invariant tensor views and the fixed-size logits output are created
// once per caption. Only the growing decoder cache changes between tokens.
// No wrapper-side KV or logits copies occur in the autoregressive hot loop.
template <class Interrupted>
bool runGreedyDecoder(const OrtApi* api, OrtSession* session,
                      const OrtRunOptions* runOptions, OrtMemoryInfo* memoryInfo,
                      OrtAllocator* allocator, const char* const* inputNames,
                      const char* const* outputNames, const float* hidden,
                      const int64_t* attention, size_t encLen, size_t vocabSize,
                      int64_t padId, int64_t eosId, int maxTokens,
                      std::vector<int64_t>& decodedIds, std::string* error,
                      Interrupted interrupted, bool* emptyOutput = nullptr) {
    if (emptyOutput) *emptyOutput = false;
    auto fail = [error](const std::string& message) {
        if (error) *error = message;
        return false;
    };
    auto checked = [api, &fail](OrtStatus* status, const char* operation) {
        if (!status) return true;
        const char* cause = api->GetErrorMessage(status);
        const std::string message = std::string(operation) + ": " +
            (cause ? cause : "unknown ORT error");
        api->ReleaseStatus(status);
        return fail(message);
    };
    if (!encLen || !vocabSize || maxTokens < 1 || padId < 0 || eosId < 0 ||
        static_cast<size_t>(padId) >= vocabSize ||
        static_cast<size_t>(eosId) >= vocabSize) return fail("Invalid Marian decoder dimensions or token IDs");

    // Values borrow these scalar/buffer addresses for the duration of the loop.
    int64_t token = padId;
    uint8_t cacheBranch = 0;
    const int64_t tokenShape[2] = {1, 1};
    const int64_t maskShape[2] = {1, static_cast<int64_t>(encLen)};
    const int64_t hiddenShape[3] = {1, static_cast<int64_t>(encLen), kDecoderHiddenDim};
    const int64_t branchShape[1] = {1};
    const int64_t emptyShape[4] = {1, kDecoderHeads, 0, kDecoderHeadDim};
    const int64_t logitsShape[3] = {1, 1, static_cast<int64_t>(vocabSize)};
    OrtValues<6> fixed(api);
    if (!checked(api->CreateTensorWithDataAsOrtValue(memoryInfo, &token, sizeof(token),
            tokenShape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, fixed.slot(0)), "decoder input_ids tensor")) return false;
    if (!checked(api->CreateTensorWithDataAsOrtValue(memoryInfo, const_cast<int64_t*>(attention),
            sizeof(int64_t) * encLen, maskShape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,
            fixed.slot(1)), "decoder mask tensor")) return false;
    if (!checked(api->CreateTensorWithDataAsOrtValue(memoryInfo, const_cast<float*>(hidden),
            sizeof(float) * encLen * kDecoderHiddenDim, hiddenShape, 3,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, fixed.slot(2)), "decoder hidden tensor")) return false;
    if (!checked(api->CreateTensorWithDataAsOrtValue(memoryInfo, &cacheBranch, sizeof(cacheBranch),
            branchShape, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL, fixed.slot(3)), "decoder cache branch tensor")) return false;
    if (!checked(api->CreateTensorWithDataAsOrtValue(memoryInfo, nullptr, 0, emptyShape, 4,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, fixed.slot(4)), "decoder empty past tensor")) return false;
    if (!checked(api->CreateTensorAsOrtValue(allocator, logitsShape, 3,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, fixed.slot(5)), "decoder logits tensor")) return false;
    float* logits = nullptr;
    if (!checked(api->GetTensorMutableData(fixed[5], reinterpret_cast<void**>(&logits)),
            "decoder logits data")) return false;

    OrtValues<kDecoderPastCount> past(api);
    std::array<const OrtValue*, kDecoderInputs> inputs{};
    for (size_t i = 0; i < 4; ++i) inputs[i] = fixed[i];
    for (size_t i = 0; i < kDecoderPastCount; ++i) inputs[4 + i] = fixed[4];
    decodedIds.clear();
    for (int step = 0; step < maxTokens; ++step) {
        if (interrupted(error)) return false;
        OrtValues<kDecoderPastCount> presents(api);
        std::array<OrtValue*, kDecoderOutputs> outputs{};
        outputs[0] = fixed[5];
        OrtStatus* status = api->Run(session, runOptions, inputNames, inputs.data(),
            inputs.size(), outputNames, outputs.size(), outputs.data());
        // Adopt every returned KV tensor before inspecting status: ORT can
        // allocate some outputs before a later node reports a failure.
        for (size_t i = 0; i < kDecoderPastCount; ++i) presents.reset(i, outputs[1 + i]);
        if (!checked(status, step == 0 ? "decoder init run failed" : "decoder step run failed")) return false;
        if (interrupted(error)) return false;

        float best = -std::numeric_limits<float>::infinity();
        token = eosId;
        for (size_t i = 0; i < vocabSize; ++i) {
            if (i == static_cast<size_t>(padId)) continue;
            if (std::isnan(logits[i]) || logits[i] == std::numeric_limits<float>::infinity()) {
                return fail("Marian decoder returned invalid token scores");
            }
            if (logits[i] > best) {
                best = logits[i];
                token = static_cast<int64_t>(i);
            }
        }
        if (!std::isfinite(best)) return fail("Marian decoder returned no usable token scores");
        if (token == eosId) {
            // The pinned model can genuinely prefer immediate EOS for short
            // UI labels (for example English "Search"). Masking EOS invents
            // unrelated text, so report that model limitation explicitly.
            if (decodedIds.empty()) {
                if (emptyOutput) *emptyOutput = true;
                return fail("Marian produced no translation for this text. Try a longer phrase or another translator.");
            }
            return true;
        }
        decodedIds.push_back(token);
        if (step + 1 == maxTokens) break;
        for (int layer = 0; layer < kDecoderLayers; ++layer) {
            for (int side = 0; side < 2; ++side) {
                if (step != 0 && side == 1) continue;
                for (int kv = 0; kv < 2; ++kv) {
                    const size_t index = decoderPastIndex(layer, side, kv);
                    if (!presents[index]) return fail("Marian decoder returned a missing cache tensor");
                    past.reset(index, presents.take(index));
                    inputs[4 + index] = past[index];
                }
            }
        }
        cacheBranch = 1;
    }
    return fail("Marian translation exceeded " + std::to_string(maxTokens) +
        " output tokens; incomplete text was not published");
}

} // namespace stt::marian::detail
#endif // HEARTH_MARIAN_DECODER_H
