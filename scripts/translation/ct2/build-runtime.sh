#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
WORK="$ROOT/build/ct2-runtime"
ABI="${1:-arm64-v8a}"
case "$ABI" in host|arm64-v8a) ;; *) echo 'Unsupported target' >&2;exit 2;; esac
clone_pin() {
 local name="$1" url="$2" pin="$3"
 if [ ! -d "$WORK/src/$name/.git" ];then git clone --filter=blob:none --no-checkout "$url" "$WORK/src/$name";fi
 if ! git -C "$WORK/src/$name" cat-file -e "$pin^{commit}";then git -C "$WORK/src/$name" fetch --depth 1 origin "$pin";fi
 git -C "$WORK/src/$name" checkout --detach "$pin"
 git -C "$WORK/src/$name" diff --quiet HEAD -- || { echo "$name sources differ from the pinned revision" >&2;exit 2; }
}
clone_pin CTranslate2 https://github.com/OpenNMT/CTranslate2.git 8c8cab2a954810216a45d65c1b8d56839c846e5b
clone_pin sentencepiece https://github.com/google/sentencepiece.git 17d7580d6407802f85855d2cc9190634e2c95624
git -C "$WORK/src/CTranslate2" submodule update --init --recursive third_party/ruy third_party/spdlog third_party/cpu_features
git -C "$WORK/src/CTranslate2" diff --quiet HEAD -- || { echo 'CTranslate2 dependency revisions differ from the source pin' >&2;exit 2; }
FLAGS=(-DCMAKE_BUILD_TYPE=Release -DCT2_SOURCE="$WORK/src/CTranslate2" -DSPM_SOURCE="$WORK/src/sentencepiece" -DCPP="$ROOT/common-jni/src/main/cpp")
OUTPUT=host
RUST_TARGET="$(rustc -vV | awk '$1 == "host:" { print $2 }')"
if [ "$ABI" != host ];then
 NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
 OUTPUT="android-$ABI"
 FLAGS+=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)
 RUST_TARGET=aarch64-linux-android
 TOOLCHAIN=""
 for candidate in "$NDK"/toolchains/llvm/prebuilt/*/bin; do
  if [ -x "$candidate/aarch64-linux-android28-clang" ];then TOOLCHAIN="$candidate";break;fi
 done
 test -n "$TOOLCHAIN" || { echo 'Android ARM64 compiler not found' >&2;exit 2; }
 export CC_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android28-clang"
 export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
 export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/aarch64-linux-android28-clang"
fi
python3 "$ROOT/scripts/translation/ct2/runtime_sources.py" > "$WORK/$OUTPUT.sources.before.json"
cargo build --manifest-path "$ROOT/common-jni/src/main/cpp/translation/bpe-rust/Cargo.toml" --locked --release --target "$RUST_TARGET" --target-dir "$WORK/rust"
FLAGS+=(-DBPE_LIB="$WORK/rust/$RUST_TARGET/release/libhearth_byte_bpe.a")
cmake -S "$ROOT/scripts/translation/ct2" -B "$WORK/$OUTPUT" "${FLAGS[@]}"
cmake --build "$WORK/$OUTPUT" --target "$([ "$ABI" = host ] && echo ct2_smoke || echo transiber_ct2)" -j "${HEARTH_BUILD_JOBS:-4}"
python3 "$ROOT/scripts/translation/ct2/runtime_sources.py" > "$WORK/$OUTPUT.sources.after.json"
cmp "$WORK/$OUTPUT.sources.before.json" "$WORK/$OUTPUT.sources.after.json"
cp "$WORK/$OUTPUT.sources.before.json" "$WORK/$OUTPUT/hearth-ct2-sources.json"
