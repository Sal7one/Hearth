"""Hash the sources actually used to build the isolated CT2/tokenizer runtime."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[3]


def collect():
    files = [ROOT / "common-jni/src/main/cpp/common/lease_registry.h"]
    for name in ["common-jni/src/main/cpp/translation/ct2", "common-jni/src/main/cpp/translation/bpe-rust",
                 "scripts/translation/ct2", "scripts/translation/jisr"]:
        files.extend(p for p in (ROOT / name).rglob("*") if p.is_file()
                     and "__pycache__" not in p.parts and p.suffix != ".pyc")
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)}


if __name__ == "__main__":
    print(json.dumps(collect(), sort_keys=True, indent=2))
