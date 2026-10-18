#!/usr/bin/env bash
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$REPO/build/whisper-runtime"
SOURCE="$REPO/common-jni/src/main/cpp/whisper/upstream"
PLATFORM="${1:-android}"
python3 "$REPO/scripts/whisper/source-pin.py" "$SOURCE" "$REPO/common-jni/src/main/cpp/whisper/whisper_runtime_api.h"
# Inputs are shared with translation, but build directories never are. The
# dependency bootstrap is deliberately explicit and SHA-pinned.
DEPS="$REPO/build/translation-runtime"
for item in Vulkan-Headers SPIRV-Headers; do
 case "$item" in
  Vulkan-Headers) dependency_pin=19725e4d48082fe78e26622b15d3080ccd54112b;;
  SPIRV-Headers) dependency_pin=01e0577914a75a2569c846778c2f93aa8e6feddd;;
 esac
 if [ ! -d "$DEPS/$item/.git" ]; then
  git clone --filter=blob:none --no-checkout "https://github.com/KhronosGroup/$item.git" "$DEPS/$item"
 fi
 if ! git -C "$DEPS/$item" cat-file -e "$dependency_pin^{commit}"; then
  git -C "$DEPS/$item" fetch --depth 1 origin "$dependency_pin"
 fi
 if [ "$(git -C "$DEPS/$item" rev-parse HEAD 2>/dev/null || true)" != "$dependency_pin" ]; then
  git -C "$DEPS/$item" checkout --detach "$dependency_pin"
 fi
done
cmake -S "$DEPS/SPIRV-Headers" -B "$DEPS/spirv-headers-build" -DSPIRV_HEADERS_ENABLE_TESTS=OFF -DCMAKE_INSTALL_PREFIX="$DEPS/vulkan-deps"
cmake --install "$DEPS/spirv-headers-build"
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
GLSLC="${HEARTH_GLSLC:-$NDK/shader-tools/darwin-x86_64/glslc}"
if [ ! -x "$GLSLC" ]; then echo 'Set HEARTH_GLSLC to a modern host glslc' >&2; exit 2; fi
python3 "$REPO/scripts/whisper/prepare-source.py" "$SOURCE/src/whisper.cpp" "$WORK/whisper-gpu.cpp"
FLAGS=(-DCMAKE_BUILD_TYPE=Release -DWHISPER_ROOT="$SOURCE" -DCPP="$REPO/common-jni/src/main/cpp"
 -DPATCHED_WHISPER="$WORK/whisper-gpu.cpp" -DVulkan_INCLUDE_DIR="$DEPS/Vulkan-Headers/include"
 -DVulkan_GLSLC_EXECUTABLE="$GLSLC" "-DSPIRV-Headers_DIR=$DEPS/vulkan-deps/share/cmake/SPIRV-Headers")
if [ "$PLATFORM" = android ]; then
 FLAGS+=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)
elif [ "$PLATFORM" = host ]; then
 if [ -z "${HEARTH_HOST_VULKAN_LIBRARY:-}" ]; then echo 'Set HEARTH_HOST_VULKAN_LIBRARY to a Vulkan loader or MoltenVK dylib' >&2; exit 2; fi
 FLAGS+=(-DVulkan_LIBRARY="$HEARTH_HOST_VULKAN_LIBRARY")
else echo 'Expected android or host' >&2; exit 2; fi
cmake -S "$REPO/scripts/whisper" -B "$WORK/$PLATFORM" "${FLAGS[@]}"
cmake --build "$WORK/$PLATFORM" -j "${HEARTH_BUILD_JOBS:-3}"
