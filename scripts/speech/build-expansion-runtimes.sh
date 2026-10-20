#!/usr/bin/env bash
# Builds the real pinned Cohere/Fun-ASR/Voxtral native runtime, never model weights.
# Usage: build-expansion-runtimes.sh [android|host] [cpu|vulkan]
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${HEARTH_SPEECH_EXPANSION_BUILD_DIR:-$REPO/build/speech-expansion}"
PLATFORM="${1:-android}"
COMPUTE="${2:-cpu}"
case "$PLATFORM" in android|host) ;; *) echo 'Expected android or host' >&2; exit 2;; esac
case "$COMPUTE" in cpu|vulkan) ;; *) echo 'Expected cpu or vulkan' >&2; exit 2;; esac
if [ "$PLATFORM" != android ] && [ "$COMPUTE" != cpu ]; then echo 'Vulkan publication is Android ARM64 only' >&2; exit 2; fi
PIN="$(sed -n 's/^#define HEARTH_TRANSCRIBE_CPP_REVISION "\(.*\)"/\1/p' "$REPO/common-jni/src/main/cpp/speech/expansion/versions.h")"
SOURCE="${HEARTH_TRANSCRIBE_SOURCE:-$WORK/src/transcribe.cpp}"
if [ ! -d "$SOURCE/.git" ]; then git clone --filter=blob:none --no-checkout https://github.com/handy-computer/transcribe.cpp.git "$SOURCE"; fi
if ! git -C "$SOURCE" cat-file -e "$PIN^{commit}"; then git -C "$SOURCE" fetch --depth 1 origin "$PIN"; fi
git -C "$SOURCE" checkout --detach "$PIN"
test "$(git -C "$SOURCE" rev-parse HEAD)" = "$PIN"
test -z "$(git -C "$SOURCE" status --porcelain --untracked-files=no)"
OUTPUT="$WORK/$PLATFORM-$COMPUTE"
mkdir -p "$WORK/project" "$OUTPUT/lib"
cp "$REPO/scripts/speech/transcribe-cpp.cmake" "$WORK/project/CMakeLists.txt"
python3 "$REPO/scripts/speech/expansion-runtime-sources.py" > "$OUTPUT/build-sources.pending.json"
FLAGS=(-G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.10
  -DTRANSCRIBE_SOURCE="$SOURCE" -DHEARTH_CPP="$REPO/common-jni/src/main/cpp" -DHEARTH_SPEECH_ACCELERATION="$COMPUTE")
if [ "$PLATFORM" = android ]; then
  NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
  FLAGS+=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a
    -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)
fi
if [ "$COMPUTE" = vulkan ]; then
  DEPS="$REPO/build/translation-runtime"
  test "$(git -C "$DEPS/Vulkan-Headers" rev-parse HEAD)" = 19725e4d48082fe78e26622b15d3080ccd54112b
  test "$(git -C "$DEPS/SPIRV-Headers" rev-parse HEAD)" = 01e0577914a75a2569c846778c2f93aa8e6feddd
  GLSLC="${HEARTH_GLSLC:-$DEPS/shaderc-bottle/shaderc/2026.4/bin/glslc}"
  test -x "$GLSLC"
  FLAGS+=(-DVulkan_INCLUDE_DIR="$DEPS/Vulkan-Headers/include" -DVulkan_GLSLC_EXECUTABLE="$GLSLC"
    -DHEARTH_SPIRV_HEADERS="$DEPS/SPIRV-Headers/include"
    -DSPIRV-Headers_DIR="$DEPS/vulkan-deps/share/cmake/SPIRV-Headers")
fi
cmake -S "$WORK/project" -B "$OUTPUT/cmake" "${FLAGS[@]}"
TARGET=hearth_transcribe_cpp
if [ "$COMPUTE" != cpu ]; then TARGET="${TARGET}_$COMPUTE"; fi
cmake --build "$OUTPUT/cmake" --target "$TARGET" -j "${HEARTH_BUILD_JOBS:-4}"
if [ "$PLATFORM" = android ]; then
  case "$(uname -s)" in Darwin) HOST=darwin-x86_64;; Linux) HOST=linux-x86_64;; *) exit 2;; esac
  LIB="$(find "$OUTPUT/cmake" -name "lib$TARGET.so" -type f | head -1)"
  test -n "$LIB"
  "$NDK/toolchains/llvm/prebuilt/$HOST/bin/llvm-strip" --strip-unneeded "$LIB"
else LIB="$(find "$OUTPUT/cmake" -name "lib$TARGET.dylib" -o -name "lib$TARGET.so" | head -1)"; fi
cp "$LIB" "$OUTPUT/lib/"
python3 "$REPO/scripts/speech/expansion-runtime-sources.py" > "$OUTPUT/build-sources.final.json"
cmp "$OUTPUT/build-sources.pending.json" "$OUTPUT/build-sources.final.json"
mv "$OUTPUT/build-sources.final.json" "$OUTPUT/build-sources.json"
rm "$OUTPUT/build-sources.pending.json"
printf 'Built %s\n' "$OUTPUT/lib/$(basename "$LIB")"
