#ifndef HEARTH_MARIAN_SESSION_CONFIG_H
#define HEARTH_MARIAN_SESSION_CONFIG_H

#include "../onnx/include/onnxruntime/onnxruntime_c_api.h"
#include <cstring>
#include <string>

namespace stt::marian {

// ASR and Marian often run side by side. ORT worker spinning is disabled by
// default; MARIAN_ORT_SPIN=1 is a deliberate benchmark-only opt-in.
inline bool configureIntraOpSpinning(const OrtApi* api, OrtSessionOptions* options,
                                     const char* overrideValue, std::string* error) {
    if (!api || !api->AddSessionConfigEntry || !api->GetErrorMessage ||
        !api->ReleaseStatus || !options) {
        if (error) *error = "ORT session-config API is unavailable";
        return false;
    }
    const char* value = overrideValue && std::strcmp(overrideValue, "1") == 0 ? "1" : "0";
    OrtStatus* status = api->AddSessionConfigEntry(
        options, "session.intra_op.allow_spinning", value);
    if (status) {
        const char* cause = api->GetErrorMessage(status);
        if (error) *error = std::string("Marian ORT spin configuration failed: ") +
            (cause ? cause : "unknown ORT error");
        api->ReleaseStatus(status);
        return false;
    }
    return true;
}

} // namespace stt::marian

#endif // HEARTH_MARIAN_SESSION_CONFIG_H
