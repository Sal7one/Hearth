#!/usr/bin/env bash
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
INCLUDE="${HEARTH_TRANSCRIBE_INCLUDE:-$REPO/common-jni/src/main/cpp/speech/tests/fixtures/transcribe_cpp/include}"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
CPP="$REPO/common-jni/src/main/cpp"
# Apple clang's older ASan runtime deadlocks during dyld startup on macOS 26.5.
# Prefer the installed current LLVM sanitizer; callers may still override CXX.
if [ -z "${CXX:-}" ] && [ -x /opt/homebrew/opt/llvm/bin/clang++ ]; then CXX=/opt/homebrew/opt/llvm/bin/clang++; fi
for MODE in cpu vulkan; do
  "${CXX:-c++}" -std=c++17 -O1 -g -Wall -Wextra -fsanitize=address,undefined \
    -DHEARTH_TRANSCRIBE_ACCELERATION=\"$MODE\" -I"$INCLUDE" \
    "$CPP/speech/tests/transcribe_cpp_backend_test.cpp" "$CPP/speech/speech_session.cpp" \
    "$CPP/common/json_utils.cpp" "$CPP/common/engine_interface.cpp" -pthread -ldl -o "$BUILD/test_$MODE"
  "$BUILD/test_$MODE"
done
