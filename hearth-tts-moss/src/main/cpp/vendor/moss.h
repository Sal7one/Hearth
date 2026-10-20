#pragma once
#include <stddef.h>
#if defined(__GNUC__)
#define MOSS_API __attribute__((visibility("default")))
#else
#define MOSS_API
#endif
#ifdef __cplusplus
extern "C" {
#endif
typedef struct hearth_moss hearth_moss;
typedef int (*hearth_moss_pcm)(const float*, size_t, int, void*);
/* Status 0 success, 1 explicit cancellation, 2 real failure. Errors are UTF-8, thread-local. */
MOSS_API hearth_moss* hearth_moss_create(const char* directory, int threads);
MOSS_API void hearth_moss_destroy(hearth_moss*);
MOSS_API void hearth_moss_cancel(hearth_moss*);
MOSS_API int hearth_moss_voice_count(const hearth_moss*);
MOSS_API const char* hearth_moss_voice_name(const hearth_moss*, int);
MOSS_API int hearth_moss_synthesize(hearth_moss*, const char* text, const char* voice, hearth_moss_pcm, void*);
MOSS_API const char* hearth_moss_last_error(void);
#ifdef __cplusplus
}
#endif
