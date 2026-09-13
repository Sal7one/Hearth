#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
"${CXX:-c++}" -std=c++17 -Wall -Wextra -fsanitize=address,undefined "$ROOT/ocr_test.cpp" -o "$WORK/ocr-test"
"$WORK/ocr-test"
