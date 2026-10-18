#!/usr/bin/env bash
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$REPO/build/translation-runtime"
PIN=64e9bceb2c3a856efed96feda784a50947049feb
PLATFORM="${1:-android}"
ABI="${2:-arm64-v8a}"
BACKEND="${3:-cpu}"
case "$BACKEND" in cpu|vulkan|opencl) ;; *) echo 'Expected cpu, vulkan or opencl backend' >&2; exit 2;; esac
if [ "$BACKEND" != cpu ] && { [ "$PLATFORM" != android ] || [ "$ABI" != arm64-v8a ]; }; then
 echo 'The packaged GPU runtimes require android arm64-v8a' >&2; exit 2
fi
case "$ABI" in arm64-v8a|armeabi-v7a|x86|x86_64) ;; *) echo 'Unsupported Android ABI' >&2; exit 2;; esac
OUTPUT="$PLATFORM"
if [ "$PLATFORM" = android ] && [ "$ABI" != arm64-v8a ]; then OUTPUT="android-$ABI"; fi
if [ "$BACKEND" = vulkan ]; then OUTPUT="android-vulkan-arm64-v8a"; fi
if [ "$BACKEND" = opencl ]; then OUTPUT="android-opencl-arm64-v8a"; fi
if [ ! -d "$WORK/llama.cpp/.git" ]; then git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git "$WORK/llama.cpp"; fi
if ! git -C "$WORK/llama.cpp" cat-file -e "$PIN^{commit}"; then git -C "$WORK/llama.cpp" fetch --depth 1 origin "$PIN"; fi
git -C "$WORK/llama.cpp" checkout --detach "$PIN"
FLAGS=(-DCMAKE_BUILD_TYPE=Release -DLLAMA_SOURCE="$WORK/llama.cpp" -DCPP="$REPO/common-jni/src/main/cpp")
if [ "$PLATFORM" = android ]; then
 NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
 FLAGS+=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)
 FLAGS+=(-DHEARTH_ANDROID_VULKAN=OFF -DHEARTH_ANDROID_OPENCL=OFF)
 if [ "$BACKEND" = opencl ]; then
  dependency=OpenCL-Headers
  dependency_pin=30bc20a8e90468e231d7c639805ae61ad1fefa4f
  if [ ! -d "$WORK/$dependency/.git" ]; then
   git clone --filter=blob:none --no-checkout "https://github.com/KhronosGroup/$dependency.git" "$WORK/$dependency"
  fi
  if ! git -C "$WORK/$dependency" cat-file -e "$dependency_pin^{commit}"; then
   git -C "$WORK/$dependency" fetch --depth 1 origin "$dependency_pin"
  fi
  git -C "$WORK/$dependency" checkout --detach "$dependency_pin"
  FLAGS+=(-DHEARTH_ANDROID_OPENCL=ON -DHEARTH_OPENCL_HEADERS="$WORK/$dependency")
 fi
 if [ "$BACKEND" = vulkan ]; then
  # Headers are build inputs only, pinned separately from the unchanged llama
  # revision. Generated shaders are compiled into the optional private DSO.
  for dependency in Vulkan-Headers SPIRV-Headers; do
   case "$dependency" in
    Vulkan-Headers) dependency_pin=19725e4d48082fe78e26622b15d3080ccd54112b;;
    SPIRV-Headers) dependency_pin=01e0577914a75a2569c846778c2f93aa8e6feddd;;
   esac
   if [ ! -d "$WORK/$dependency/.git" ]; then
    git clone --filter=blob:none --no-checkout "https://github.com/KhronosGroup/$dependency.git" "$WORK/$dependency"
   fi
   if ! git -C "$WORK/$dependency" cat-file -e "$dependency_pin^{commit}"; then
    git -C "$WORK/$dependency" fetch --depth 1 origin "$dependency_pin"
   fi
   git -C "$WORK/$dependency" checkout --detach "$dependency_pin"
  done
  cmake -S "$WORK/SPIRV-Headers" -B "$WORK/spirv-headers-build" -DSPIRV_HEADERS_ENABLE_TESTS=OFF -DCMAKE_INSTALL_PREFIX="$WORK/vulkan-deps"
  cmake --install "$WORK/spirv-headers-build"
  GLSLC="${HEARTH_GLSLC:-$NDK/shader-tools/darwin-x86_64/glslc}"
  if [ ! -x "$GLSLC" ]; then echo 'Set HEARTH_GLSLC to a host glslc executable' >&2; exit 2; fi
  FLAGS+=(-DHEARTH_ANDROID_VULKAN=ON -DVulkan_INCLUDE_DIR="$WORK/Vulkan-Headers/include" -DVulkan_GLSLC_EXECUTABLE="$GLSLC" "-DSPIRV-Headers_DIR=$WORK/vulkan-deps/share/cmake/SPIRV-Headers")
 fi
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
