#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)";work="$root/build/chatterbox-host-tests";mkdir -p "$work"
"${CXX:-c++}" -std=c++17 -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer "$root/scripts/voice/chatterbox/frontend-test.cpp" -I "$root/hearth-tts-chatterbox/src/main/cpp" "$root/build/ct2-runtime/rust/$(rustc -vV | awk '$1 == "host:" { print $2 }')/release/libhearth_byte_bpe.a" -o "$work/frontend-test"
ASAN_OPTIONS=detect_leaks=1 "$work/frontend-test" "$root/build/voice-runtimes/chatterbox-models/tokenizer.json"
