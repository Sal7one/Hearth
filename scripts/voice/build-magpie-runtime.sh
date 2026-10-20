#!/usr/bin/env bash
# Developer-only deterministic optional NeMo CPU runtime build. No model weights are downloaded.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
work="${HEARTH_MAGPIE_BUILD_DIR:-$repo_root/build/voice-runtimes}"
source_dir="${HEARTH_MAGPIE_SOURCE_DIR:-$repo_root/research/tts-expansion/NeMo-Speech.cpp}"
platform="${1:-android}"
ndk_path="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
abi="${2:-arm64-v8a}"
case "$platform" in android|host) ;; *) exit 2;; esac
case "$abi" in arm64-v8a|x86_64|armeabi-v7a|x86) ;; *) exit 2;; esac
test "$(git -C "$source_dir" rev-parse HEAD)" = caa3f47c7c75fa2e9e6340272ebad57c1647dd44
test "$(git -C "$source_dir/llama.cpp" rev-parse HEAD)" = bd4f514db14d87fded667787a7a963bfbaa98e89
test -z "$(git -C "$source_dir" diff --name-only)"
build_dir="$work/magpie-$platform-$abi-cpu"
mkdir -p "$build_dir"
python3 "$repo_root/scripts/voice/magpie-runtime-sources.py" > "$build_dir/sources-before.json"

flags=(-G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_VULKAN=OFF -DGGML_METAL=OFF -DGGML_CUDA=OFF -DNEMO_SPEECH_GGML_PATCHED=OFF -DNEMO_SPEECH_BUILD_ASR=OFF -DNEMO_SPEECH_BUILD_DIAR=OFF -DNEMO_SPEECH_BUILD_TTS=ON -DNEMO_SPEECH_BUILD_NMT=OFF -DNEMO_SPEECH_BUILD_CLI=OFF -DNEMO_SPEECH_BUILD_MIC_CAPTURE=OFF -DNEMO_SPEECH_BUILD_HTTP=OFF -DNEMO_SPEECH_BUILD_GRPC=OFF -DNEMO_SPEECH_BUILD_TESTS=OFF -DBUILD_TESTING=OFF -DNEMO_SPEECH_TTS_WITH_JA=OFF -DNEMO_SPEECH_TTS_WITH_ZH=OFF -DNEMO_SPEECH_WITH_NORM=OFF -DCMAKE_PROJECT_TOP_LEVEL_INCLUDES="$repo_root/scripts/voice/nemo-tts-inject.cmake")
if [ "$platform" = android ]; then flags+=(-DCMAKE_TOOLCHAIN_FILE="$ndk_path/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON); fi
cmake -S "$source_dir" -B "$build_dir" "${flags[@]}" -Wno-author
cmake --build "$build_dir" --target nemo_speech_tts -j"${HEARTH_BUILD_JOBS:-4}"
python3 "$repo_root/scripts/voice/magpie-runtime-sources.py" > "$build_dir/sources-after.json"
cmp "$build_dir/sources-before.json" "$build_dir/sources-after.json"
test -z "$(git -C "$source_dir" diff --name-only)"
test -z "$(git -C "$source_dir/llama.cpp" diff --name-only)"
if [ "$platform" = android ]; then
 case "$(uname -s)" in Darwin) ndk_host=darwin-x86_64;;Linux)ndk_host=linux-x86_64;;*)exit 2;;esac
 "$ndk_path/toolchains/llvm/prebuilt/$ndk_host/bin/llvm-strip" --strip-unneeded "$build_dir/bin/libhearth_magpie.so"
 mkdir -p "$repo_root/hearth-tts-magpie/src/main/jniLibs/$abi"
 cp "$build_dir/bin/libhearth_magpie.so" "$repo_root/hearth-tts-magpie/src/main/jniLibs/$abi/"
 python3 - "$repo_root" "$build_dir" "$abi" <<'PROVENANCE'
import hashlib,json,sys,subprocess
from pathlib import Path
root,build,abi=Path(sys.argv[1]),Path(sys.argv[2]),sys.argv[3]
p=root/'hearth-tts-magpie/src/main/assets/licenses/magpie/runtime-build.json'
record=json.loads(p.read_text())
sources=json.loads((build/'sources-before.json').read_text())
record['sources']=sources
record['sourceSetSha256']=hashlib.sha256(json.dumps(sources,sort_keys=True,separators=(',',':')).encode()).hexdigest()
binary=root/'hearth-tts-magpie/src/main/jniLibs'/abi/'libhearth_magpie.so'
record['runtime'].update(abi=abi,bytes=binary.stat().st_size,sha256=hashlib.sha256(binary.read_bytes()).hexdigest())
record['build']['cmake']=subprocess.check_output(['cmake','--version'],text=True).splitlines()[0].split()[-1]
p.write_text(json.dumps(record,indent=2)+'\n')
(root/'hearth-tts-magpie/src/main/jniLibs/SHA256SUMS').write_text(record['runtime']['sha256']+'  '+abi+'/libhearth_magpie.so\n')
PROVENANCE
fi
