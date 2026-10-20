#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define HEARTH_OUTE_ABI 1
#define HEARTH_OUTE_API __attribute__((visibility("default")))
struct hearth_oute;
/* Runtime retains ownership; callback samples are valid only for the callback. */
typedef int (*hearth_oute_pcm)(void*, const float*, int, int);
HEARTH_OUTE_API int hearth_oute_abi(void);
HEARTH_OUTE_API const char* hearth_oute_last_error(void);
HEARTH_OUTE_API struct hearth_oute* hearth_oute_create(const char* combined_model,const char* speaker_profile,int threads);
HEARTH_OUTE_API int hearth_oute_synthesize(struct hearth_oute*,const char* utf8_text,hearth_oute_pcm,void*);
HEARTH_OUTE_API void hearth_oute_cancel(struct hearth_oute*);
HEARTH_OUTE_API void hearth_oute_destroy(struct hearth_oute*);
#ifdef __cplusplus
}
#endif
