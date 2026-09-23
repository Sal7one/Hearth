#!/usr/bin/env bash
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
if [[ "$(uname -s)" != "Darwin" ]]; then
  echo 'The Whisper host benchmark currently targets macOS.' >&2
  exit 2
fi
BUILD="$REPO/build/benchmark-runtime/whisper"
cmake -S "$REPO/scripts/benchmark/whisper-host" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release
cmake --build "$BUILD" -j 4
