#!/usr/bin/env bash
# Explicit developer build. The shared ORT AAR supplies Android's single ONNX runtime.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
platform="${1:-android}";abi="${2:-arm64-v8a}"
case "$platform" in android|host);;*)exit 2;;esac
test "$abi" = arm64-v8a
upstream="$root/research/tts-expansion/chatterboxcpp"
test "$(git -C "$upstream" rev-parse HEAD)" = 1a9a4fc00a6caa8c8bb9bee533225f40661e4220
test -z "$(git -C "$upstream" diff --name-only)"
work="$root/build/voice-runtimes/chatterbox-$platform-arm64"
mkdir -p "$work"
python3 "$root/scripts/voice/chatterbox-runtime-sources.py" > "$work/sources-before.json"
python3 "$root/scripts/voice/chatterbox/prepare-watermark.py" "$upstream/src/watermark.cpp" "$work/watermark.cpp"
rust_target="$(rustc -vV | awk '$1 == "host:" { print $2 }')"
flags=(-G Ninja -DWATERMARK_CPP="$work/watermark.cpp" -DCMAKE_BUILD_TYPE=Release -DUPSTREAM="$upstream" -DCHATTERBOX_CPP="$root/hearth-tts-chatterbox/src/main/cpp" -DORT_HEADERS="$root/common-jni/src/main/cpp/onnx/include")
if [ "$platform" = android ];then
 ndk="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
 rust_target=aarch64-linux-android
 toolchain=""
 for candidate in "$ndk"/toolchains/llvm/prebuilt/*/bin;do if [ -x "$candidate/aarch64-linux-android28-clang" ];then toolchain="$candidate";break;fi;done
 test -n "$toolchain"
 export CC_aarch64_linux_android="$toolchain/aarch64-linux-android28-clang"
 export AR_aarch64_linux_android="$toolchain/llvm-ar"
 export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$toolchain/aarch64-linux-android28-clang"
 flags+=(-DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DORT_LIBRARY="$root/hearth-onnx-runtime/src/main/jniLibs/$abi/libonnxruntime.so" -DBPE_LIBRARY="$root/build/ct2-runtime/rust/aarch64-linux-android/release/libhearth_byte_bpe.a")
else
 flags+=(-DORT_LIBRARY="${HEARTH_HOST_ORT:-$root/build/model-expansion/ort-host/onnxruntime-osx-arm64-1.20.1/lib/libonnxruntime.dylib}" -DBPE_LIBRARY="$root/build/ct2-runtime/rust/$rust_target/release/libhearth_byte_bpe.a")
fi
cargo build --manifest-path "$root/common-jni/src/main/cpp/translation/bpe-rust/Cargo.toml" --locked --offline --release --target "$rust_target" --target-dir "$root/build/ct2-runtime/rust"
cmake -S "$root/scripts/voice/chatterbox" -B "$work" "${flags[@]}"
cmake --build "$work" --target hearth_chatterbox -j"${HEARTH_BUILD_JOBS:-4}"
if [ "$platform" = host ];then cmake --build "$work" --target chatterbox_smoke -j2;fi
python3 "$root/scripts/voice/chatterbox-runtime-sources.py" > "$work/sources-after.json"
cmp "$work/sources-before.json" "$work/sources-after.json"
test -z "$(git -C "$upstream" diff --name-only)"
if [ "$platform" = android ];then
 case "$(uname -s)" in Darwin)ndk_host=darwin-x86_64;;Linux)ndk_host=linux-x86_64;;*)exit 2;;esac
 "$ndk/toolchains/llvm/prebuilt/$ndk_host/bin/llvm-strip" --strip-unneeded "$work/libhearth_chatterbox.so"
 mkdir -p "$root/hearth-tts-chatterbox/src/main/jniLibs/$abi"
 cp "$work/libhearth_chatterbox.so" "$root/hearth-tts-chatterbox/src/main/jniLibs/$abi/"
 python3 - "$root" "$work" "$abi" <<'PROVENANCE'
import hashlib,json,sys,subprocess
from pathlib import Path
root,work,abi=Path(sys.argv[1]),Path(sys.argv[2]),sys.argv[3]
sources=json.loads((work/'sources-before.json').read_text())
binary=root/'hearth-tts-chatterbox/src/main/jniLibs'/abi/'libhearth_chatterbox.so'
record={'schemaVersion':1,'source':{'repo':'https://github.com/birdup000/chatterboxcpp','revision':'1a9a4fc00a6caa8c8bb9bee533225f40661e4220','tokenizersVersion':'0.22.2','perthRevision':'ff1c8ac55a976971245cdd53c18d6131ca00d993','jsonVersion':'3.11.2','jsonSourceRevision':'8c8cab2a954810216a45d65c1b8d56839c846e5b'},'runtime':{'abi':abi,'backend':'ONNX Runtime CPU','file':binary.name,'bytes':binary.stat().st_size,'sha256':hashlib.sha256(binary.read_bytes()).hexdigest()},'build':{'ndk':'27.0.12077973','api':28,'cmake':subprocess.check_output(['cmake','--version'],text=True).splitlines()[0].split()[-1]},'sources':sources,'sourceSetSha256':hashlib.sha256(json.dumps(sources,sort_keys=True,separators=(',',':')).encode()).hexdigest(),'modelPreparation':{'graph':{'sourceBytes':228633,'sourceSha256':'91846c24706116f87b39aac5b13aa1a67243b6f973bc433327d143b8eff9fde6','outputBytes':228453,'outputSha256':'60f1451649fcef2014208084f546328f3a4e32c635d57983da7dcf75e9e3dbe8','operation':'Remove only three omitted optional inputs from each of 30 GroupQueryAttention nodes; all other bytes preserved'},'perth':json.loads((root/'hearth-tts-chatterbox/src/main/assets/licenses/chatterbox/perth-conversion.json').read_text())},'preparedWatermarkSha256':hashlib.sha256((work/'watermark.cpp').read_bytes()).hexdigest(),'upstreamFiles':{name:hashlib.sha256((root/'research/tts-expansion/chatterboxcpp'/name).read_bytes()).hexdigest() for name in ['src/audio.cpp','src/watermark.cpp','include/chatterbox/audio.hpp','include/chatterbox/watermark.hpp','include/chatterbox/types.hpp']},'limitations':['CPU ARM64 only','Arabic/English frontends initially exposed','Whole waveform decoded and watermarked before bounded PCM delivery; no token streaming playback','Raw NFKD text without number expansion','500 tokens / 20 seconds generation maximum per part; bound without STOP is an error','Default reference has unverified gender','Owner listening and device performance not measured']}
(root/'hearth-tts-chatterbox/src/main/assets/licenses/chatterbox/runtime-build.json').write_text(json.dumps(record,indent=2)+'\n')
(root/'hearth-tts-chatterbox/src/main/jniLibs/SHA256SUMS').write_text(record['runtime']['sha256']+'  '+abi+'/'+binary.name+'\n')
PROVENANCE
fi
