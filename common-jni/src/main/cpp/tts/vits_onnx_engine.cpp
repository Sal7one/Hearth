// =============================================================================
// VITS ONNX engine implementation — real on-device neural TTS through the
// vendored ONNX Runtime (same C API Marian already uses).
// =============================================================================

#include "vits_onnx_engine.h"

#include "../common/logging.h"

#include <algorithm>
#include <cctype>
#include <cstring>
#include <fstream>
#include <sstream>

namespace common_jni {
namespace tts {

namespace {
constexpr const char* TAG = "VitsOnnx";

std::string trim(const std::string& s) {
    const auto begin = s.find_first_not_of(" \t\r\n");
    if (begin == std::string::npos) return "";
    const auto end = s.find_last_not_of(" \t\r\n");
    return s.substr(begin, end - begin + 1);
}

std::string toUpper(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(),
                   [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
    return s;
}
}  // namespace

VitsOnnxEngine::~VitsOnnxEngine() {
    release();
}

#if defined(WITH_ONNX)

bool VitsOnnxEngine::loadVocabulary(const std::string& dir) {
    std::ifstream in(dir + "/tokens.txt");
    if (!in.good()) {
        setError("tokens.txt not found next to the model");
        return false;
    }
    tokenIds_.clear();
    std::string line;
    while (std::getline(in, line)) {
        const auto trimmed = trim(line);
        if (trimmed.empty() || trimmed[0] == '#') continue;
        // sherpa format is "SYMBOL ID"; accept "ID SYMBOL" too.
        std::istringstream ss(trimmed);
        std::string a, b;
        ss >> a >> b;
        if (a.empty() || b.empty()) continue;
        if (std::isdigit(static_cast<unsigned char>(a[0]))) {
            tokenIds_[b] = std::stoll(a);
        } else {
            tokenIds_[a] = std::stoll(b);
        }
    }
    if (tokenIds_.empty()) {
        setError("tokens.txt had no usable entries");
        return false;
    }
    return true;
}

bool VitsOnnxEngine::loadLexicon(const std::string& dir) {
    std::ifstream in(dir + "/lexicon.txt");
    if (!in.good()) return false;  // optional: grapheme bundles skip it
    lexicon_.clear();
    std::string line;
    while (std::getline(in, line)) {
        const auto trimmed = trim(line);
        if (trimmed.empty()) continue;
        std::istringstream ss(trimmed);
        std::string word;
        ss >> word;
        std::vector<std::string> phones;
        std::string p;
        while (ss >> p) phones.push_back(p);
        if (!word.empty() && !phones.empty()) {
            lexicon_[word] = std::move(phones);
        }
    }
    return !lexicon_.empty();
}

int VitsOnnxEngine::detectSampleRate(const TtsEngineConfig& config) const {
    // 1) explicit <dir>/sample_rate.txt
    {
        std::ifstream in(config.modelPath + "/sample_rate.txt");
        int rate = 0;
        if (in >> rate && rate >= 8000 && rate <= 96000) return rate;
    }
    // 2) a 5-digit rate embedded in the bundle name (vits-en-22050, ...)
    {
        for (size_t i = 0; i + 5 <= config.modelPath.size(); ++i) {
            if (std::isdigit(static_cast<unsigned char>(config.modelPath[i])) &&
                std::isdigit(static_cast<unsigned char>(config.modelPath[i + 1])) &&
                std::isdigit(static_cast<unsigned char>(config.modelPath[i + 2])) &&
                std::isdigit(static_cast<unsigned char>(config.modelPath[i + 3])) &&
                std::isdigit(static_cast<unsigned char>(config.modelPath[i + 4]))) {
                const int value = std::stoi(config.modelPath.substr(i, 5));
                if (value >= 8000 && value <= 96000) return value;
            }
        }
    }
    // 3) known bundle families
    const std::string lowered = toUpper(config.modelPath);
    if (lowered.find("LJSPEECH") != std::string::npos) return 22050;
    if (lowered.find("AISHELL") != std::string::npos) return 16000;
    return 22050;  // most common VITS export rate
}

bool VitsOnnxEngine::tokenizeText(const std::string& text, std::vector<int64_t>& ids) {
    ids.clear();
    std::istringstream ss(toUpper(text));
    std::string word;
    size_t matched = 0, total = 0;
    auto pushSymbol = [&](const std::string& symbol) {
        auto it = tokenIds_.find(symbol);
        if (it != tokenIds_.end()) {
            ids.push_back(it->second);
            return true;
        }
        return false;
    };
    while (ss >> word) {
        ++total;
        bool ok = false;
        if (auto it = lexicon_.find(word); it != lexicon_.end()) {
            for (const auto& phone : it->second) ok = pushSymbol(phone) || ok;
        } else {
            // Grapheme fallback: spell the word out character by character.
            for (char ch : word) ok = pushSymbol(std::string(1, ch)) || ok;
        }
        if (ok) ++matched;
        // Word separator: many bundles encode space as "$sp"/"$space".
        pushSymbol("$sp");
    }
    if (ids.empty() || matched == 0) {
        setError("No known tokens in the text — wrong tokens.txt for this language?");
        return false;
    }
    return true;
}

bool VitsOnnxEngine::initialize(const TtsEngineConfig& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    release();

    api_ = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (api_ == nullptr) {
        setError("ONNX Runtime API unavailable");
        return false;
    }

    const std::string modelFile = config.modelPath + "/model.onnx";
    {
        std::ifstream probe(modelFile);
        if (!probe.good()) {
            setError("model.onnx not found in " + config.modelPath);
            return false;
        }
    }
    if (!loadVocabulary(config.modelPath)) return false;
    loadLexicon(config.modelPath);
    sampleRate_ = detectSampleRate(config);
    // VITS speaks pace through length_scale: smaller = faster. The config's
    // speed (0.25..4) inverts into it.
    lengthScale_ = 1.0f / std::max(0.25f, std::min(4.0f, config.speed));

    OrtStatus* status = api_->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "vits-tts", &env_);
    if (status != nullptr) {
        setError("CreateEnv failed");
        api_->ReleaseStatus(status);
        return false;
    }
    status = api_->CreateSessionOptions(&options_);
    if (status != nullptr) {
        api_->ReleaseStatus(status);
        return false;
    }
    api_->SetIntraOpNumThreads(options_, std::max(1, config.numThreads));
    api_->SetSessionGraphOptimizationLevel(options_, ORT_ENABLE_ALL);

    status = api_->CreateSession(env_, modelFile.c_str(), options_, &session_);
    if (status != nullptr) {
        const char* msg = api_->GetErrorMessage(status);
        setError(std::string("CreateSession failed: ") + (msg ? msg : "?"));
        api_->ReleaseStatus(status);
        return false;
    }

    initialized_.store(true);
    lastError_.clear();
    LOG_I(TAG, "VITS bundle loaded: %zu tokens, %zu lexicon entries, %d Hz",
          tokenIds_.size(), lexicon_.size(), sampleRate_);
    return true;
}

void VitsOnnxEngine::reset() {
    // Stateless engine: nothing to clear between utterances.
}

void VitsOnnxEngine::release() {
    if (session_ != nullptr && api_ != nullptr) api_->ReleaseSession(session_);
    if (options_ != nullptr && api_ != nullptr) api_->ReleaseSessionOptions(options_);
    // Env is shared per engine; created in initialize, released here.
    if (env_ != nullptr && api_ != nullptr) api_->ReleaseEnv(env_);
    session_ = nullptr;
    options_ = nullptr;
    env_ = nullptr;
    initialized_.store(false);
}

std::vector<float> VitsOnnxEngine::synthesize(const std::string& text, int targetSampleRate) {
    (void)targetSampleRate;  // VITS outputs its native rate; the caller resamples.
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_ || api_ == nullptr || session_ == nullptr) {
        setError("Engine not initialized");
        return {};
    }

    std::vector<int64_t> ids;
    if (!tokenizeText(text, ids)) return {};
    if (ids.size() > 1900) ids.resize(1900);  // VITS positional limit

    // Build the input tensors the graph actually declares. The vendored ORT
    // API needs an OrtMemoryInfo for wrapped-memory tensors.
    std::vector<const char*> inputNames;
    std::vector<OrtValue*> inputs;
    struct Cleanup {
        const OrtApi* api;
        std::vector<OrtValue*>* inputs;
        ~Cleanup() {
            for (auto* v : *inputs) api->ReleaseValue(v);
        }
    } cleanup{api_, &inputs};

    OrtMemoryInfo* memoryInfo = nullptr;
    api_->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memoryInfo);

    int64_t shape2[2] = {1, static_cast<int64_t>(ids.size())};
    int64_t shape1[1] = {1};
    int64_t lengths[1] = {static_cast<int64_t>(ids.size())};
    float noiseScale[1] = {noiseScale_};
    float noiseScaleW[1] = {noiseScaleW_};
    float lengthScale[1] = {lengthScale_};

    // Int64 token input + length + the three float scales sherpa VITS uses.
    {
        OrtValue* tokens = nullptr;
        if (api_->CreateTensorWithDataAsOrtValue(memoryInfo, ids.data(),
                                                 sizeof(int64_t) * ids.size(),
                                                 shape2, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,
                                                 &tokens) == nullptr && tokens) {
            inputNames.push_back("input");
            inputs.push_back(tokens);
        }
        OrtValue* lens = nullptr;
        if (api_->CreateTensorWithDataAsOrtValue(memoryInfo, lengths, sizeof(int64_t),
                                                 shape1, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,
                                                 &lens) == nullptr && lens) {
            inputNames.push_back("input_lengths");
            inputs.push_back(lens);
        }
        auto addFloat = [&](const char* name, float* data) {
            OrtValue* v = nullptr;
            if (api_->CreateTensorWithDataAsOrtValue(memoryInfo, data, sizeof(float),
                                                     shape1, 1,
                                                     ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,
                                                     &v) == nullptr && v) {
                inputNames.push_back(name);
                inputs.push_back(v);
            }
        };
        addFloat("noise_scale", noiseScale);
        addFloat("noise_scale_w", noiseScaleW);
        addFloat("length_scale", lengthScale);
    }

    if (inputs.size() < 2 || memoryInfo == nullptr) {
        if (memoryInfo != nullptr) api_->ReleaseMemoryInfo(memoryInfo);
        setError("Failed to build input tensors");
        return {};
    }

    // Output name discovery (usually a single "audio"/"output").
    const char* outputNames[4] = {nullptr, nullptr, nullptr, nullptr};
    size_t outputCount = 0;
    api_->SessionGetOutputCount(session_, &outputCount);
    if (outputCount == 0 || outputCount > 4) {
        setError("Unexpected output count");
        return {};
    }
    OrtAllocator* allocator = nullptr;
    api_->GetAllocatorWithDefaultOptions(&allocator);
    for (size_t i = 0; i < outputCount && i < 4; ++i) {
        api_->SessionGetOutputName(session_, i, allocator,
                                   const_cast<char**>(&outputNames[i]));
    }

    OrtValue* outputs[4] = {nullptr, nullptr, nullptr, nullptr};
    OrtStatus* runStatus = api_->Run(session_, nullptr, inputNames.data(), inputs.data(),
                                     inputs.size(), outputNames, outputCount, outputs);
    if (memoryInfo != nullptr) api_->ReleaseMemoryInfo(memoryInfo);
    if (runStatus != nullptr) {
        const char* msg = api_->GetErrorMessage(runStatus);
        setError(std::string("Inference failed: ") + (msg ? msg : "?"));
        api_->ReleaseStatus(runStatus);
        return {};
    }

    std::vector<float> audio;
    if (outputs[0] != nullptr) {
        OrtTensorTypeAndShapeInfo* info = nullptr;
        size_t elements = 0;
        if (api_->GetTensorTypeAndShape(outputs[0], &info) == nullptr && info != nullptr) {
            api_->GetTensorShapeElementCount(info, &elements);
            api_->ReleaseTensorTypeAndShapeInfo(info);
        }
        float* data = nullptr;
        api_->GetTensorMutableData(outputs[0], reinterpret_cast<void**>(&data));
        if (data != nullptr && elements > 0) {
            audio.assign(data, data + elements);
        }
    }
    for (size_t i = 0; i < outputCount && i < 4; ++i) {
        if (outputs[i] != nullptr) api_->ReleaseValue(outputs[i]);
    }
    if (audio.empty()) {
        setError("Model returned no audio");
        return {};
    }

    // Peak-guard: these models occasionally emit >1 spikes.
    float peak = 1e-6f;
    for (float v : audio) peak = std::max(peak, std::abs(v));
    if (peak > 1.0f) {
        const float gain = 0.95f / peak;
        for (float& v : audio) v *= gain;
    }
    return audio;
}

std::vector<TtsVoiceInfo> VitsOnnxEngine::getAvailableVoices() const {
    TtsVoiceInfo info;
    info.id = "default";
    info.displayName = "VITS bundle voice";
    info.isDefault = true;
    return {info};
}

bool VitsOnnxEngine::setVoice(const std::string& voiceId) {
    return voiceId == "default";
}

#else  // !WITH_ONNX

bool VitsOnnxEngine::initialize(const TtsEngineConfig&) {
    setError("Built without ONNX Runtime");
    return false;
}
void VitsOnnxEngine::reset() {}
void VitsOnnxEngine::release() {}
std::vector<float> VitsOnnxEngine::synthesize(const std::string&, int) { return {}; }
std::vector<TtsVoiceInfo> VitsOnnxEngine::getAvailableVoices() const { return {}; }
bool VitsOnnxEngine::setVoice(const std::string&) { return false; }

#endif  // WITH_ONNX

}  // namespace tts
}  // namespace common_jni
