/**
 * TTS Engine Factory
 *
 * Creates engine instances based on type and compile-time availability.
 *
 * No engine in this tree fabricates audio. If a backend isn't compiled in,
 * createTtsEngine() returns nullptr and isTtsEngineAvailable() returns false.
 * Supported engines are enabled with -DENABLE_KOKORO=ON,
 * -DENABLE_SHERPA_ONNX_TTS=ON or -DENABLE_SUPERTONIC=ON at CMake time.
 */

#include "tts_engine_interface.h"
#include "vits_onnx_engine.h"
// Future engines (enable with corresponding CMake flag):
// #include "kokoro_engine.h"
// #include "supertonic_engine.h"

#include "../common/logging.h"
#include "../common/json_utils.h"

namespace common_jni {
namespace tts {

static const char* TAG = "TtsFactory";

std::unique_ptr<ITtsEngine> createTtsEngine(TtsEngineType type) {
    switch (type) {
        case TtsEngineType::KOKORO:
#ifdef ENABLE_KOKORO
            LOG_E(TAG, "Kokoro backend headers present but engine not linked");
            return nullptr;
#else
            LOG_W(TAG, "Kokoro engine not compiled in (rebuild with -DENABLE_KOKORO=ON)");
            return nullptr;
#endif

        case TtsEngineType::SHERPA_ONNX:
#if defined(WITH_ONNX)
            // Real neural TTS: sherpa-onnx VITS bundles run through the
            // vendored ONNX Runtime — see vits_onnx_engine.cpp.
            return std::make_unique<VitsOnnxEngine>();
#else
            LOG_W(TAG, "sherpa-onnx VITS needs the ONNX Runtime (WITH_ONNX)");
            return nullptr;
#endif

        case TtsEngineType::SUPERTONIC:
#ifdef ENABLE_SUPERTONIC
            LOG_E(TAG, "Supertonic backend headers present but engine not linked");
            return nullptr;
#else
            LOG_W(TAG, "Supertonic engine not compiled in (rebuild with -DENABLE_SUPERTONIC=ON)");
            return nullptr;
#endif

        default:
            LOG_E(TAG, "Unsupported TTS engine type: %d", static_cast<int>(type));
            return nullptr;
    }
}

bool isTtsEngineAvailable(TtsEngineType type) {
    switch (type) {
        case TtsEngineType::KOKORO:
#ifdef ENABLE_KOKORO
            return true;
#else
            return false;
#endif
        case TtsEngineType::SHERPA_ONNX:
#if defined(WITH_ONNX)
            return true;
#else
            return false;
#endif
        case TtsEngineType::SUPERTONIC:
#ifdef ENABLE_SUPERTONIC
            return true;
#else
            return false;
#endif
        default:
            return false;
    }
}

std::vector<TtsEngineType> getAvailableTtsEngines() {
    std::vector<TtsEngineType> available;
    if (isTtsEngineAvailable(TtsEngineType::KOKORO))      available.push_back(TtsEngineType::KOKORO);
    if (isTtsEngineAvailable(TtsEngineType::SHERPA_ONNX)) available.push_back(TtsEngineType::SHERPA_ONNX);
    if (isTtsEngineAvailable(TtsEngineType::SUPERTONIC))  available.push_back(TtsEngineType::SUPERTONIC);
    return available;
}

namespace {

bool configError(const char* key, const char* expected, std::string* error) {
    if (error) *error = std::string("invalid '") + key + "': expected " + expected;
    return false;
}

bool readString(
    const stt::JsonValue& root,
    const char* key,
    std::string& target,
    std::string* error
) {
    const auto* value = root.find(key);
    if (!value) return true;
    std::string candidate;
    if (!value->asString(candidate) || candidate.size() > 4096 ||
        candidate.find('\0') != std::string::npos) {
        return configError(key, "a bounded string", error);
    }
    target = std::move(candidate);
    return true;
}

bool readBool(const stt::JsonValue& root, const char* key, bool& target, std::string* error) {
    const auto* value = root.find(key);
    if (!value) return true;
    bool candidate = false;
    if (!value->asBool(candidate)) return configError(key, "a boolean", error);
    target = candidate;
    return true;
}

bool readInt(
    const stt::JsonValue& root,
    const char* key,
    int& target,
    int minimum,
    int maximum,
    std::string* error
) {
    const auto* value = root.find(key);
    if (!value) return true;
    int64_t candidate = 0;
    if (!value->asInt64(candidate) || candidate < minimum || candidate > maximum) {
        return configError(key, "an in-range integer", error);
    }
    target = static_cast<int>(candidate);
    return true;
}

bool readFloat(
    const stt::JsonValue& root,
    const char* key,
    float& target,
    double minimum,
    double maximum,
    std::string* error
) {
    const auto* value = root.find(key);
    if (!value) return true;
    double candidate = 0.0;
    if (!value->asDouble(candidate) || candidate < minimum || candidate > maximum) {
        return configError(key, "a finite in-range number", error);
    }
    target = static_cast<float>(candidate);
    return true;
}

} // namespace

bool parseTtsConfig(
    const std::string& json,
    TtsEngineConfig& config,
    std::string* error
) {
    TtsEngineConfig candidate;
    stt::JsonValue root;
    const std::string document = json.empty() ? "{}" : json;
    if (!stt::JsonUtils::parse(document, root, error)) return false;
    if (!root.isObject()) {
        if (error) *error = "TTS config must be a JSON object";
        return false;
    }
    static const std::vector<std::string> allowedKeys = {
        "qualityMode", "sampleRate", "numThreads", "useGpu", "maxCacheLength",
        "tokensPerStep", "speed", "pitch", "volume", "vocabPath", "voicePromptPath"
    };
    if (!stt::JsonUtils::validateObjectKeys(root, allowedKeys, error)) return false;

    int qualityMode = static_cast<int>(candidate.qualityMode);
    if (!readInt(root, "qualityMode", qualityMode, 0, 2, error) ||
        !readInt(root, "sampleRate", candidate.sampleRate, 8000, 192000, error) ||
        !readInt(root, "numThreads", candidate.numThreads, 1, 8, error) ||
        !readBool(root, "useGpu", candidate.useGpu, error) ||
        !readInt(root, "maxCacheLength", candidate.maxCacheLength, 1, 1048576, error) ||
        !readInt(root, "tokensPerStep", candidate.tokensPerStep, 1, 1024, error) ||
        !readFloat(root, "speed", candidate.speed, 0.25, 4.0, error) ||
        !readFloat(root, "pitch", candidate.pitch, 0.25, 4.0, error) ||
        !readFloat(root, "volume", candidate.volume, 0.0, 1.0, error) ||
        !readString(root, "vocabPath", candidate.vocabPath, error) ||
        !readString(root, "voicePromptPath", candidate.voicePromptPath, error)) {
        return false;
    }
    candidate.qualityMode = static_cast<TtsQualityMode>(qualityMode);
    config = std::move(candidate);
    if (error) error->clear();
    return true;
}

} // namespace tts
} // namespace common_jni
