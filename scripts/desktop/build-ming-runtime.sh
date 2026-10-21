#!/usr/bin/env bash
# Build the experimental Ming-3.0 TTS JNI runtime for macOS arm64.
# fusor-ai 0.1.2 requires rustc 1.99; cargo 1.90 is enough when it drives that rustc.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MANIFEST="$ROOT/common-jni/src/main/cpp/ming/jni/Cargo.toml"
OUT=${HEARTH_MING_OUTPUT:-"$ROOT/desktop-runtime/native-libs/macos-arm64"}
TOOLCHAIN=${HEARTH_MING_RUST_TOOLCHAIN:-1.99.0-aarch64-apple-darwin}
test -f "$MANIFEST"
mkdir -p "$OUT"
RUSTC_BIN=$(rustup which --toolchain "$TOOLCHAIN" rustc)
"$RUSTC_BIN" --version | grep -q '1\.99\.'
# cargo 1.90 cannot select 1.99 as a toolchain (its cargo component is absent), so drive
# rustc 1.99 explicitly and skip Cargo's rust-version check.
RUSTC="$RUSTC_BIN" cargo build --ignore-rust-version --locked --release --manifest-path "$MANIFEST"
install -m 755 "$ROOT/common-jni/src/main/cpp/ming/jni/target/release/libming_jni.dylib" "$OUT/libming_jni.dylib"
install_name_tool -id @rpath/libming_jni.dylib "$OUT/libming_jni.dylib"
echo "Ming JNI staged at $OUT/libming_jni.dylib"
shasum -a 256 "$OUT/libming_jni.dylib"
