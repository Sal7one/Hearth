#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
"${CXX:-c++}" -std=c++17 -Wall -Wextra -fsanitize=address,undefined "$ROOT/voice_test.cpp" -o "$WORK/voice-test"
"$WORK/voice-test"
