#!/usr/bin/env python3
"""Extract all Android ABIs from the existing SHA-pinned Vosk/ORT archives."""
import hashlib
import shutil
import urllib.request
import zipfile
from pathlib import Path
from native_abis import ABIS, update_checksums, verify_elf

def main():
    root = Path(__file__).resolve().parents[1]
    cache = root / "build/vendor-artifacts"
    cache.mkdir(parents=True, exist_ok=True)
    libs = root / "common-jni/src/main/jniLibs"
    onnx_libs = root / "hearth-onnx-runtime/src/main/jniLibs"
    for line in (root / "scripts/supply-chain-artifacts.tsv").read_text().splitlines():
        if line.startswith("#"): continue
        name, digest, url, original_member = line.split("\t")
        if not name.endswith("-aar"): continue
        archive = cache / (name + ".aar")
        if not archive.exists():
            temporary = archive.with_suffix(".part")
            try:
                with urllib.request.urlopen(url, timeout=45) as response, temporary.open("wb") as out:
                    shutil.copyfileobj(response, out)
                if hashlib.sha256(temporary.read_bytes()).hexdigest() != digest:
                    raise ValueError(f"{name}: downloaded archive hash mismatch")
                temporary.replace(archive)
            finally: temporary.unlink(missing_ok=True)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            raise ValueError(f"{name}: cached archive hash mismatch")
        with zipfile.ZipFile(archive) as source:
            for abi in ABIS:
                filename = Path(original_member).name
                data = source.read(f"jni/{abi}/{filename}")
                verify_elf(abi, data)
                target = (onnx_libs if name == "onnx-aar" else libs) / abi / filename
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                print(abi, filename, hashlib.sha256(data).hexdigest())
    update_checksums(libs)
    update_checksums(onnx_libs)

if __name__ == "__main__": main()
