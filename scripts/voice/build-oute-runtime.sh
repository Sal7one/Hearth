#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
source_dir="${HEARTH_OUTE_SOURCE_DIR:-$root/research/tts-expansion/chatllm.cpp}"
work="${HEARTH_OUTE_BUILD_DIR:-$root/build/voice-runtimes}"
platform="${1:-host}"
test "$platform" = host || test "$platform" = android
test "$(git -C "$source_dir" rev-parse HEAD)" = 74b8237a4c1796f76bb0ed7f483b8e52e7d4d5c7
test -z "$(git -C "$source_dir" diff --name-only)"
source_copy="$work/oute-source"
python3 "$root/scripts/voice/oute/prepare-source.py" "$source_dir" "$source_copy"
output="$work/oute-$platform-arm64-v8a-cpu"
flags=(-G Ninja -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_VULKAN=OFF -DGGML_METAL=OFF -DGGML_CUDA=OFF -DGGML_BLAS=OFF -DGGML_BACKEND_DL=OFF -DGGML_BUILD_TESTS=OFF -DGGML_BUILD_EXAMPLES=OFF -DOUTE_SOURCE="$source_copy" -DHEARTH_ROOT="$root")
if [ "$platform" = android ]; then
 ndk="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
 flags+=(-DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)
fi
mkdir -p "$output"
python3 "$root/scripts/voice/oute-runtime-sources.py" > "$output/sources-before.json"
cmake -S "$root/scripts/voice/oute" -B "$output" "${flags[@]}"
cmake --build "$output" --target hearth_oute -j"${HEARTH_BUILD_JOBS:-4}"

python3 "$root/scripts/voice/oute-runtime-sources.py" > "$output/sources-after.json"
cmp "$output/sources-before.json" "$output/sources-after.json"
if [ "$platform" = android ]; then
 case "$(uname -s)" in Darwin)ndk_host=darwin-x86_64;;Linux)ndk_host=linux-x86_64;;*)exit 2;;esac
 "$ndk/toolchains/llvm/prebuilt/$ndk_host/bin/llvm-strip" --strip-unneeded "$output/libhearth_oute.so"
 mkdir -p "$root/hearth-tts-oute/src/main/jniLibs/arm64-v8a"
 cp "$output/libhearth_oute.so" "$root/hearth-tts-oute/src/main/jniLibs/arm64-v8a/"
 python3 - "$root" "$output" <<'PROVENANCE'
import hashlib,json,sys,subprocess
from pathlib import Path
root,build=map(Path,sys.argv[1:]);sources=json.loads((build/'sources-before.json').read_text())
binary=root/'hearth-tts-oute/src/main/jniLibs/arm64-v8a/libhearth_oute.so'
record={'schemaVersion':1,'source':{'repo':'https://github.com/foldl/chatllm.cpp','revision':'74b8237a4c1796f76bb0ed7f483b8e52e7d4d5c7','ggml':'embedded at pinned revision'},'runtime':{'abi':'arm64-v8a','backend':'CPU','file':binary.name,'bytes':binary.stat().st_size,'sha256':hashlib.sha256(binary.read_bytes()).hexdigest()},'build':{'ndk':'27.0.12077973','api':28,'cmake':subprocess.check_output(['cmake','--version'],text=True).splitlines()[0].split()[-1]},'sources':sources,'sourceSetSha256':hashlib.sha256(json.dumps(sources,sort_keys=True,separators=(',',':')).encode()).hexdigest(),'limitations':['CPU only; generation completes before bounded PCM delivery','1B model has noncommercial/share-alike and Llama terms','English reference; native Arabic quality and gender matching unverified','No device latency benchmark']}
(root/'hearth-tts-oute/src/main/assets/licenses/oute/runtime-build.json').write_text(json.dumps(record,indent=2)+'\n')
(root/'hearth-tts-oute/src/main/jniLibs/SHA256SUMS').write_text(record['runtime']['sha256']+'  arm64-v8a/'+binary.name+'\n')
PROVENANCE
fi
