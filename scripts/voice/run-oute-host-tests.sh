#!/usr/bin/env bash
# Offline production sampling-policy coverage; no model or optional runtime required.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$root/build/oute-host-tests"
mkdir -p "$work"
"${CXX:-c++}" -std=c++20 -fsanitize=address,undefined -fno-omit-frame-pointer "$root/scripts/voice/oute/sampling-test.cpp" -o "$work/sampling-test"
"$work/sampling-test"
bash "$root/scripts/voice/run-oute-jni-contract-test.sh"
