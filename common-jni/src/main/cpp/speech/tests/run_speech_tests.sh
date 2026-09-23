#!/usr/bin/env bash
set -euo pipefail
SPEECH="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
"${CXX:-c++}" -std=c++17 -O1 -g -Wall -Wextra -fsanitize=address,undefined \
  "$SPEECH/tests/speech_test.cpp" "$SPEECH/speech_session.cpp" \
  "$SPEECH/../common/json_utils.cpp" "$SPEECH/../common/engine_interface.cpp" \
  -pthread -ldl -o "$BUILD/speech_test"
"$BUILD/speech_test"
"${CXX:-c++}" -std=c++17 -O1 -g -Wall -Wextra -fsanitize=address,undefined \
  "$SPEECH/tests/segmenter_allocation_test.cpp" -pthread -o "$BUILD/segmenter_allocation_test"
"$BUILD/segmenter_allocation_test"
