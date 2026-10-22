#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
build_dir="$root/build/desktop-media"
arch="$(uname -m)"
case "$arch" in arm64|aarch64) arch=arm64 ;; x86_64) arch=x64 ;; *) echo 'Unsupported macOS architecture' >&2; exit 1 ;; esac
cmake -S "$root/desktop-media/src/main/cpp" -B "$build_dir" -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_DEPLOYMENT_TARGET=13.3
cmake --build "$build_dir" --parallel 2
ctest --test-dir "$build_dir" --output-on-failure
stage="$root/desktop-media/native-libs/macos-$arch"
mkdir -p "$stage"
cp "$build_dir/hearth-media-decode" "$build_dir/hearth-keychain" "$build_dir/hearth-system-audio" "$stage/"
# Probe loads the actual framework/executable without enumerating content, requesting permission or capturing audio.
"$stage/hearth-system-audio" --probe
