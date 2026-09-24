#!/usr/bin/env bash
set -euo pipefail
MARIAN="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
"${CXX:-c++}" -std=c++17 -O1 -g -Wall -Wextra -fsanitize=address,undefined \
  "$MARIAN/tests/marian_tokenizer_test.cpp" "$MARIAN/marian_tokenizer.cpp" \
  -o "$BUILD/marian_tokenizer_test"
"$BUILD/marian_tokenizer_test"
