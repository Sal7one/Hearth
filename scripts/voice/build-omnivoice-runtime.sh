#!/usr/bin/env bash
# Developer-only pinned optional runtime build; never downloads weights or runs from Gradle.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
source_dir="${HEARTH_OMNIVOICE_SOURCE_DIR:-$repo_root/research/tts-expansion/omnivoice.cpp}"
work="${HEARTH_OMNIVOICE_BUILD_DIR:-$repo_root/build/voice-runtimes}"
platform="${1:-android}"
abi="${2:-arm64-v8a}"
case "$platform" in host|android);;*)exit 2;;esac
test "$abi" = arm64-v8a
test "$(git -C "$source_dir" rev-parse HEAD)" = 1a468d8b9d65b3544ca1004d2d7c05dfc65d1f3e
test "$(git -C "$source_dir/ggml" rev-parse HEAD)" = cc5df49a6b878db2b68b9a7b45976e279594627d
test -z "$(git -C "$source_dir" diff --name-only)"
test -z "$(git -C "$source_dir/ggml" diff --name-only)"
build_dir="$work/omnivoice-$platform-$abi-cpu"
mkdir -p "$build_dir/source"
cp "$repo_root/scripts/voice/omnivoice-runtime.cmake" "$build_dir/source/CMakeLists.txt"
cp "$repo_root/scripts/voice/omnivoice.map" "$build_dir/source/omnivoice.map"
python3 "$repo_root/scripts/voice/omnivoice-runtime-sources.py" > "$build_dir/sources-before.json"
flags=(-G Ninja -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_VULKAN=OFF -DGGML_METAL=OFF -DGGML_CUDA=OFF -DGGML_BLAS=OFF -DGGML_BACKEND_DL=OFF -DGGML_BUILD_TESTS=OFF -DGGML_BUILD_EXAMPLES=OFF -DOMNIVOICE_SOURCE_DIR="$source_dir")
if [ "$platform" = android ]; then
 ndk_path="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
 flags+=(-DCMAKE_TOOLCHAIN_FILE="$ndk_path/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)
fi
cmake -S "$build_dir/source" -B "$build_dir" "${flags[@]}" -Wno-author
cmake --build "$build_dir" --target hearth_omnivoice -j"${HEARTH_BUILD_JOBS:-4}"
python3 "$repo_root/scripts/voice/omnivoice-runtime-sources.py" > "$build_dir/sources-after.json"
cmp "$build_dir/sources-before.json" "$build_dir/sources-after.json"
test -z "$(git -C "$source_dir" diff --name-only)"
test -z "$(git -C "$source_dir/ggml" diff --name-only)"
if [ "$platform" = android ]; then
 case "$(uname -s)" in Darwin)ndk_host=darwin-x86_64;;Linux)ndk_host=linux-x86_64;;*)exit 2;;esac
 "$ndk_path/toolchains/llvm/prebuilt/$ndk_host/bin/llvm-strip" --strip-unneeded "$build_dir/libhearth_omnivoice.so"
 mkdir -p "$repo_root/hearth-tts-omnivoice/src/main/jniLibs/$abi"
 cp "$build_dir/libhearth_omnivoice.so" "$repo_root/hearth-tts-omnivoice/src/main/jniLibs/$abi/"
 python3 - "$repo_root" "$build_dir" "$abi" <<'PROVENANCE'
import hashlib,json,sys,subprocess
from pathlib import Path
root,build,abi=Path(sys.argv[1]),Path(sys.argv[2]),sys.argv[3]
sources=json.loads((build/'sources-before.json').read_text())
binary=root/'hearth-tts-omnivoice/src/main/jniLibs'/abi/'libhearth_omnivoice.so'
record={'schemaVersion':1,'source':{'repo':'https://github.com/ServeurpersoCom/omnivoice.cpp','revision':'1a468d8b9d65b3544ca1004d2d7c05dfc65d1f3e','ggmlRevision':'cc5df49a6b878db2b68b9a7b45976e279594627d'},'runtime':{'abi':abi,'backend':'CPU','file':binary.name,'bytes':binary.stat().st_size,'sha256':hashlib.sha256(binary.read_bytes()).hexdigest()},'build':{'ndk':'27.0.12077973','api':28,'cmake':subprocess.check_output(['cmake','--version'],text=True).splitlines()[0].split()[-1]},'sources':sources,'sourceSetSha256':hashlib.sha256(json.dumps(sources,sort_keys=True,separators=(',',':')).encode()).hexdigest(),'limitations':['CPU only','Cooperative cancellation between upstream synthesis chunks','Generated voice design profiles are not fixed speaker identities; gender is not verified']}
(root/'hearth-tts-omnivoice/src/main/assets/licenses/omnivoice/runtime-build.json').write_text(json.dumps(record,indent=2)+'\n')
(root/'hearth-tts-omnivoice/src/main/jniLibs/SHA256SUMS').write_text(record['runtime']['sha256']+'  '+abi+'/'+binary.name+'\n')
PROVENANCE
fi
