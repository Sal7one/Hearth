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
}
clone_pin CTranslate2 https://github.com/OpenNMT/CTranslate2.git 8c8cab2a954810216a45d65c1b8d56839c846e5b
clone_pin sentencepiece https://github.com/google/sentencepiece.git 17d7580d6407802f85855d2cc9190634e2c95624
git -C "$WORK/src/CTranslate2" submodule update --init --recursive third_party/ruy third_party/spdlog third_party/cpu_features
FLAGS=(-DCMAKE_BUILD_TYPE=Release -DCT2_SOURCE="$WORK/src/CTranslate2" -DSPM_SOURCE="$WORK/src/sentencepiece" -DCPP="$ROOT/common-jni/src/main/cpp")
OUTPUT=host
if [ "$ABI" != host ];then
 NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
 OUTPUT="android-$ABI"
 FLAGS+=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)
fi
cmake -S "$ROOT/scripts/translation/ct2" -B "$WORK/$OUTPUT" "${FLAGS[@]}"
cmake --build "$WORK/$OUTPUT" --target "$([ "$ABI" = host ] && echo ct2_smoke || echo transiber_ct2)" -j "${HEARTH_BUILD_JOBS:-4}"
