#!/usr/bin/env bash
# Explicit developer build; never run by Gradle or on an owner device.
# Usage: build-runtimes.sh [qwen|nemotron|all] [android|host] [ABI]
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
SPEECH_CPP="$REPO/common-jni/src/main/cpp"
WORK="${HEARTH_SPEECH_BUILD_DIR:-$REPO/build/speech-runtimes}"
BACKEND="${1:-all}"
PLATFORM="${2:-android}"
ABI="${3:-arm64-v8a}"
case "$ABI" in arm64-v8a|armeabi-v7a|x86|x86_64) ;; *) echo 'Unsupported Android ABI' >&2; exit 2;; esac
OUTPUT="$PLATFORM"
if [ "$PLATFORM" = android ] && [ "$ABI" != arm64-v8a ]; then OUTPUT="android-$ABI"; fi
JOBS="${HEARTH_BUILD_JOBS:-4}"
case "$BACKEND" in qwen|nemotron|all) ;; *) echo 'Expected qwen, nemotron or all' >&2; exit 2;; esac
case "$PLATFORM" in android|host) ;; *) echo 'Expected android or host' >&2; exit 2;; esac
mkdir -p "$WORK/src" "$WORK/$OUTPUT/lib"
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
if [ "$PLATFORM" = android ]; then
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
p.write_text(s.replace('#if __ANDROID_API__ >= 27', '#if __ANDROID_API__ >= 27 && !defined(HEARTH_SPEECH_CPU_ONLY)'))
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
  find "$WORK/$OUTPUT/qwen" -maxdepth 2 -type f \( -name 'libhearth_qwen.so' -o -name 'libhearth_qwen.dylib' \) -exec cp {} "$WORK/$OUTPUT/lib/" \;
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
  find "$WORK/$OUTPUT/nemo/bin" -maxdepth 1 -type f \( -name 'libhearth_nemotron*.so' -o -name 'libhearth_nemotron*.dylib' \) -exec cp {} "$WORK/$OUTPUT/lib/" \;
fi
if [ "$PLATFORM" = android ]; then
  case "$(uname -s)" in Darwin) NDK_HOST=darwin-x86_64;; Linux) NDK_HOST=linux-x86_64;; *) echo 'Unsupported NDK host' >&2; exit 2;; esac
  python3 "$REPO/scripts/speech/verify-android-runtimes.py" "$WORK/$OUTPUT/lib" "$NDK_PATH/toolchains/llvm/prebuilt/$NDK_HOST/bin" "$ABI"
fi
printf 'Runtime outputs: %s\n'  "$WORK/$OUTPUT/lib"
