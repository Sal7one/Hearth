/* A minimal C caller for the Ming C ABI: synthesize one sentence to WAV. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "ming.h"

static double now(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

int main(int argc, char **argv) {
  if (argc < 4) {
    fprintf(stderr, "usage: %s <bundle-dir> <text> <out.wav> [voice] [preset] [threads]\n",
            argv[0]);
    return 2;
  }
  const char *bundle = argv[1];
  const char *text = argv[2];
  const char *out = argv[3];
  const char *voice = argc > 4 ? argv[4] : "US_James";
  int preset = argc > 5 ? atoi(argv[5]) : 0;
  int threads = argc > 6 ? atoi(argv[6]) : 8;

  char err[2048] = {0};
  MingStats stats;
  memset(&stats, 0, sizeof stats);

  printf("abi=%u model=%s\n", ming_abi_version(), ming_model_name());
  fflush(stdout);

  double start = now();
  int rc = ming_synthesize_wav(bundle, text, voice, preset, 1895, threads, out, &stats, err,
                               sizeof err);
  double wall = now() - start;

  if (rc != 0) {
    fprintf(stderr, "ming_synthesize_wav failed rc=%d: %s\n", rc, err);
    return 1;
  }
  char json[1024];
  if (ming_stats_json(&stats, json, sizeof json) != 0) {
    fprintf(stderr, "ming_stats_json failed\n");
    return 1;
  }
  printf("wall=%.2fs %s\n", wall, json);
  return 0;
}
