#!/usr/bin/env python3
"""Publish explicit ARM64-only STT GPU plugins with the actual build snapshot."""
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path

repo = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(repo / "scripts"))
from native_abis import source_set_digest, update_checksums, verify_elf

acceleration = sys.argv[1] if len(sys.argv) > 1 else "vulkan"
if acceleration not in ("vulkan", "opencl"):
    raise SystemExit("Expected vulkan or opencl")
work = Path(os.environ.get("HEARTH_SPEECH_BUILD_DIR", repo / "build/speech-runtimes"))
output = work / f"android-{acceleration}-arm64-v8a"
ndk = Path(os.environ.get("ANDROID_NDK_HOME", Path.home() / "Library/Android/sdk/ndk/27.0.12077973"))
host = "darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64"
subprocess.run([sys.executable, str(repo / "scripts/speech/verify-android-runtimes.py"),
                str(output / "lib"), str(ndk / "toolchains/llvm/prebuilt" / host / "bin"), "arm64-v8a"], check=True)
artifacts = json.loads((output / "runtime-artifacts.json").read_text())
name = f"libhearth_transcribepsy_{acceleration}.so"
if [item["file"] for item in artifacts] != [name]:
    raise RuntimeError("GPU publication requires the single explicit optional plugin")
sources = json.loads((output / "build-sources.json").read_text())
for path, digest in sources.items():
    if hashlib.sha256((repo / path).read_bytes()).hexdigest() != digest:
        raise RuntimeError(f"Speech source changed after GPU compilation: {path}")
artifact = dict(artifacts[0], acceleration=acceleration, sourceSetSha256=source_set_digest(sources))
verify_elf("arm64-v8a", (output / "lib" / name).read_bytes())
destination = repo / "common-jni/src/main/jniLibs/arm64-v8a" / name
shutil.copyfile(output / "lib" / name, destination)
assets = repo / "common-jni/src/main/assets/licenses/speech"
record_path = assets / "gpu-runtime-build.json"
previous = json.loads(record_path.read_text()) if record_path.exists() else {}
records = [item for item in previous.get("artifacts", []) if item["acceleration"] != acceleration] + [artifact]
dependencies = dict(previous.get("dependencies", {}))
build_deps = repo / "build/translation-runtime"
pins = {"OpenCL-Headers": "30bc20a8e90468e231d7c639805ae61ad1fefa4f"} if acceleration == "opencl" else {
    "Vulkan-Headers": "19725e4d48082fe78e26622b15d3080ccd54112b",
    "SPIRV-Headers": "01e0577914a75a2569c846778c2f93aa8e6feddd"}
for dependency, expected in pins.items():
    actual = subprocess.check_output(["git", "-C", str(build_deps / dependency), "rev-parse", "HEAD"], text=True).strip()
    if actual != expected:
        raise RuntimeError(f"Unexpected {dependency} revision")
    dependencies[dependency] = expected
    notices = assets / dependency
    notices.mkdir(exist_ok=True)
    for item in (build_deps / dependency).glob("LICENSE*"):
        if item.is_file(): shutil.copy2(item, notices / item.name)
        elif item.is_dir(): shutil.copytree(item, notices / item.name, dirs_exist_ok=True)
record = {"abi": 2, "architectures": ["arm64-v8a"], "adapterSources": sources,
          "pins": dict(re.findall(r'#define (HEARTH_\w+_REVISION) "([a-f0-9]+)"',
                    (repo / "common-jni/src/main/cpp/speech/backend_versions.h").read_text())),
          "artifacts": sorted(records, key=lambda item: item["file"]), "dependencies": dependencies,
          "selection": "Explicit opt-in; CPU default does not load GPU plugins; reject CPU fallback; mel remains CPU"}
if "shaderCompiler" in previous: record["shaderCompiler"] = previous["shaderCompiler"]
if acceleration == "vulkan":
    cache = (output / "transcribepsy/CMakeCache.txt").read_text().splitlines()
    glslc = Path(next(line.split("=", 1)[1] for line in cache if line.startswith("Vulkan_GLSLC_EXECUTABLE:")))
    record["shaderCompiler"] = {"sha256": hashlib.sha256(glslc.read_bytes()).hexdigest(),
        "version": subprocess.check_output([glslc, "--version"], text=True).strip()}
record_path.write_text(json.dumps(record, indent=2) + "\n")
update_checksums(repo / "common-jni/src/main/jniLibs")
print(destination)
