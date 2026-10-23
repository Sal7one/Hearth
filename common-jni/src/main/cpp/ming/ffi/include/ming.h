/* ming.h — C ABI over the Ming-3.0-tts-0.5B fusor-ai runtime.
 *
 * Experimental. One engine belongs to one thread at a time. Every function returns 0 on
 * success or a negative value on failure, filling `err` with a UTF-8 message (truncated).
 * The implementation is Rust (`ming-ffi`), built as a cdylib/staticlib for macOS arm64 and
 * Android arm64 from the same source.
 */
#ifndef MING_H
#define MING_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MING_ABI_VERSION 1u

typedef struct MingEngine MingEngine;

typedef struct MingStats {
  uint64_t frames;             /* 80 ms frames generated */
  uint64_t samples;            /* f32 samples of 44.1 kHz mono audio */
  double audio_seconds;
  double elapsed_seconds;
  double rtf;                  /* elapsed / audio */
  double first_audio_seconds;
  double load_seconds;
  uint32_t threads;            /* compute threads actually used */
  uint32_t sample_rate;        /* 44100 */
} MingStats;

uint32_t ming_abi_version(void);
const char *ming_model_name(void);

/* Load and BLAKE3-check a bundle directory (manifest.json + parts). `threads` <= 0 keeps the
 * current Rayon pool. `out` receives an engine that must be freed with ming_engine_free. */
int ming_engine_load(const char *bundle_dir, int threads, MingEngine **out,
                     char *err, size_t err_capacity);
void ming_engine_free(MingEngine *engine);
uint32_t ming_engine_threads(const MingEngine *engine);

/* Start one utterance. `voice` is a built-in preset ("US_James", "US_Emma", "UK_George", …);
 * NULL/"" means US_James. `preset`: 0 quality, 1 balanced, 2 fast. `seed` 0 = upstream
 * default (1895). */
int ming_engine_start(MingEngine *engine, const char *text, const char *voice,
                      int preset, uint64_t seed, char *err, size_t err_capacity);

/* Generate one 80 ms frame. `done` becomes 1 on the final frame. Audio is queued; drain it
 * with ming_engine_take_audio. */
int ming_engine_step(MingEngine *engine, int *done, char *err, size_t err_capacity);

/* Samples waiting. */
size_t ming_engine_pending(const MingEngine *engine);

/* Copy up to `capacity` f32 samples into `out` and remove them. With out==NULL it only
 * reports the pending count. Returns the number copied. */
size_t ming_engine_take_audio(MingEngine *engine, float *out, size_t capacity);

int ming_engine_stats(const MingEngine *engine, MingStats *stats);
int ming_engine_write_wav(const MingEngine *engine, const char *path,
                          char *err, size_t err_capacity);
int ming_stats_json(const MingStats *stats, char *buffer, size_t capacity);

/* One-shot blocking convenience path: load, synthesize, write 16-bit mono WAV. */
int ming_synthesize_wav(const char *bundle_dir, const char *text, const char *voice,
                        int preset, uint64_t seed, int threads, const char *out_wav,
                        MingStats *stats, char *err, size_t err_capacity);

#ifdef __cplusplus
}
#endif

#endif /* MING_H */
