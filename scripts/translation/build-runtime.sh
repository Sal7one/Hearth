#!/usr/bin/env bash
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$REPO/build/translation-runtime"
PIN=64e9bceb2c3a856efed96feda784a50947049feb
PLATFORM="${1:-android}"
if [ ! -d "$WORK/llama.cpp/.git" ]; then git clone https://github.com/ggml-org/llama.cpp.git "$WORK/llama.cpp"; fi
if ! git -C "$WORK/llama.cpp" cat-file -e "$PIN^{commit}"; then git -C "$WORK/llama.cpp" fetch --depth 1 origin "$PIN"; fi
git -C "$WORK/llama.cpp" checkout --detach "$PIN"
FLAGS=(-DCMAKE_BUILD_TYPE=Release -DLLAMA_SOURCE="$WORK/llama.cpp" -DCPP="$REPO/common-jni/src/main/cpp")
if [ "$PLATFORM" = android ]; then
 NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
 FLAGS+=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)
elif [ "$PLATFORM" = host ]; then
 if [[ "$(uname -s)" == "Darwin" ]] && command -v xcrun >/dev/null 2>&1 && xcrun --find metal >/dev/null 2>&1; then
  FLAGS+=(-DHEARTH_HOST_METAL=ON)
  echo 'Building host translation runtime with Metal.'
 else
  FLAGS+=(-DHEARTH_HOST_METAL=OFF)
  echo 'Metal compiler unavailable; building the host translation runtime for CPU.'
 fi
else echo 'Expected android or host' >&2; exit 2; fi
cmake -S "$REPO/scripts/translation" -B "$WORK/$PLATFORM" "${FLAGS[@]}"
cmake --build "$WORK/$PLATFORM" -j 4
