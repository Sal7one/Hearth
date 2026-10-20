#!/usr/bin/env bash
# One narrow CPU adapter, host or Android ARM64. No weights or network clients.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
BACKEND="${1:?Expected owsm or audar}"
PLATFORM="${2:-android}"
case "$BACKEND" in owsm) SOURCE="${HEARTH_AUDIOCPP_SOURCE:-$REPO/research/audio.cpp}"; PIN=673ade451c9cba9c893fb0e606872d9dc44f21e3;; audar) SOURCE="${HEARTH_LLAMA_SOURCE:-$REPO/research/llama.cpp}"; PIN=64e9bceb2c3a856efed96feda784a50947049feb;; *) exit 2;; esac
case "$PLATFORM" in android|host) ;; *) exit 2;; esac
test "$(git -C "$SOURCE" rev-parse HEAD)" = "$PIN"
test -z "$(git -C "$SOURCE" status --porcelain --untracked-files=normal)"
WORK="${HEARTH_MODERN_SPEECH_BUILD:-$REPO/build/speech-modern}/$BACKEND"
OUTPUT="$WORK/$PLATFORM"
mkdir -p "$WORK/project" "$WORK/prepared" "$OUTPUT/lib"
cp "$REPO/scripts/speech/modern_cpu/$BACKEND.cmake" "$WORK/project/CMakeLists.txt"
python3 "$REPO/scripts/speech/modern_cpu/sources.py" "$BACKEND" > "$OUTPUT/sources.pending.json"
FLAGS=(-G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.10 -DHEARTH_CPP="$REPO/common-jni/src/main/cpp")
if [ "$BACKEND" = owsm ]; then
 python3 "$REPO/scripts/speech/modern_cpu/prepare-owsm.py" "$SOURCE" "$WORK/prepared"
 FLAGS+=(-DAUDIOCPP_SOURCE="$SOURCE" -DOWSM_PREPARED="$WORK/prepared")
else FLAGS+=(-DLLAMA_SOURCE="$SOURCE"); fi
if [ "$PLATFORM" = android ]; then
 NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
 FLAGS+=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)
fi
cmake -S "$WORK/project" -B "$OUTPUT/cmake" "${FLAGS[@]}"
cmake --build "$OUTPUT/cmake" --target "hearth_$BACKEND" -j "${HEARTH_BUILD_JOBS:-2}"
if [ "$PLATFORM" = android ]; then
 case "$(uname -s)" in Darwin) HOST=darwin-x86_64;; Linux) HOST=linux-x86_64;; *) exit 2;; esac
 LIB="$OUTPUT/cmake/libhearth_$BACKEND.so"
 "$NDK/toolchains/llvm/prebuilt/$HOST/bin/llvm-strip" --strip-unneeded "$LIB"
else LIB="$OUTPUT/cmake/libhearth_$BACKEND.dylib"; [ -f "$LIB" ] || LIB="$OUTPUT/cmake/libhearth_$BACKEND.so"; fi
cp "$LIB" "$OUTPUT/lib/"
python3 "$REPO/scripts/speech/modern_cpu/sources.py" "$BACKEND" > "$OUTPUT/sources.final.json"
cmp "$OUTPUT/sources.pending.json" "$OUTPUT/sources.final.json"
mv "$OUTPUT/sources.final.json" "$OUTPUT/build-sources.json"
rm "$OUTPUT/sources.pending.json"
printf 'Built %s\n' "$OUTPUT/lib/$(basename "$LIB")"
