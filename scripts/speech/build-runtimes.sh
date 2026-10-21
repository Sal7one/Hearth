#!/usr/bin/env bash
# Explicit developer build; never run by Gradle or on an owner device.
# Usage: build-runtimes.sh [qwen|nemotron|transcribepsy|all] [android|host] [ABI] [cpu|vulkan|opencl]
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
SPEECH_CPP="$REPO/common-jni/src/main/cpp"
WORK="${HEARTH_SPEECH_BUILD_DIR:-$REPO/build/speech-runtimes}"
BACKEND="${1:-all}"
PLATFORM="${2:-android}"
ABI="${3:-arm64-v8a}"
ACCELERATION="${4:-cpu}"
case "$ACCELERATION" in cpu|vulkan|opencl) ;; *) echo 'Expected cpu, vulkan or opencl' >&2; exit 2;; esac
if [ "$ACCELERATION" != cpu ] && { [ "$BACKEND" != transcribepsy ] || { [ "$PLATFORM" = android ] && [ "$ABI" != arm64-v8a ]; }; }; then
  echo 'Optional speech GPU runtimes require TranscribePsy and Android arm64-v8a (or a developer host)' >&2; exit 2
fi
case "$ABI" in arm64-v8a|armeabi-v7a|x86|x86_64) ;; *) echo 'Unsupported Android ABI' >&2; exit 2;; esac
OUTPUT="$PLATFORM"
if [ "$PLATFORM" = android ] && [ "$ABI" != arm64-v8a ]; then OUTPUT="android-$ABI"; fi
if [ "$ACCELERATION" != cpu ]; then OUTPUT="$PLATFORM-$ACCELERATION-$ABI"; fi
JOBS="${HEARTH_BUILD_JOBS:-4}"
case "$BACKEND" in qwen|nemotron|transcribepsy|all) ;; *) echo 'Expected qwen, nemotron, transcribepsy or all' >&2; exit 2;; esac
case "$PLATFORM" in android|host) ;; *) echo 'Expected android or host' >&2; exit 2;; esac
mkdir -p "$WORK/src" "$WORK/$OUTPUT/lib"
# Publication requires all three plugins from one unchanged source snapshot.
# Partial developer builds remain useful for host inference, but cannot falsely
# attest that an existing sibling library was rebuilt with a new recipe.
if [ "$BACKEND" = all ] || [ "$ACCELERATION" != cpu ]; then
  python3 "$REPO/scripts/speech/runtime-sources.py" > "$WORK/$OUTPUT/build-sources.pending.json"
fi
revision() { sed -n "s/^#define $1 \"\(.*\)\"/\1/p" "$SPEECH_CPP/speech/backend_versions.h"; }
checkout() {
  local repo_url="$1" source_dir="$2" commit_id="$3"
  if [ ! -d "$source_dir/.git" ]; then git clone --filter=blob:none --no-checkout "$repo_url" "$source_dir"; fi
  if ! git -C "$source_dir" cat-file -e "$commit_id^{commit}" 2>/dev/null; then git -C "$source_dir" fetch --depth 1 origin "$commit_id"; fi
  # Sources under WORK are dedicated, disposable build checkouts.
  git -C "$source_dir" checkout --detach --force "$commit_id"
  test "$(git -C "$source_dir" rev-parse HEAD)" = "$commit_id"
}
NINJA_BIN="${NINJA:-$(command -v ninja || true)}"
if [ -z "$NINJA_BIN" ]; then NINJA_BIN="$HOME/Library/Android/sdk/cmake/3.22.1/bin/ninja"; fi
test -x "$NINJA_BIN"
FLAGS=(-DCMAKE_MAKE_PROGRAM="$NINJA_BIN" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.10)
if [ "$PLATFORM" = host ] && [ "$(uname -s)" = Darwin ]; then
  # The desktop app supports macOS 13.3; a host SDK default would emit minos 26.
  FLAGS+=(-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0)
fi
if [ "$PLATFORM" = android ]; then
  case "$(uname -s)" in Darwin) NDK_HOST=darwin-x86_64;; Linux) NDK_HOST=linux-x86_64;; *) echo 'Unsupported NDK host' >&2; exit 2;; esac
  NDK_PATH="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
  test -f "$NDK_PATH/build/cmake/android.toolchain.cmake"
  FLAGS+=(-DCMAKE_TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI"
    -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
    '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384')
fi
if [ "$PLATFORM" = android ] && [[ "$ABI" == x86* ]]; then
  FLAGS+=(-DGGML_AVX=OFF -DGGML_AVX2=OFF -DGGML_FMA=OFF -DGGML_F16C=OFF)
fi
if [ "$PLATFORM" = android ] && [ "$ABI" = armeabi-v7a ]; then
  FLAGS+=(-DGGML_LLAMAFILE=OFF)
fi
if [ "$PLATFORM" = android ] && [ "$ABI" = x86 ]; then FLAGS+=(-DGGML_SSE42=OFF); fi
# Strip only generated outputs before copying: ORT's unstripped DSO is ~700 MB.
# Copying it before strip duplicated hundreds of MB per ABI and dominated build
# publication on low-space filesystems. Fail if no output was produced/copied.
publish_libraries() {
  local candidate published=0
  for candidate in "$@"; do
    if [ ! -f "$candidate" ]; then continue; fi
    if [ "$PLATFORM" = android ]; then
      "$NDK_PATH/toolchains/llvm/prebuilt/$NDK_HOST/bin/llvm-strip" --strip-unneeded "$candidate"
    fi
    cp "$candidate" "$WORK/$OUTPUT/lib/"
    published=1
  done
  test "$published" = 1
}
if [ "$BACKEND" = qwen ] || [ "$BACKEND" = all ]; then
  checkout https://github.com/k2-fsa/sherpa-onnx.git "$WORK/src/sherpa" "$(revision HEARTH_QWEN_REVISION)"
  # The pinned Android static ORT archive has no NNAPI provider. This CPU-only
  # adapter must not compile sherpa's unconditional Android NNAPI references.
  python3 - "$WORK/src/sherpa/sherpa-onnx/csrc/session.cc" <<'PATCHPY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
assert s.count('#if __ANDROID_API__ >= 27') == 2
s = s.replace('#if __ANDROID_API__ >= 27', '#if __ANDROID_API__ >= 27 && !defined(HEARTH_SPEECH_CPU_ONLY)')
# Each encoder/decoder/joiner session owns ORT workers. Idle spin from those
# pools competes with the independently selected translation and TTS engines.
# Retain the configured worker budget; make idle workers sleep instead.
thread_anchor = '  sess_opts.SetInterOpNumThreads(num_threads);\n'
assert s.count(thread_anchor) == 1
s = s.replace(thread_anchor, thread_anchor + '''
#if defined(HEARTH_SPEECH_CPU_ONLY)
  sess_opts.AddConfigEntry("session.intra_op.allow_spinning", "0");
  sess_opts.AddConfigEntry("session.inter_op.allow_spinning", "0");
#endif
''')
p.write_text(s)
PATCHPY
  # Preserve the detected language before upstream strips the ASR scaffold.
  # Without this, the C API emits empty lang and downstream translation cannot route.
  python3 - "$WORK/src/sherpa/sherpa-onnx/csrc/offline-recognizer-qwen3-asr-impl.cc" <<'PATCHPY'
from pathlib import Path
import sys
p = Path(sys.argv[1]); s = p.read_text()
needle = '        cleaned_ids.assign(std::next(asr_text_it), generated_ids.end());'
assert s.count(needle) == 1
s = s.replace(needle, '        if (prefix_text.size() > 19) result.lang = prefix_text.substr(9, prefix_text.size() - 19);\n' + needle)
p.write_text(s)
PATCHPY
  mkdir -p "$WORK/qwen-project"
  cp "$REPO/scripts/speech/qwen.cmake" "$WORK/qwen-project/CMakeLists.txt"
  cmake -S "$WORK/qwen-project" -B "$WORK/$OUTPUT/qwen" "${FLAGS[@]}" \
    -DSHERPA_SOURCE="$WORK/src/sherpa" -DHEARTH_CPP="$SPEECH_CPP"
  cmake --build "$WORK/$OUTPUT/qwen" --target hearth_qwen -j "$JOBS"
  publish_libraries "$WORK/$OUTPUT/qwen/libhearth_qwen.so" "$WORK/$OUTPUT/qwen/libhearth_qwen.dylib"
fi
if [ "$BACKEND" = nemotron ] || [ "$BACKEND" = all ]; then
  checkout https://github.com/NVIDIA/NeMo-Speech.cpp.git "$WORK/src/nemo" "$(revision HEARTH_NEMO_REVISION)"
  git -C "$WORK/src/nemo" submodule update --init --depth 1 ggml
  checkout https://github.com/google/sentencepiece.git "$WORK/src/sentencepiece" "$(revision HEARTH_SENTENCEPIECE_REVISION)"
  cmake -S "$WORK/src/sentencepiece" -B "$WORK/$OUTPUT/sentencepiece" "${FLAGS[@]}" \
    -DSPM_ENABLE_SHARED=OFF -DSPM_BUILD_TEST=OFF -DSPM_ENABLE_TCMALLOC=OFF
  cmake --build "$WORK/$OUTPUT/sentencepiece" --target sentencepiece-static -j "$JOBS"
  # Upstream's shared C++ target exports STL/GGML. Embed it privately in our ABI
  # DSO instead; retain upstream source at the exact pin plus this small patch.
  python3 - "$WORK/src/nemo" "$SPEECH_CPP" <<'PY'
from pathlib import Path
import sys
root, cpp = map(Path, sys.argv[1:])
p = root / 'src/asr/CMakeLists.txt'
s = p.read_text()
needle = 'add_library(nemo_speech_asr SHARED ${ASR_SOURCES})'
assert needle in s
p.write_text(s.replace(needle, 'add_library(nemo_speech_asr STATIC ${ASR_SOURCES})'))
(root / 'src/asr/c_api.map').write_text((cpp / 'speech/backends/exports.map').read_text())
PY
  cmake -S "$WORK/src/nemo" -B "$WORK/$OUTPUT/nemo" "${FLAGS[@]}" \
    -DCMAKE_PROJECT_nemo_speech_INCLUDE="$REPO/scripts/speech/nemo-inject.cmake" -DHEARTH_CPP="$SPEECH_CPP" \
    -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_CPU_KLEIDIAI=OFF \
    -DGGML_METAL=OFF -DGGML_CUDA=OFF -DNEMO_SPEECH_GGML_PATCHED=OFF \
    -DNEMO_SPEECH_BUILD_ASR=ON -DNEMO_SPEECH_BUILD_DIAR=OFF -DNEMO_SPEECH_BUILD_TTS=OFF \
    -DNEMO_SPEECH_BUILD_NMT=OFF -DNEMO_SPEECH_BUILD_CLI=OFF -DNEMO_SPEECH_BUILD_MIC_CAPTURE=OFF \
    -DNEMO_SPEECH_BUILD_HTTP=OFF -DNEMO_SPEECH_BUILD_GRPC=OFF -DNEMO_SPEECH_BUILD_TESTS=OFF \
    -DNEMO_SPEECH_BUILD_EXAMPLES=OFF -DNEMO_SPEECH_BUILD_TOOLS=OFF \
    -DSENTENCEPIECE_STATIC_LIB="$WORK/$OUTPUT/sentencepiece/src/libsentencepiece.a" \
    -DSENTENCEPIECE_INCLUDE_DIR="$WORK/src/sentencepiece/src"
  cmake --build "$WORK/$OUTPUT/nemo" --target nemo_speech_asr_c -j "$JOBS"
  publish_libraries "$WORK/$OUTPUT/nemo/bin"/libhearth_nemotron*.so "$WORK/$OUTPUT/nemo/bin"/libhearth_nemotron*.dylib
fi
if [ "$BACKEND" = transcribepsy ] || [ "$BACKEND" = all ]; then
  checkout https://github.com/tether-ai-research/qvac-transcribepsy.git "$WORK/src/transcribepsy" "$(revision HEARTH_TRANSCRIBEPSY_REVISION)"
  mkdir -p "$WORK/transcribepsy-project"
  cp "$REPO/scripts/speech/transcribepsy.cmake" "$WORK/transcribepsy-project/CMakeLists.txt"
  if [ "$ACCELERATION" = vulkan ]; then
    VULKAN_DEPS="$REPO/build/translation-runtime"
    GLSLC="${HEARTH_GLSLC:-${NDK_PATH:-$HOME/Library/Android/sdk/ndk/27.0.12077973}/shader-tools/darwin-x86_64/glslc}"
    test -x "$GLSLC"
    test -f "$VULKAN_DEPS/Vulkan-Headers/include/vulkan/vulkan.h"
    test "$(git -C "$VULKAN_DEPS/Vulkan-Headers" rev-parse HEAD)" = 19725e4d48082fe78e26622b15d3080ccd54112b
    test "$(git -C "$VULKAN_DEPS/SPIRV-Headers" rev-parse HEAD)" = 01e0577914a75a2569c846778c2f93aa8e6feddd
    FLAGS+=(-DVulkan_INCLUDE_DIR="$VULKAN_DEPS/Vulkan-Headers/include" -DVulkan_GLSLC_EXECUTABLE="$GLSLC"
      -DHEARTH_SPIRV_HEADERS="$VULKAN_DEPS/SPIRV-Headers/include")
    if [ "$PLATFORM" = host ] && [ "$(uname -s)" = Darwin ]; then
      FLAGS+=(-DVulkan_LIBRARY="$REPO/build/gpu-research/moltenvk/MoltenVK/MoltenVK/dynamic/dylib/macOS/libMoltenVK.dylib")
    fi
  fi
  if [ "$ACCELERATION" = opencl ]; then
    OPENCL_HEADERS="$REPO/build/translation-runtime/OpenCL-Headers"
    test "$(git -C "$OPENCL_HEADERS" rev-parse HEAD)" = 30bc20a8e90468e231d7c639805ae61ad1fefa4f
    FLAGS+=(-DHEARTH_OPENCL_HEADERS="$OPENCL_HEADERS")
  fi
  cmake -S "$WORK/transcribepsy-project" -B "$WORK/$OUTPUT/transcribepsy" "${FLAGS[@]}" \
    -DTRANSCRIBEPSY_SOURCE="$WORK/src/transcribepsy" -DHEARTH_CPP="$SPEECH_CPP" -DHEARTH_SPEECH_ACCELERATION="$ACCELERATION"
  SPEECH_TARGET=hearth_transcribepsy
  if [ "$ACCELERATION" != cpu ]; then SPEECH_TARGET="${SPEECH_TARGET}_$ACCELERATION"; fi
  cmake --build "$WORK/$OUTPUT/transcribepsy" --target "$SPEECH_TARGET" -j "$JOBS"
  publish_libraries "$WORK/$OUTPUT/transcribepsy/lib$SPEECH_TARGET.so" "$WORK/$OUTPUT/transcribepsy/lib$SPEECH_TARGET.dylib"
fi
if [ "$BACKEND" = all ] || [ "$ACCELERATION" != cpu ]; then
  python3 "$REPO/scripts/speech/runtime-sources.py" > "$WORK/$OUTPUT/build-sources.final.json"
  cmp "$WORK/$OUTPUT/build-sources.pending.json" "$WORK/$OUTPUT/build-sources.final.json"
  mv "$WORK/$OUTPUT/build-sources.final.json" "$WORK/$OUTPUT/build-sources.json"
  rm "$WORK/$OUTPUT/build-sources.pending.json"
fi
if [ "$PLATFORM" = android ]; then
  case "$(uname -s)" in Darwin) NDK_HOST=darwin-x86_64;; Linux) NDK_HOST=linux-x86_64;; *) echo 'Unsupported NDK host' >&2; exit 2;; esac
  python3 "$REPO/scripts/speech/verify-android-runtimes.py" "$WORK/$OUTPUT/lib" "$NDK_PATH/toolchains/llvm/prebuilt/$NDK_HOST/bin" "$ABI"
fi
printf 'Runtime outputs: %s\n'  "$WORK/$OUTPUT/lib"
