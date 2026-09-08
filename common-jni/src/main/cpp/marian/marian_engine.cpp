#include "marian_engine.h"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <limits>

namespace stt {
namespace marian {

namespace {

constexpr const char* kEncoderInputNames[2] = {"input_ids", "attention_mask"};
constexpr const char* kEncoderOutputNames[1] = {"last_hidden_state"};

// Decoder input order for Ort::Run (names must match the graph).
// Index layout below: 0=input_ids, 1=encoder_attention_mask,
// 2=encoder_hidden_states, 3=use_cache_branch, then 24 past tensors
// (layer, side decoder|encoder, key|value), then 24 present outputs.
constexpr int kPastTensorCount = 24;
constexpr int kDecoderInputCount = 4 + kPastTensorCount;
constexpr int kDecoderOutputCount = 1 + kPastTensorCount;

std::string pastName(int layer, const char* side, const char* kv,
                     bool present) {
    std::string name = present ? "present." : "past_key_values.";
    name += std::to_string(layer) + "." + side + "." + kv;
    return name;
}

const char* sideName(int side) { return side == 0 ? "decoder" : "encoder"; }
const char* kvName(int kv) { return kv == 0 ? "key" : "value"; }

// past index: layer * 4 + side * 2 + kv  (matches the graph order)
int pastIndex(int layer, int side, int kv) {
    return layer * 4 + side * 2 + kv;
}

bool fileExists(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    return f.good();
}

std::string findModelFile(const std::string& dir, const std::string& needle) {
    // Deterministic scan: sorted entries, first name containing needle and
    // ending with .onnx wins.
    // Directory iteration is avoided for portability; the bundled layout uses
    // fixed names, so check the conventional names first, then a few
    // alternatives.
    std::vector<std::string> candidates;
    const std::string needleLower = [&needle]() {
        std::string n = needle;
        std::transform(n.begin(), n.end(), n.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        return n;
    }();
    // Conventional names first.
    for (const char* name : {"encoder_model_quantized.onnx",
                             "encoder_model.onnx",
                             "decoder_model_merged_quantized.onnx",
                             "decoder_model_merged.onnx"}) {
        candidates.push_back(dir + "/" + name);
    }
    for (const auto& candidate : candidates) {
        std::string lower = candidate;
        std::transform(lower.begin(), lower.end(), lower.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        if (lower.find(needleLower) != std::string::npos &&
            fileExists(candidate)) {
            return candidate;
        }
    }
    return "";
}

// Applies an optional session-config override driven by an environment
// variable. The knob only fires when the env var equals `sentinel` (e.g. "0"
// for a disable knob, "1" for an enable knob); an unset variable or any other
// value leaves the session options untouched. Failures are logged and ignored
// so the engine still loads with default behavior.
void applyEnvSessionConfig(const OrtApi* api, OrtSessionOptions* options,
                           const char* envName, const char* sentinel,
                           const char* key, const char* value) {
    const char* env = std::getenv(envName);
    if (env == nullptr || std::strcmp(env, sentinel) != 0) return;
    if (api == nullptr || api->AddSessionConfigEntry == nullptr) {
        std::fprintf(stderr,
                     "W/Marian: AddSessionConfigEntry unavailable; ignoring "
                     "%s=%s\n",
                     key, value);
        return;
    }
    OrtStatus* status = api->AddSessionConfigEntry(options, key, value);
    if (status != nullptr) {
        const char* msg = api->GetErrorMessage(status);
        std::fprintf(stderr,
                     "W/Marian: AddSessionConfigEntry(%s=%s) failed: %s\n",
                     key, value, (msg != nullptr ? msg : "unknown error"));
        api->ReleaseStatus(status);
        return;
    }
    std::fprintf(stderr, "I/Marian: session config %s=%s applied\n", key, value);
}

}  // namespace

MarianEngine::~MarianEngine() {
    if (api_) {
        if (encoderSession_) api_->ReleaseSession(encoderSession_);
        if (decoderSession_) api_->ReleaseSession(decoderSession_);
        if (allocator_) api_->ReleaseAllocator(allocator_);
        if (memoryInfo_) api_->ReleaseMemoryInfo(memoryInfo_);
        if (options_) api_->ReleaseSessionOptions(options_);
        if (env_) api_->ReleaseEnv(env_);
    }
    api_ = nullptr;
}

bool MarianEngine::setError(std::string* error, const std::string& message) const {
    if (error) *error = message;
    return false;
}

bool MarianEngine::loadSession(const std::string& path, OrtSession** session,
                               std::string* error) {
    OrtStatus* status = api_->CreateSession(env_, path.c_str(), options_, session);
    if (status) {
        const char* msg = api_->GetErrorMessage(status);
        std::string message = "CreateSession failed for " + path + ": " +
                              (msg ? msg : "unknown error");
        api_->ReleaseStatus(status);
        return setError(error, message);
    }
    return true;
}

std::unique_ptr<MarianEngine> MarianEngine::create(const std::string& modelDir,
                                                   int numThreads,
                                                   std::string* error) {
    auto engine = std::unique_ptr<MarianEngine>(new MarianEngine());
    if (!engine->init(modelDir, numThreads, error)) return nullptr;
    return engine;
}

bool MarianEngine::init(const std::string& modelDir, int numThreads,
                        std::string* error) {
    const std::string spmPath = modelDir + "/source.spm";
    const std::string jsonPath = modelDir + "/tokenizer.json";
    encoderPath_ = findModelFile(modelDir, "encoder_model");
    decoderPath_ = findModelFile(modelDir, "decoder_model_merged");

    if (encoderPath_.empty()) {
        return setError(error, "no encoder_model*.onnx found in " + modelDir);
    }
    if (decoderPath_.empty()) {
        return setError(error,
                        "no decoder_model_merged*.onnx found in " + modelDir +
                            " (the merged decoder is required)");
    }
    if (!fileExists(spmPath)) {
        return setError(error, "source.spm missing in " + modelDir);
    }
    if (!fileExists(jsonPath)) {
        return setError(error, "tokenizer.json missing in " + modelDir);
    }

    if (!MarianTokenizer::load(spmPath, jsonPath, tokenizer_, error)) {
        return false;
    }
    eosId_ = tokenizer_.eosTokenId();
    padId_ = tokenizer_.padTokenId();

    // Prefer the header's API version, then walk back: the functions used
    // here are stable since the earliest ORT releases, and the OrtApi struct
    // is append-only across versions.
    api_ = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (!api_) {
        for (uint32_t version = ORT_API_VERSION - 1U; version >= 1U;
             --version) {
            api_ = OrtGetApiBase()->GetApi(version);
            if (api_) break;
        }
    }
    if (!api_) return setError(error, "failed to obtain the ONNX Runtime API");

    OrtStatus* status = api_->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "marian",
                                        &env_);
    if (status) {
        const char* msg = api_->GetErrorMessage(status);
        std::string message = std::string("CreateEnv failed: ") +
                              (msg ? msg : "unknown error");
        api_->ReleaseStatus(status);
        return setError(error, message);
    }

    status = api_->CreateSessionOptions(&options_);
    if (status) {
        const char* msg = api_->GetErrorMessage(status);
        std::string message = std::string("CreateSessionOptions failed: ") +
                              (msg ? msg : "unknown error");
        api_->ReleaseStatus(status);
        return setError(error, message);
    }

    status = api_->SetSessionGraphOptimizationLevel(options_, ORT_ENABLE_ALL);
    if (status) {
        api_->ReleaseStatus(status);
        return setError(error, "SetSessionGraphOptimizationLevel failed");
    }
    status = api_->SetIntraOpNumThreads(options_, numThreads > 0 ? numThreads : 1);
    if (status) {
        api_->ReleaseStatus(status);
        return setError(error, "SetIntraOpNumThreads failed");
    }

    // Optional per-session config knobs (measurement only). Default behavior
    // is unchanged when the environment variables are unset.
    applyEnvSessionConfig(api_, options_, "MARIAN_ORT_SPIN", "0",
                          "session.intra_op.allow_spinning", "0");
    applyEnvSessionConfig(api_, options_, "MARIAN_ORT_ARENA", "1",
                          "arena.extend_strategy", "kNextPowerOfTwo");

    status = api_->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault,
                                       &memoryInfo_);
    if (status) {
        api_->ReleaseStatus(status);
        return setError(error, "CreateCpuMemoryInfo failed");
    }

    if (!loadSession(encoderPath_, &encoderSession_, error)) return false;
    if (!loadSession(decoderPath_, &decoderSession_, error)) return false;

    status = api_->CreateAllocator(encoderSession_, memoryInfo_, &allocator_);
    if (status) {
        api_->ReleaseStatus(status);
        return setError(error, "CreateAllocator failed");
    }

    // Prepare decoder input/output name tables once. The strings must
    // outlive the char* arrays, so they live in nameStorage_.
    nameStorage_.clear();
    inputNames_.clear();
    outputNames_.clear();
    // Reserve everything up front: c_str() pointers captured below would
    // dangle if a later emplace_back reallocated (small-string-optimized
    // strings store their bytes inside the string object).
    nameStorage_.reserve(4U + static_cast<size_t>(kPastTensorCount) + 1U +
                         static_cast<size_t>(kPastTensorCount));
    nameStorage_.emplace_back("input_ids");
    nameStorage_.emplace_back("encoder_attention_mask");
    nameStorage_.emplace_back("encoder_hidden_states");
    nameStorage_.emplace_back("use_cache_branch");
    for (int layer = 0; layer < kLayers; ++layer) {
        for (int side = 0; side < 2; ++side) {
            for (int kv = 0; kv < 2; ++kv) {
                nameStorage_.push_back(
                    pastName(layer, sideName(side), kvName(kv), false));
            }
        }
    }
    for (auto& name : nameStorage_) {
        inputNames_.push_back(const_cast<char*>(name.c_str()));
    }
    nameStorage_.emplace_back("logits");
    for (int layer = 0; layer < kLayers; ++layer) {
        for (int side = 0; side < 2; ++side) {
            for (int kv = 0; kv < 2; ++kv) {
                nameStorage_.push_back(
                    pastName(layer, sideName(side), kvName(kv), true));
            }
        }
    }
    for (size_t i = kDecoderInputCount; i < nameStorage_.size(); ++i) {
        outputNames_.push_back(const_cast<char*>(nameStorage_[i].c_str()));
    }
    return true;
}

bool MarianEngine::runEncoder(const std::vector<int64_t>& ids,
                              std::vector<float>& hidden,
                              std::string* error) {
    const int64_t n = static_cast<int64_t>(ids.size());
    const int64_t shape[2] = {1, n};
    OrtStatus* status = nullptr;

    OrtValue* inputIds = nullptr;
    OrtValue* attention = nullptr;
    status = api_->CreateTensorWithDataAsOrtValue(
        memoryInfo_, const_cast<int64_t*>(ids.data()),
        sizeof(int64_t) * static_cast<size_t>(n), shape, 2,
        ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &inputIds);
    if (status) {
        api_->ReleaseStatus(status);
        return setError(error, "failed to create encoder input_ids tensor");
    }
    std::vector<int64_t> mask(static_cast<size_t>(n), 1);
    status = api_->CreateTensorWithDataAsOrtValue(
        memoryInfo_, mask.data(), sizeof(int64_t) * static_cast<size_t>(n),
        shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &attention);
    if (status) {
        api_->ReleaseStatus(status);
        api_->ReleaseValue(inputIds);
        return setError(error, "failed to create encoder attention tensor");
    }

    const int64_t outShape[3] = {1, n, kHiddenDim};
    OrtValue* output = nullptr;
    status = api_->CreateTensorAsOrtValue(
        allocator_, outShape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &output);
    if (status) {
        api_->ReleaseStatus(status);
        api_->ReleaseValue(attention);
        api_->ReleaseValue(inputIds);
        return setError(error, "failed to allocate encoder output tensor");
    }

    const OrtValue* inputs[2] = {inputIds, attention};
    OrtValue* outputs[1] = {output};
    const char* inputNames[2] = {kEncoderInputNames[0], kEncoderInputNames[1]};
    const char* outputNames[1] = {kEncoderOutputNames[0]};

    status = api_->Run(encoderSession_, nullptr, inputNames, inputs, 2,
                       outputNames, 1, outputs);
    if (status) {
        const char* msg = api_->GetErrorMessage(status);
        std::string message = std::string("encoder run failed: ") +
                              (msg ? msg : "unknown error");
        api_->ReleaseStatus(status);
        api_->ReleaseValue(output);
        api_->ReleaseValue(attention);
        api_->ReleaseValue(inputIds);
        return setError(error, message);
    }

    float* data = nullptr;
    status = api_->GetTensorMutableData(output, reinterpret_cast<void**>(&data));
    if (status) {
        api_->ReleaseStatus(status);
        api_->ReleaseValue(output);
        api_->ReleaseValue(attention);
        api_->ReleaseValue(inputIds);
        return setError(error, "failed to read encoder output");
    }
    const size_t count = static_cast<size_t>(n) * kHiddenDim;
    hidden.assign(data, data + count);

    api_->ReleaseValue(output);
    api_->ReleaseValue(attention);
    api_->ReleaseValue(inputIds);
    return true;
}

bool MarianEngine::decodeLoop(const std::vector<float>& hidden, size_t encLen,
                              std::vector<int64_t>& decodedIds,
                              std::string* error) {
    OrtStatus* status = nullptr;
    const int64_t vocabSize = static_cast<int64_t>(tokenizer_.vocabSize());
    const int64_t encLen64 = static_cast<int64_t>(encLen);
    std::vector<int64_t> emptyShape = {1, kHeads, 0, kHeadDim};

    // Init step: use_cache_branch=false, empty pasts.
    {
        decInput_.assign(1, padId_);
        const int64_t shape[2] = {1, 1};
        OrtValue* inputIds = nullptr;
        status = api_->CreateTensorWithDataAsOrtValue(
            memoryInfo_, decInput_.data(), sizeof(int64_t), shape, 2,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &inputIds);
        if (status) { api_->ReleaseStatus(status); return setError(error, "init input_ids tensor failed"); }

        OrtValue* encAttn = nullptr;
        const int64_t attnShape[2] = {1, encLen64};
        status = api_->CreateTensorWithDataAsOrtValue(
            memoryInfo_, attention_.data(),
            sizeof(int64_t) * encLen, attnShape, 2,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &encAttn);
        if (status) { api_->ReleaseStatus(status); api_->ReleaseValue(inputIds); return setError(error, "init mask tensor failed"); }

        const int64_t hiddenShape[3] = {1, encLen64, kHiddenDim};
        OrtValue* hiddenValue = nullptr;
        status = api_->CreateTensorWithDataAsOrtValue(
            memoryInfo_, const_cast<float*>(hidden.data()),
            sizeof(float) * encLen * kHiddenDim, hiddenShape, 3,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &hiddenValue);
        if (status) { api_->ReleaseStatus(status); api_->ReleaseValue(encAttn); api_->ReleaseValue(inputIds); return setError(error, "init hidden tensor failed"); }

        uint8_t branch = 0;
        const int64_t branchShape[1] = {1};
        OrtValue* branchValue = nullptr;
        status = api_->CreateTensorWithDataAsOrtValue(
            memoryInfo_, &branch, sizeof(uint8_t), branchShape, 1,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL, &branchValue);
        if (status) { api_->ReleaseStatus(status); api_->ReleaseValue(hiddenValue); api_->ReleaseValue(encAttn); api_->ReleaseValue(inputIds); return setError(error, "init branch tensor failed"); }

        std::vector<OrtValue*> inputs;
        inputs.reserve(kDecoderInputCount);
        inputs.push_back(inputIds);
        inputs.push_back(encAttn);
        inputs.push_back(hiddenValue);
        inputs.push_back(branchValue);
        for (int i = 0; i < kPastTensorCount; ++i) {
            OrtValue* empty = nullptr;
            status = api_->CreateTensorWithDataAsOrtValue(
                memoryInfo_, nullptr, 0, emptyShape.data(), 4,
                ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &empty);
            if (status) {
                api_->ReleaseStatus(status);
                for (OrtValue* v : inputs) api_->ReleaseValue(v);
                return setError(error, "init past tensor failed");
            }
            inputs.push_back(empty);
        }

        const int64_t logitsShape[3] = {1, 1, vocabSize};
        OrtValue* logitsOut = nullptr;
        status = api_->CreateTensorAsOrtValue(
            allocator_, logitsShape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,
            &logitsOut);
        if (status) {
            api_->ReleaseStatus(status);
            for (OrtValue* v : inputs) api_->ReleaseValue(v);
            return setError(error, "init logits tensor failed");
        }
        std::vector<OrtValue*> outputs;
        outputs.reserve(kDecoderOutputCount);
        outputs.push_back(logitsOut);
        const int64_t decoderPresentShape[4] = {1, kHeads, 1, kHeadDim};
        const int64_t encoderPresentShape[4] = {1, kHeads, encLen64, kHeadDim};
        for (int layer = 0; layer < kLayers; ++layer) {
            for (int side = 0; side < 2; ++side) {
                for (int kv = 0; kv < 2; ++kv) {
                    const int64_t* shape =
                        side == 0 ? decoderPresentShape : encoderPresentShape;
                    OrtValue* present = nullptr;
                    status = api_->CreateTensorAsOrtValue(
                        allocator_, shape, 4,
                        ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &present);
                    if (status) {
                        api_->ReleaseStatus(status);
                        for (OrtValue* v : outputs) api_->ReleaseValue(v);
                        for (OrtValue* v : inputs) api_->ReleaseValue(v);
                        return setError(error, "init present tensor failed");
                    }
                    outputs.push_back(present);
                }
            }
        }

        status = api_->Run(decoderSession_, nullptr, inputNames_.data(),
                           inputs.data(), static_cast<size_t>(kDecoderInputCount),
                           outputNames_.data(),
                           static_cast<size_t>(kDecoderOutputCount),
                           outputs.data());
        if (status) {
            const char* msg = api_->GetErrorMessage(status);
            std::string message = std::string("decoder init run failed: ") +
                                  (msg ? msg : "unknown error");
            api_->ReleaseStatus(status);
            for (OrtValue* v : outputs) api_->ReleaseValue(v);
            for (OrtValue* v : inputs) api_->ReleaseValue(v);
            return setError(error, message);
        }

        // Copy logits; select the first token.
        float* logitsData = nullptr;
        status = api_->GetTensorMutableData(
            logitsOut, reinterpret_cast<void**>(&logitsData));
        if (status) {
            api_->ReleaseStatus(status);
            for (OrtValue* v : outputs) api_->ReleaseValue(v);
            for (OrtValue* v : inputs) api_->ReleaseValue(v);
            return setError(error, "failed to read decoder logits");
        }
        const size_t vocabCount = static_cast<size_t>(vocabSize);
        logits_.assign(logitsData, logitsData + vocabCount);

        // Copy presents into the persistent caches. Encoder presents stay
        // constant forever; decoder presents are fed back each step.
        for (int layer = 0; layer < kLayers; ++layer) {
            for (int side = 0; side < 2; ++side) {
                for (int kv = 0; kv < 2; ++kv) {
                    OrtValue* present =
                        outputs[1 + static_cast<size_t>(pastIndex(layer, side, kv))];
                    float* data = nullptr;
                    status = api_->GetTensorMutableData(
                        present, reinterpret_cast<void**>(&data));
                    if (status) {
                        api_->ReleaseStatus(status);
                        for (OrtValue* v : outputs) api_->ReleaseValue(v);
                        for (OrtValue* v : inputs) api_->ReleaseValue(v);
                        return setError(error, "failed to read decoder presents");
                    }
                    const size_t elements =
                        side == 0
                            ? static_cast<size_t>(kHeads) * kHeadDim
                            : encLen * static_cast<size_t>(kHeads) * kHeadDim;
                    std::vector<float>& target =
                        side == 0 ? decoderPast_[layer][kv] : encoderPast_[layer][kv];
                    target.assign(data, data + elements);
                }
            }
        }

        for (OrtValue* v : outputs) api_->ReleaseValue(v);
        for (OrtValue* v : inputs) api_->ReleaseValue(v);
    }

    // Greedy steps.
    int64_t token = -1;
    {
        float best = -std::numeric_limits<float>::infinity();
        int64_t bestId = eosId_;
        const size_t vocabCount = static_cast<size_t>(vocabSize);
        logits_[static_cast<size_t>(padId_)] =
            -std::numeric_limits<float>::infinity();
        for (size_t i = 0; i < vocabCount; ++i) {
            if (logits_[i] > best) {
                best = logits_[i];
                bestId = static_cast<int64_t>(i);
            }
        }
        token = bestId;
        if (token != eosId_) decodedIds.push_back(token);
    }

    const int64_t branchShape[1] = {1};
    uint8_t branchTrue = 1;
    for (int64_t step = 1; step < kMaxNewTokens && token != eosId_; ++step) {
        decInput_.assign(1, token);
        const int64_t shape[2] = {1, 1};
        OrtValue* inputIds = nullptr;
        status = api_->CreateTensorWithDataAsOrtValue(
            memoryInfo_, decInput_.data(), sizeof(int64_t), shape, 2,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &inputIds);
        if (status) { api_->ReleaseStatus(status); return setError(error, "step input_ids tensor failed"); }

        OrtValue* encAttn = nullptr;
        const int64_t attnShape[2] = {1, encLen64};
        status = api_->CreateTensorWithDataAsOrtValue(
            memoryInfo_, attention_.data(), sizeof(int64_t) * encLen,
            attnShape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &encAttn);
        if (status) { api_->ReleaseStatus(status); api_->ReleaseValue(inputIds); return setError(error, "step mask tensor failed"); }

        const int64_t hiddenShape[3] = {1, encLen64, kHiddenDim};
        OrtValue* hiddenValue = nullptr;
        status = api_->CreateTensorWithDataAsOrtValue(
            memoryInfo_, const_cast<float*>(hidden.data()),
            sizeof(float) * encLen * kHiddenDim, hiddenShape, 3,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &hiddenValue);
        if (status) { api_->ReleaseStatus(status); api_->ReleaseValue(encAttn); api_->ReleaseValue(inputIds); return setError(error, "step hidden tensor failed"); }

        OrtValue* branchValue = nullptr;
        status = api_->CreateTensorWithDataAsOrtValue(
            memoryInfo_, &branchTrue, sizeof(uint8_t), branchShape, 1,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL, &branchValue);
        if (status) { api_->ReleaseStatus(status); api_->ReleaseValue(hiddenValue); api_->ReleaseValue(encAttn); api_->ReleaseValue(inputIds); return setError(error, "step branch tensor failed"); }

        std::vector<OrtValue*> inputs;
        inputs.reserve(kDecoderInputCount);
        inputs.push_back(inputIds);
        inputs.push_back(encAttn);
        inputs.push_back(hiddenValue);
        inputs.push_back(branchValue);
        const int64_t pastLen = step;
        for (int layer = 0; layer < kLayers; ++layer) {
            for (int side = 0; side < 2; ++side) {
                for (int kv = 0; kv < 2; ++kv) {
                    std::vector<float>& source =
                        side == 0 ? decoderPast_[layer][kv] : encoderPast_[layer][kv];
                    const int64_t len =
                        side == 0 ? pastLen : encLen64;
                    const int64_t pastShape[4] = {1, kHeads, len, kHeadDim};
                    OrtValue* past = nullptr;
                    status = api_->CreateTensorWithDataAsOrtValue(
                        memoryInfo_, source.data(),
                        sizeof(float) * static_cast<size_t>(source.size()),
                        pastShape, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,
                        &past);
                    if (status) {
                        api_->ReleaseStatus(status);
                        for (OrtValue* v : inputs) api_->ReleaseValue(v);
                        return setError(error, "step past tensor failed");
                    }
                    inputs.push_back(past);
                }
            }
        }

        const int64_t logitsShape[3] = {1, 1, vocabSize};
        OrtValue* logitsOut = nullptr;
        status = api_->CreateTensorAsOrtValue(
            allocator_, logitsShape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,
            &logitsOut);
        if (status) {
            api_->ReleaseStatus(status);
            for (OrtValue* v : inputs) api_->ReleaseValue(v);
            return setError(error, "step logits tensor failed");
        }
        std::vector<OrtValue*> outputs;
        outputs.reserve(kDecoderOutputCount);
        outputs.push_back(logitsOut);
        const int64_t decoderPresentShape[4] = {1, kHeads, pastLen + 1, kHeadDim};
        // On the cache branch the graph emits DUMMY encoder presents with an
        // empty sequence dimension ({0,8,1,64}); the output buffers must
        // match or ORT's shape verification rejects the run. They are never
        // read (see the class comment).
        const int64_t encoderPresentShape[4] = {0, kHeads, 1, kHeadDim};
        for (int layer = 0; layer < kLayers; ++layer) {
            for (int side = 0; side < 2; ++side) {
                for (int kv = 0; kv < 2; ++kv) {
                    const int64_t* shape =
                        side == 0 ? decoderPresentShape : encoderPresentShape;
                    OrtValue* present = nullptr;
                    status = api_->CreateTensorAsOrtValue(
                        allocator_, shape, 4,
                        ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &present);
                    if (status) {
                        api_->ReleaseStatus(status);
                        for (OrtValue* v : outputs) api_->ReleaseValue(v);
                        for (OrtValue* v : inputs) api_->ReleaseValue(v);
                        return setError(error, "step present tensor failed");
                    }
                    outputs.push_back(present);
                }
            }
        }

        status = api_->Run(decoderSession_, nullptr, inputNames_.data(),
                           inputs.data(), static_cast<size_t>(kDecoderInputCount),
                           outputNames_.data(),
                           static_cast<size_t>(kDecoderOutputCount),
                           outputs.data());
        if (status) {
            const char* msg = api_->GetErrorMessage(status);
            std::string message = std::string("decoder step run failed: ") +
                                  (msg ? msg : "unknown error");
            api_->ReleaseStatus(status);
            for (OrtValue* v : outputs) api_->ReleaseValue(v);
            for (OrtValue* v : inputs) api_->ReleaseValue(v);
            return setError(error, message);
        }

        float* logitsData = nullptr;
        status = api_->GetTensorMutableData(
            logitsOut, reinterpret_cast<void**>(&logitsData));
        if (status) {
            api_->ReleaseStatus(status);
            for (OrtValue* v : outputs) api_->ReleaseValue(v);
            for (OrtValue* v : inputs) api_->ReleaseValue(v);
            return setError(error, "failed to read decoder logits");
        }
        const size_t vocabCount = static_cast<size_t>(vocabSize);
        logits_.assign(logitsData, logitsData + vocabCount);
        logits_[static_cast<size_t>(padId_)] =
            -std::numeric_limits<float>::infinity();

        // Update decoder presents; encoder present outputs are dummies and
        // must be ignored (see the class comment).
        for (int layer = 0; layer < kLayers; ++layer) {
            for (int kv = 0; kv < 2; ++kv) {
                OrtValue* present =
                    outputs[1 + static_cast<size_t>(pastIndex(layer, 0, kv))];
                float* data = nullptr;
                status = api_->GetTensorMutableData(
                    present, reinterpret_cast<void**>(&data));
                if (status) {
                    api_->ReleaseStatus(status);
                    for (OrtValue* v : outputs) api_->ReleaseValue(v);
                    for (OrtValue* v : inputs) api_->ReleaseValue(v);
                    return setError(error, "failed to read decoder presents");
                }
                const size_t elements =
                    static_cast<size_t>(kHeads) *
                    static_cast<size_t>(pastLen + 1) * static_cast<size_t>(kHeadDim);
                decoderPast_[layer][kv].assign(data, data + elements);
            }
        }

        for (OrtValue* v : outputs) api_->ReleaseValue(v);
        for (OrtValue* v : inputs) api_->ReleaseValue(v);

        float best = -std::numeric_limits<float>::infinity();
        int64_t bestId = eosId_;
        for (size_t i = 0; i < vocabCount; ++i) {
            if (logits_[i] > best) {
                best = logits_[i];
                bestId = static_cast<int64_t>(i);
            }
        }
        token = bestId;
        if (token != eosId_) decodedIds.push_back(token);
    }

    return true;
}

MarianEngine::TranslateResult MarianEngine::translate(const std::string& text) {
    TranslateResult result;
    std::lock_guard<std::mutex> lock(mutex_);
    const auto start = std::chrono::steady_clock::now();

    if (!encoderSession_ || !decoderSession_) {
        result.error = "engine is not initialized";
        return result;
    }

    const auto tokenizeStart = std::chrono::steady_clock::now();
    const auto encoded = tokenizer_.encode(text);
    result.stages.tokenizeMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - tokenizeStart).count();
    if (!encoded.ok) {
        result.error = encoded.error;
        return result;
    }
    if (encoded.ids.empty()) {
        result.error = "tokenizer produced an empty sequence";
        return result;
    }

    const size_t encLen = encoded.ids.size();
    encIds_ = encoded.ids;
    attention_.assign(encLen, 1);

    std::string error;
    const auto encoderStart = std::chrono::steady_clock::now();
    if (!runEncoder(encIds_, hidden_, &error)) {
        result.error = error;
        return result;
    }
    result.stages.encoderMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - encoderStart).count();

    std::vector<int64_t> decodedIds;
    const auto decoderStart = std::chrono::steady_clock::now();
    if (!decodeLoop(hidden_, encLen, decodedIds, &error)) {
        result.error = error;
        return result;
    }
    result.stages.decoderMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - decoderStart).count();
    result.stages.tokensDecoded = static_cast<int64_t>(decodedIds.size());

    result.text = tokenizer_.decode(decodedIds);
    const auto end = std::chrono::steady_clock::now();
    result.latencyMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                           end - start)
                           .count();
    result.ok = true;
    stageStats_ = result.stages;
    lastLatencyMs_.store(result.latencyMs, std::memory_order_relaxed);
    // Direct fprintf (rather than LOG_I) so the host test build's
    // -Wpedantic -Werror does not reject the , ##__VA_ARGS__ GNU extension
    // inside the shared logging macros.
    std::fprintf(stderr,
                 "I/Marian: Marian stages: tok=%lldms enc=%lldms dec=%lldms "
                 "tokens=%lld\n",
                 static_cast<long long>(result.stages.tokenizeMs),
                 static_cast<long long>(result.stages.encoderMs),
                 static_cast<long long>(result.stages.decoderMs),
                 static_cast<long long>(result.stages.tokensDecoded));
    return result;
}

} // namespace marian
} // namespace stt
