#!/usr/bin/env bash
# Build the experimental Ming JNI for Android arm64. No device is touched.
# Requires NDK r27, rustc 1.99 and the aarch64-linux-android target.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NDK=${ANDROID_NDK_HOME:-"$HOME/Library/Android/sdk/ndk/27.0.12077973"}
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
CLANG="$TOOLCHAIN/bin/aarch64-linux-android28-clang"
RUST_TOOLCHAIN=${HEARTH_MING_RUST_TOOLCHAIN:-1.99.0-aarch64-apple-darwin}
OUT=${HEARTH_MING_ANDROID_OUT:-"$ROOT/build/ming-android/arm64-v8a"}
MANIFEST="$ROOT/common-jni/src/main/cpp/ming/jni/Cargo.toml"
test -x "$CLANG"
test -f "$MANIFEST"
mkdir -p "$OUT"
RUSTC_BIN=$(rustup which --toolchain "$RUST_TOOLCHAIN" rustc)
rustup target add aarch64-linux-android --toolchain "$RUST_TOOLCHAIN" >/dev/null 2>&1 || true
build() {
  local name=$1 flags=$2 target_dir=$3
  CARGO_TARGET_DIR="$ROOT/build/ming-android/target-$target_dir" \
  RUSTC="$RUSTC_BIN" \
  CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG" \
  CC_aarch64_linux_android="$CLANG" \
  AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar" \
  CXX_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android28-clang++" \
  RUSTFLAGS="$flags -C link-arg=-Wl,-z,max-page-size=16384" \
  cargo build --ignore-rust-version --locked --release --target aarch64-linux-android --manifest-path "$MANIFEST"
  install -m 755 "$ROOT/build/ming-android/target-$target_dir/aarch64-linux-android/release/libming_jni.so" "$OUT/libming_jni-$name.so"
  echo "Ming Android $name: $OUT/libming_jni-$name.so"
  shasum -a 256 "$OUT/libming_jni-$name.so"
}
build scalar "" android
build dotprod "-C target-feature=+dotprod" android-dotprod
echo "Android runtime is build/ELF-verified only; no device or emulator was used."
