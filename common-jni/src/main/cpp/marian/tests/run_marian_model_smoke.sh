#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 2 ]]; then
  echo "Usage: $0 <pinned-EN-AR-model-directory> <host-onnxruntime-root>" >&2
  exit 2
fi
MARIAN="$(cd "$(dirname "$0")/.." && pwd)"
MODEL="$(cd "$1" && pwd)"
ORT_ROOT="$(cd "$2" && pwd)"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT

# Same four publisher files as MarianPackage.kt. Never turn a smoke run into
# an unverified model load or download weights implicitly during host tests.
python3 - "$MODEL" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
pins = {
    "source.spm": (801074, "81ac8f4d4ee271f4109eac67560a31f9c2d555463ba5eb95c32c6deee8ae819d"),
    "tokenizer.json": (6748848, "109bb62e1c598287f5960616c869e15be54785a3c2906903dafe044241d50b61"),
    "encoder_model_quantized.onnx": (51749190, "ebce26ed64f2d6efd09098f5752f283ae7bdf593114d8e2add769f51a395d91b"),
    "decoder_model_merged_quantized.onnx": (187651988, "ae11f1426d824134ad3788bc44f09c7c8c42876c386bd7474cd181b262f82c89"),
}
for name, (size, digest) in pins.items():
    path = root / name
    if not path.is_file() or path.stat().st_size != size:
        raise SystemExit("Pinned model file missing or wrong size: " + name)
    with path.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != digest:
        raise SystemExit("Pinned model SHA-256 mismatch: " + name)
print("Marian EN→AR: all four publisher files verified")
PY

ORT_LIBRARY="$(python3 - "$ORT_ROOT/lib" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
libraries = {p.resolve() for pattern in ("libonnxruntime*.dylib", "libonnxruntime.so*")
             for p in root.glob(pattern) if p.is_file()}
if len(libraries) != 1:
    raise SystemExit("Provide a host ORT root containing one ONNX Runtime library")
print(next(iter(libraries)))
PY
)"

"${CXX:-c++}" -std=c++17 -O1 -g -Wall -Wextra -Wpedantic -Werror \
  -fsanitize=address,undefined -I"$MARIAN/../onnx/include" \
  "$MARIAN/tests/marian_model_smoke.cpp" "$MARIAN/marian_engine.cpp" \
  "$MARIAN/marian_tokenizer.cpp" "$ORT_LIBRARY" \
  -Wl,-rpath,"$ORT_ROOT/lib" -o "$BUILD/marian_model_smoke"
"$BUILD/marian_model_smoke" "$MODEL"
