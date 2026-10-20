#pragma once
#include <stddef.h>
#if defined(__GNUC__)
#define Chatterbox_API __attribute__((visibility("default")))
#else
#define Chatterbox_API
#endif
#ifdef __cplusplus
extern "C" {
#endif
typedef struct hearth_chatterbox hearth_chatterbox;
typedef int (*hearth_chatterbox_pcm)(const float*, size_t, int, void*);
/* Status 0 success, 1 explicit cancellation, 2 real failure. Errors are UTF-8, thread-local. */
Chatterbox_API hearth_chatterbox* hearth_chatterbox_create(const char* directory, int threads);
Chatterbox_API void hearth_chatterbox_destroy(hearth_chatterbox*);
Chatterbox_API void hearth_chatterbox_cancel(hearth_chatterbox*);
Chatterbox_API int hearth_chatterbox_voice_count(const hearth_chatterbox*);
Chatterbox_API const char* hearth_chatterbox_voice_name(const hearth_chatterbox*, int);
Chatterbox_API int hearth_chatterbox_synthesize(hearth_chatterbox*, const char* text, const char* language, const char* voice, hearth_chatterbox_pcm, void*);
Chatterbox_API const char* hearth_chatterbox_last_error(void);
#ifdef __cplusplus
}
#endif
