#ifndef HEARTH_WHISPER_RUNTIME_API_H
#define HEARTH_WHISPER_RUNTIME_API_H

#include "whisper.h"
#include <cstdint>
#include <cstddef>
#include <cstring>

// Contexts, states, and allocations never cross runtimes. The optional DSO
// statically links its own hidden GGML and exports only this versioned table.
// Exact vendored source tree; no upstream Git revision was recorded.
#define HEARTH_WHISPER_REVISION "5f3464bc06c04426d66eae69e5e510d32fb76621b524c42ab25b4294f035a920"
#define HEARTH_WHISPER_FUNCTIONS(X) \
    X(whisper_context_default_params) X(whisper_full_default_params) \
    X(whisper_init_with_params) X(whisper_free) X(whisper_log_set) \
    X(whisper_full) X(whisper_full_n_segments) X(whisper_full_lang_id) \
    X(whisper_full_get_segment_text) X(whisper_full_get_segment_t0) \
    X(whisper_full_get_segment_t1) X(whisper_full_n_tokens) \
    X(whisper_full_get_token_data) X(whisper_lang_id) X(whisper_lang_max_id) \
    X(whisper_lang_str) X(whisper_model_type) X(whisper_pcm_to_mel) \
    X(whisper_lang_auto_detect)

struct HearthWhisperApi {
    uint32_t version;
    uint32_t bytes;
    const char* revision;
#define HEARTH_WHISPER_DECLARE(name) decltype(&name) name;
    HEARTH_WHISPER_FUNCTIONS(HEARTH_WHISPER_DECLARE)
#undef HEARTH_WHISPER_DECLARE
    // Throws on absent/unusable GPU; never substitutes the CPU runtime.
    const char* (*deviceDescription)();
};

inline bool hearthWhisperApiValid(const HearthWhisperApi* api) {
    if (!api || api->version != 1 || api->bytes != sizeof(HearthWhisperApi) ||
        !api->revision || std::strcmp(api->revision, HEARTH_WHISPER_REVISION) ||
        !api->deviceDescription) return false;
#define HEARTH_WHISPER_CHECK(name) if (!api->name) return false;
    HEARTH_WHISPER_FUNCTIONS(HEARTH_WHISPER_CHECK)
#undef HEARTH_WHISPER_CHECK
    return true;
}

inline HearthWhisperApi hearthWhisperCpuApi() {
    return {1, sizeof(HearthWhisperApi), HEARTH_WHISPER_REVISION,
#define HEARTH_WHISPER_ADDRESS(name) &name,
        HEARTH_WHISPER_FUNCTIONS(HEARTH_WHISPER_ADDRESS)
#undef HEARTH_WHISPER_ADDRESS
        []() -> const char* { return "CPU"; }};
}

#endif
