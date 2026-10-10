#!/usr/bin/env bash
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$REPO/build/translation-runtime"
PIN=64e9bceb2c3a856efed96feda784a50947049feb
PLATFORM="${1:-android}"
ABI="${2:-arm64-v8a}"
case "$ABI" in arm64-v8a|armeabi-v7a|x86|x86_64) ;; *) echo 'Unsupported Android ABI' >&2; exit 2;; esac
OUTPUT="$PLATFORM"
if [ "$PLATFORM" = android ] && [ "$ABI" != arm64-v8a ]; then OUTPUT="android-$ABI"; fi
if [ ! -d "$WORK/llama.cpp/.git" ]; then git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git "$WORK/llama.cpp"; fi
if ! git -C "$WORK/llama.cpp" cat-file -e "$PIN^{commit}"; then git -C "$WORK/llama.cpp" fetch --depth 1 origin "$PIN"; fi
git -C "$WORK/llama.cpp" checkout --detach "$PIN"
FLAGS=(-DCMAKE_BUILD_TYPE=Release -DLLAMA_SOURCE="$WORK/llama.cpp" -DCPP="$REPO/common-jni/src/main/cpp")
if [ "$PLATFORM" = android ]; then
 NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
 FLAGS+=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)
 if [[ "$ABI" == x86* ]]; then FLAGS+=(-DGGML_AVX=OFF -DGGML_AVX2=OFF -DGGML_FMA=OFF -DGGML_F16C=OFF); fi
 if [ "$ABI" = armeabi-v7a ]; then FLAGS+=(-DGGML_LLAMAFILE=OFF); fi
 if [ "$ABI" = x86 ]; then FLAGS+=(-DGGML_SSE42=OFF); fi
elif [ "$PLATFORM" = host ]; then
 if [[ "$(uname -s)" == "Darwin" ]] && command -v xcrun >/dev/null 2>&1 && xcrun --find metal >/dev/null 2>&1; then
  FLAGS+=(-DHEARTH_HOST_METAL=ON)
  echo 'Building host translation runtime with Metal.'
 else
  FLAGS+=(-DHEARTH_HOST_METAL=OFF)
  echo 'Metal compiler unavailable; building the host translation runtime for CPU.'
 fi
else echo 'Expected android or host' >&2; exit 2; fi
cmake -S "$REPO/scripts/translation" -B "$WORK/$OUTPUT" "${FLAGS[@]}"
cmake --build "$WORK/$OUTPUT" -j "${HEARTH_BUILD_JOBS:-4}"
