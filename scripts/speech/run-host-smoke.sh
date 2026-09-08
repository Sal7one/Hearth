#!/usr/bin/env bash
# Run a real model through the same backend + session used by JNI. No device use.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${HEARTH_SPEECH_BUILD_DIR:-$REPO/build/speech-runtimes}"
CPP="$REPO/common-jni/src/main/cpp"
[ "$#" -ge 2 ] || { echo 'Usage: run-host-smoke.sh PACKAGE_DIR AUDIO_FILE [SOURCE_LANGUAGE]' >&2; exit 2; }
mkdir -p "$WORK/host"
"${CXX:-c++}" -std=c++17 -O2 "$CPP/speech/tests/speech_smoke.cpp" "$CPP/speech/speech_session.cpp" \
  "$CPP/common/json_utils.cpp" "$CPP/common/engine_interface.cpp" -pthread -ldl -o "$WORK/host/speech_smoke"
PCM="$(mktemp)"
trap 'rm -f "$PCM"' EXIT
ffmpeg -v error -y -i "$2" -ac 1 -ar 16000 -f s16le "$PCM"
DYLD_LIBRARY_PATH="$WORK/host/lib${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}" \
LD_LIBRARY_PATH="$WORK/host/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  "$WORK/host/speech_smoke" "$1" "$PCM" "${3:-auto}"
