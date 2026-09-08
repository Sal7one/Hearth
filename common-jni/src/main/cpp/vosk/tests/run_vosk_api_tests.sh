#!/usr/bin/env bash
set -euo pipefail
TESTS="$(cd "$(dirname "$0")" && pwd)"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
"${CXX:-c++}" -std=c++17 -shared -fPIC "$TESTS/fixture.cpp" -o "$BUILD/fixture.so"
printf 'extern "C" void unrelated() {}\n' > "$BUILD/empty.cpp"
"${CXX:-c++}" -shared -fPIC "$BUILD/empty.cpp" -o "$BUILD/empty.so"
"${CXX:-c++}" -std=c++17 -Wall -Wextra "$TESTS/vosk_api_test.cpp" -ldl -o "$BUILD/test"
"$BUILD/test" "$BUILD/fixture.so" "$BUILD/empty.so"
