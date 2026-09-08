#include "engine_interface.h"

#include <algorithm>
#include <cmath>
#include <limits>

#include "json_utils.h"

namespace stt {
namespace {

bool fieldError(const char* key, const char* expected, std::string* error) {
    if (error) *error = std::string("invalid '") + key + "': expected " + expected;
    return false;
}

bool readString(
    const JsonValue& root,
    const char* key,
    std::string& target,
    size_t maxLength,
    std::string* error
) {
    const JsonValue* value = root.find(key);
    if (!value) return true;
    std::string candidate;
    if (!value->asString(candidate) || candidate.size() > maxLength ||
        candidate.find('\0') != std::string::npos) {
        return fieldError(key, "a bounded string", error);
    }
    target = std::move(candidate);
    return true;
}

bool readBool(const JsonValue& root, const char* key, bool& target, std::string* error) {
    const JsonValue* value = root.find(key);
    if (!value) return true;
    bool candidate = false;
    if (!value->asBool(candidate)) return fieldError(key, "a boolean", error);
    target = candidate;
    return true;
}

bool readInt(
    const JsonValue& root,
    const char* key,
    int& target,
    int minimum,
    int maximum,
    std::string* error
) {
    const JsonValue* value = root.find(key);
    if (!value) return true;
    int64_t candidate = 0;
    if (!value->asInt64(candidate) || candidate < minimum || candidate > maximum) {
        return fieldError(key, "an in-range integer", error);
    }
    target = static_cast<int>(candidate);
    return true;
}

bool readFloat(
    const JsonValue& root,
    const char* key,
    float& target,
    double minimum,
    double maximum,
    std::string* error
) {
    const JsonValue* value = root.find(key);
    if (!value) return true;
    double candidate = 0.0;
    if (!value->asDouble(candidate) || !std::isfinite(candidate) ||
        candidate < minimum || candidate > maximum) {
        return fieldError(key, "a finite in-range number", error);
    }
    target = static_cast<float>(candidate);
    return true;
}

bool readAudioProcessingMode(
    const JsonValue& root,
    AudioProcessingMode& target,
    std::string* error
) {
    const JsonValue* value = root.find("audioProcessingMode");
    if (!value) return true;
    std::string mode;
    if (!value->asString(mode)) {
        return fieldError(
            "audioProcessingMode",
            "one of 'auto', 'batch', or 'streaming'",
            error
        );
    }
    if (mode == "auto") target = AudioProcessingMode::AUTO;
    else if (mode == "batch") target = AudioProcessingMode::BATCH;
    else if (mode == "streaming") target = AudioProcessingMode::STREAMING;
    else {
        return fieldError(
            "audioProcessingMode",
            "one of 'auto', 'batch', or 'streaming'",
            error
        );
    }
    return true;
}

} // namespace

bool parseConfig(const std::string& json, EngineConfig& config, std::string* error) {
    EngineConfig candidate;
    JsonValue root;
    const std::string document = json.empty() ? "{}" : json;
    if (!JsonUtils::parse(document, root, error)) return false;
    if (!root.isObject()) {
        if (error) *error = "engine config must be a JSON object";
        return false;
    }
    static const std::vector<std::string> allowedKeys = {
        "modelPath", "modelSha256", "sampleRate", "language", "enableTimestamps", "numThreads",
        "translate", "noSpeechThreshold", "suppressBlank", "suppressNonSpeechTokens",
        "debugForceEnglish", "debugLogging", "audioProcessingMode",
        "streamingChunkDurationMs", "streamingContextDurationMs",
        "chunkDurationMs", "contextDurationMs",
        "enableVad", "maxSegmentLengthMs", "silenceThresholdDb", "verbose"
    };
    if (!JsonUtils::validateObjectKeys(root, allowedKeys, error)) return false;

    if (!readString(root, "modelPath", candidate.modelPath, 4096, error) ||
        !readString(root, "modelSha256", candidate.modelSha256, 64, error) ||
        !readInt(root, "sampleRate", candidate.sampleRate, 8000, 192000, error) ||
        !readString(root, "language", candidate.language, 64, error) ||
        !readBool(root, "enableTimestamps", candidate.enableTimestamps, error) ||
        !readInt(root, "numThreads", candidate.numThreads, 1, 8, error) ||
        !readBool(root, "translate", candidate.translateToEnglish, error) ||
        !readFloat(root, "noSpeechThreshold", candidate.noSpeechThreshold, 0.0, 1.0, error) ||
        !readBool(root, "suppressBlank", candidate.suppressBlank, error) ||
        !readBool(root, "suppressNonSpeechTokens", candidate.suppressNonSpeechTokens, error) ||
        !readBool(root, "debugForceEnglish", candidate.debugForceEnglish, error) ||
        !readBool(root, "debugLogging", candidate.debugLogging, error) ||
        !readAudioProcessingMode(root, candidate.audioProcessingMode, error) ||
        !readBool(root, "enableVad", candidate.enableVad, error) ||
        !readInt(root, "maxSegmentLengthMs", candidate.maxSegmentLengthMs, 100, 3600000, error) ||
        !readFloat(root, "silenceThresholdDb", candidate.silenceThresholdDb, -160.0, 0.0, error) ||
        !readBool(root, "verbose", candidate.verbose, error)) {
        return false;
    }
    const bool hasStreamingChunk = root.find("streamingChunkDurationMs") != nullptr;
    const bool hasLegacyChunk = root.find("chunkDurationMs") != nullptr;
    const bool hasStreamingContext = root.find("streamingContextDurationMs") != nullptr;
    const bool hasLegacyContext = root.find("contextDurationMs") != nullptr;
    if (hasStreamingChunk && hasLegacyChunk) {
        if (error) {
            *error = "streamingChunkDurationMs and chunkDurationMs cannot both be present";
        }
        return false;
    }
    if (hasStreamingContext && hasLegacyContext) {
        if (error) {
            *error = "streamingContextDurationMs and contextDurationMs cannot both be present";
        }
        return false;
    }
    if (!readInt(
            root,
            hasStreamingChunk ? "streamingChunkDurationMs" : "chunkDurationMs",
            candidate.chunkDurationMs,
            10,
            600000,
            error
        ) ||
        !readInt(
            root,
            hasStreamingContext ? "streamingContextDurationMs" : "contextDurationMs",
            candidate.contextDurationMs,
            0,
            600000,
            error
        )) {
        return false;
    }
    if (!candidate.modelSha256.empty() &&
        (candidate.modelSha256.size() != 64 ||
         !std::all_of(candidate.modelSha256.begin(), candidate.modelSha256.end(), [](char c) {
             return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
         }))) {
        return fieldError("modelSha256", "64 lowercase hexadecimal characters", error);
    }
    if (candidate.contextDurationMs > candidate.chunkDurationMs) {
        if (error) {
            *error = "streaming context duration must not exceed chunk duration";
        }
        return false;
    }

    config = std::move(candidate);
    if (error) error->clear();
    return true;
}

} // namespace stt
