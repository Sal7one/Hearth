#!/usr/bin/env bash
# Exercise the production Marian engine with an already verified external package.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
[ "$#" -eq 2 ] || { echo 'Usage: run-marian-host-smoke.sh MODEL_DIRECTORY SOURCE_TEXT' >&2; exit 2; }
CPP="$REPO/common-jni/src/main/cpp"
WORK="${HEARTH_SPEECH_BUILD_DIR:-$REPO/build/speech-runtimes}"
ORT="${HEARTH_HOST_ORT_LIBRARY:-$WORK/host/qwen/_deps/onnxruntime-src/lib/libonnxruntime.a}"
[ -f "$ORT" ] || { echo "Missing host ONNX Runtime: $ORT. Build scripts/speech/build-runtimes.sh qwen host first." >&2; exit 1; }
LINK=()
if [ "$(uname -s)" = Darwin ]; then LINK=(-framework Foundation -framework CoreML -framework Accelerate); else LINK=(-ldl); fi
mkdir -p "$WORK/host"
"${CXX:-c++}" -std=c++17 -O2 -pthread \
  "$CPP/marian/tests/marian_smoke.cpp" "$CPP/marian/marian_engine.cpp" "$CPP/marian/marian_tokenizer.cpp" \
  -I"$CPP/marian" -I"$CPP/onnx/include" "$ORT" "${LINK[@]}" -o "$WORK/host/marian_smoke"
"$WORK/host/marian_smoke" "$1" "$2"
