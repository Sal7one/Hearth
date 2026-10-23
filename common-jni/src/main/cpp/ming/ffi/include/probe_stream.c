/* Exercises the streaming C ABI (load once, start, step, take_audio, write_wav). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ming.h"

int main(int argc, char **argv) {
  if (argc < 4) {
    fprintf(stderr, "usage: %s <bundle-dir> <text> <out.wav> [threads]\n", argv[0]);
    return 2;
  }
  char err[2048] = {0};
  MingEngine *engine = NULL;
  if (ming_engine_load(argv[1], argc > 4 ? atoi(argv[4]) : 8, &engine, err, sizeof err) != 0) {
    fprintf(stderr, "load: %s\n", err);
    return 1;
  }
  printf("loaded threads=%u\n", ming_engine_threads(engine));

  if (ming_engine_start(engine, argv[2], "US_James", 0, 1895, err, sizeof err) != 0) {
    fprintf(stderr, "start: %s\n", err);
    ming_engine_free(engine);
    return 1;
  }

  int done = 0;
  size_t total = 0;
  size_t calls = 0;
  while (!done) {
    if (ming_engine_step(engine, &done, err, sizeof err) != 0) {
      fprintf(stderr, "step: %s\n", err);
      ming_engine_free(engine);
      return 1;
    }
    float buffer[1024];
    size_t pending = ming_engine_pending(engine);
    while (pending > 0) {
      size_t got = ming_engine_take_audio(engine, buffer, 1024);
      if (got == 0) {
        break;
      }
      total += got;
      calls++;
      pending = ming_engine_pending(engine);
    }
  }

  if (ming_engine_write_wav(engine, argv[3], err, sizeof err) != 0) {
    fprintf(stderr, "wav: %s\n", err);
    ming_engine_free(engine);
    return 1;
  }
  MingStats stats;
  ming_engine_stats(engine, &stats);
  char json[1024];
  ming_stats_json(&stats, json, sizeof json);
  printf("streamed=%zu samples in %zu take calls, %s\n", total, calls, json);
  ming_engine_free(engine);
  return 0;
}
