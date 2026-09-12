#!/usr/bin/env python3
"""Publish verified Android build outputs and their source notices into common-jni.
Run after build-runtimes.sh all android. No model weights are staged.
"""
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import urllib.request
from pathlib import Path

repo = Path(__file__).resolve().parents[2]
work = Path(os.environ.get("HEARTH_SPEECH_BUILD_DIR", repo / "build/speech-runtimes"))
ndk = Path(os.environ.get("ANDROID_NDK_HOME", Path.home() / "Library/Android/sdk/ndk/27.0.12077973"))
host = "darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64"
subprocess.run(["python3", str(repo / "scripts/speech/verify-android-runtimes.py"), str(work / "android/lib"), str(ndk / "toolchains/llvm/prebuilt" / host / "bin")], check=True)
records = json.loads((work / "android/runtime-artifacts.json").read_text())
if len(records) != 2:
    raise RuntimeError("Build both speech runtimes before publishing")
asset_dir = repo / "common-jni/src/main/assets/licenses/speech"
asset_dir.mkdir(parents=True, exist_ok=True)

# Preserve upstream notices from the exact source/dependency trees used to build.
sources = {
    "sherpa-onnx": work / "src/sherpa",
    "nemo-speech": work / "src/nemo",
    "ggml": work / "src/nemo/ggml",
    "sentencepiece": work / "src/sentencepiece",
}
for directory in (work / "android/qwen/_deps").glob("*-src"):
    sources[directory.name.removesuffix("-src")] = directory
for name, directory in sources.items():
    candidates = set()
    for pattern in ("LICENSE*", "NOTICE*", "COPYING*", "AUTHORS", "THIRD_PARTY_NOTICES*", "third_party/*/LICENSE*", "third_party/*/COPYING*"):
        candidates.update(directory.glob(pattern))
    for path in sorted(candidates):
        if path.is_file():
            target = asset_dir / name / path.relative_to(directory)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)

# The static ORT distribution contains headers/archives only. Fetch its exact
# version's notices and pin their bytes here as well as the binary archive hash.
ort_notices = {
    "LICENSE": "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c",
    "ThirdPartyNotices.txt": "0e07b95f3a8d6230037707c5c4a2b554d12c4cb67369669ac255635528ffcee2",
}
for name, sha in ort_notices.items():
    target = asset_dir / "onnxruntime" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == sha:
        continue
    data = urllib.request.urlopen(f"https://raw.githubusercontent.com/microsoft/onnxruntime/v1.27.1/{name}", timeout=30).read()
    if hashlib.sha256(data).hexdigest() != sha:
        raise RuntimeError(f"ONNX Runtime notice hash mismatch: {name}")
    target.write_bytes(data)

pins = dict(re.findall(r'#define (HEARTH_\w+_REVISION) "([a-f0-9]+)"', (repo / "common-jni/src/main/cpp/speech/backend_versions.h").read_text()))
source_files = list((repo / "common-jni/src/main/cpp/speech/backends").glob("*")) + list((repo / "scripts/speech").glob("*.cmake")) + [repo / "scripts/speech/build-runtimes.sh", repo / "common-jni/src/main/cpp/speech/backend_abi.h", repo / "common-jni/src/main/cpp/speech/utterance_segmenter.h", repo / "common-jni/src/main/cpp/speech/qwen_language.h"]
source_hashes = {str(p.relative_to(repo)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(source_files) if p.is_file()}
(asset_dir / "runtime-build.json").write_text(json.dumps({"abi": 1, "platform": "android-28-arm64-v8a", "pins": pins, "artifacts": records, "adapterSources": source_hashes, "ndk": (ndk / "source.properties").read_text()}, indent=2) + "\n")
(asset_dir / "README.txt").write_text("Hearth optional speech runtimes. CPU-only, no model weights or network clients.\n"
    "Qwen: sherpa-onnx with private static ONNX Runtime 1.27.1.\n"
    "Nemotron: NeMo-Speech.cpp with private static GGML and SentencePiece.\n"
    "Runtime source pins and local adapter/patch recipe hashes: runtime-build.json.\n"
    "Build recipe: scripts/speech/build-runtimes.sh. Upstream source notices follow in subdirectories; some cover optional components not enabled in this build.\n"
    "Hearth modifies sherpa CPU-provider guards and retains Qwen language metadata; NeMo C++ internals are statically embedded behind one private ABI.\n")
lib_root = repo / "common-jni/src/main/jniLibs"
checksum_file = lib_root / "SHA256SUMS"
lines = checksum_file.read_text().splitlines()
for record in records:
    relative = f"arm64-v8a/{record['file']}"
    shutil.copyfile(work / "android/lib" / record["file"], lib_root / relative)
    lines = [line for line in lines if not line.endswith("  " + relative)]
    lines.append(f"{record['sha256']}  {relative}")
checksum_file.write_text("\n".join(sorted(lines, key=lambda line: line.split()[-1])) + "\n")
print("Staged both runtimes, hashes and notices into common-jni")
