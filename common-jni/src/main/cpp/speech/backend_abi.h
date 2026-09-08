#ifndef HEARTH_SPEECH_BACKEND_ABI_H
#define HEARTH_SPEECH_BACKEND_ABI_H
#include <stddef.h>
#include <stdint.h>

// In-process ABI. No C++ objects or allocations cross the DSO boundary.
// All operations on one instance are serialized by SpeechNativeSession.
// Audio is borrowed for push only. Result strings are borrowed until next/reset/destroy.
// 0 = success, nonzero = failure; copy last_error immediately, on the same thread.
#ifdef __cplusplus
extern "C" {
#endif
#define HEARTH_SPEECH_ABI_VERSION 1
struct HearthSpeechConfig {
    uint32_t size;
    const char *model, *conv_frontend, *encoder, *decoder, *tokenizer, *language;
    int32_t num_threads, right_context, max_utterance_ms, silence_ms;
    float silence_threshold_db;
};
struct HearthSpeechResult {
    uint32_t size;
    const char *text, *language;
    int32_t is_final;
    int64_t audio_end_samples; // audio position, NOT word-alignment timestamps
};
struct HearthSpeechBackend {
    uint32_t abi_version, size;
    const char *id, *revision;
    int (*create)(const struct HearthSpeechConfig *, void **);
    void (*destroy)(void *);
    int (*push)(void *, const float *, size_t);
    int (*next)(void *, struct HearthSpeechResult *, int *available);
    int (*finish)(void *);
    int (*reset)(void *);
    const char *(*last_error)(void);
};
typedef const struct HearthSpeechBackend *(*HearthSpeechEntry)(void);
#ifdef __cplusplus
}
#endif
#endif
