#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
for test in ocr_test manga_ocr_input_test manga_line_geometry_test; do
    "${CXX:-c++}" -std=c++17 -Wall -Wextra -fsanitize=address,undefined "$ROOT/$test.cpp" -o "$WORK/$test"
    "$WORK/$test"
done
