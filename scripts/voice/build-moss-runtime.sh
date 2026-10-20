#!/usr/bin/env bash
# Explicit developer build. The shared ORT AAR supplies Android's single ONNX runtime.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
platform="${1:-android}";abi="${2:-arm64-v8a}"
case "$platform" in android|host);;*)exit 2;;esac
test "$abi" = arm64-v8a
spm="${HEARTH_SENTENCEPIECE_SOURCE:-$root/build/ct2-runtime/src/sentencepiece}"
test "$(git -C "$spm" rev-parse HEAD)" = 17d7580d6407802f85855d2cc9190634e2c95624
test -z "$(git -C "$spm" diff --name-only)"
work="$root/build/voice-runtimes/moss-$platform-arm64"
mkdir -p "$work"
python3 "$root/scripts/voice/moss-runtime-sources.py" > "$work/sources-before.json"
flags=(-G Ninja -DCMAKE_BUILD_TYPE=Release -DSPM_SOURCE="$spm" -DMOSS_CPP="$root/hearth-tts-moss/src/main/cpp" -DORT_HEADERS="$root/common-jni/src/main/cpp/onnx/include")
if [ "$platform" = android ];then
 ndk="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
 flags+=(-DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DORT_LIBRARY="$root/hearth-onnx-runtime/src/main/jniLibs/$abi/libonnxruntime.so")
else
 flags+=(-DORT_LIBRARY="${HEARTH_HOST_ORT:-$root/build/model-expansion/ort-host/onnxruntime-osx-arm64-1.20.1/lib/libonnxruntime.dylib}")
fi
cmake -S "$root/scripts/voice/moss" -B "$work" "${flags[@]}"
cmake --build "$work" --target hearth_moss -j"${HEARTH_BUILD_JOBS:-4}"
if [ "$platform" = host ];then cmake --build "$work" --target moss_smoke -j2;fi
python3 "$root/scripts/voice/moss-runtime-sources.py" > "$work/sources-after.json"
cmp "$work/sources-before.json" "$work/sources-after.json"
test -z "$(git -C "$spm" diff --name-only)"
if [ "$platform" = android ];then
 case "$(uname -s)" in Darwin)ndk_host=darwin-x86_64;;Linux)ndk_host=linux-x86_64;;*)exit 2;;esac
 "$ndk/toolchains/llvm/prebuilt/$ndk_host/bin/llvm-strip" --strip-unneeded "$work/libhearth_moss.so"
 mkdir -p "$root/hearth-tts-moss/src/main/jniLibs/$abi"
 cp "$work/libhearth_moss.so" "$root/hearth-tts-moss/src/main/jniLibs/$abi/"
 python3 - "$root" "$work" "$abi" <<'PROVENANCE'
import hashlib,json,sys,subprocess
from pathlib import Path
root,work,abi=Path(sys.argv[1]),Path(sys.argv[2]),sys.argv[3]
sources=json.loads((work/'sources-before.json').read_text())
binary=root/'hearth-tts-moss/src/main/jniLibs'/abi/'libhearth_moss.so'
record={'schemaVersion':1,'source':{'repo':'https://github.com/OpenMOSS/MOSS-TTS-Nano','revision':'8b7bcc9341b3b4ef3a3a58ba1338a7d85ff133eb','sentencepieceRevision':'17d7580d6407802f85855d2cc9190634e2c95624','jsonVersion':'3.11.2','jsonSourceRevision':'8c8cab2a954810216a45d65c1b8d56839c846e5b'},'runtime':{'abi':abi,'backend':'ONNX Runtime CPU','file':binary.name,'bytes':binary.stat().st_size,'sha256':hashlib.sha256(binary.read_bytes()).hexdigest()},'build':{'ndk':'27.0.12077973','api':28,'cmake':subprocess.check_output(['cmake','--version'],text=True).splitlines()[0].split()[-1]},'sources':sources,'sourceSetSha256':hashlib.sha256(json.dumps(sources,sort_keys=True,separators=(',',':')).encode()).hexdigest(),'limitations':['CPU ARM64 only','48 kHz stereo codec output explicitly averaged to mono','Raw SentencePiece input; no WeText number expansion','Published fixed reference codes; no user-reference encoder','375 frames / 30 seconds maximum per text part; bound reached without EOS is an error','Owner listening and device performance not measured']}
(root/'hearth-tts-moss/src/main/assets/licenses/moss/runtime-build.json').write_text(json.dumps(record,indent=2)+'\n')
(root/'hearth-tts-moss/src/main/jniLibs/SHA256SUMS').write_text(record['runtime']['sha256']+'  '+abi+'/'+binary.name+'\n')
PROVENANCE
fi
