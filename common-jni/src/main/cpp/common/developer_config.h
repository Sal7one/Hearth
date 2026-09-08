#ifndef STT_DEVELOPER_CONFIG_H
#define STT_DEVELOPER_CONFIG_H

#include "json_utils.h"

namespace stt {

/**
 * The former process-wide developer configuration was never connected to a
 * processing engine. Keep one truthful compatibility response for old JNI
 * callers instead of retaining inert mutable state.
 */
inline constexpr const char* kGlobalDeveloperConfigUnsupportedMessage =
    "Global processing and audio-gate configuration is unsupported; "
    "configure each STT session with SttConfig or each VAD detector with VadConfig";

inline JsonValue globalDeveloperConfigStatus() {
    return JsonValue::object({
        {"supported", false},
        {"applied", false},
        {"scope", "legacy-global"},
        {"code", "unsupported_global_configuration"},
        {"reason", kGlobalDeveloperConfigUnsupportedMessage},
        {"replacement", "SttConfig / VadConfig"}
    });
}

} // namespace stt

#endif // STT_DEVELOPER_CONFIG_H
