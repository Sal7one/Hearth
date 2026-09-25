#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -fsanitize=address,undefined \
    "$ROOT/sign_test.cpp" "$ROOT/../hand_geometry.cpp" "$ROOT/../hand_landmarks.cpp" \
    "$ROOT/../ort_session.cpp" "$ROOT/../sign_classifier.cpp" "$ROOT/ort_stub.cpp" \
    -I"$ROOT/.." -I"$ROOT/../../onnx" -I"$ROOT/../../onnx/include" \
    -o "$WORK/sign-test"
"$WORK/sign-test"
