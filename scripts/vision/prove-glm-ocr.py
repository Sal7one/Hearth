#!/usr/bin/env python3
"""Run Hearth's production CPU vision adapter with the exact official GLM Q8 files.

Requires Pillow, an existing pinned llama checkout, CMake/C++ and enough disk/RAM.
Weights/fixtures/builds remain outside Git. Downloads are explicit (--download).
This proves engine compatibility and one simple image, not phone/document accuracy.
"""
import argparse
import hashlib
import json
import platform
import re
import resource
import subprocess
import time
import urllib.request
from pathlib import Path

REVISION = "65a42de1148dbed2297e922b5dbc7d9b70c36578"
LLAMA_PIN = "64e9bceb2c3a856efed96feda784a50947049feb"
FILES = {
    "GLM-OCR-Q8_0.gguf": (950433408, "45bc244a6446aff850521dc41f18bc8d7105ad5f0c2c8c28af04e7cc4f4d50b1"),
    "mmproj-GLM-OCR-Q8_0.gguf": (484403648, "9c4b58e33e316ed142eb5dcb41abec3844d3e6e5dc361ffb782c3fa9d175141f"),
}

def inspect(path, size, digest):
    if not path.is_file() or path.stat().st_size != size:
        return False
    sha = hashlib.sha256()
    with path.open("rb") as input:
        for block in iter(lambda: input.read(1024 * 1024), b""):
            sha.update(block)
    return sha.hexdigest() == digest

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--download", action="store_true")
    parser.add_argument("--font", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    source = root / "build/translation-runtime/llama.cpp"
    actual_pin = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if actual_pin != LLAMA_PIN:
        raise RuntimeError("Use the exact pinned llama checkout; run scripts/translation/build-runtime.sh first")
    args.directory.mkdir(parents=True, exist_ok=True)
    for name, (size, digest) in FILES.items():
        path = args.directory / name
        if not inspect(path, size, digest):
            if not args.download:
                raise RuntimeError(f"Missing/invalid exact model file: {path}; use --download explicitly")
            temporary = path.with_suffix(path.suffix + ".partial")
            try:
                with urllib.request.urlopen(f"https://huggingface.co/ggml-org/GLM-OCR-GGUF/resolve/{REVISION}/{name}", timeout=60) as response, temporary.open("wb") as output:
                    total = 0
                    while block := response.read(1024 * 1024):
                        total += len(block)
                        if total > size:
                            raise RuntimeError(f"Publisher response exceeds pinned size: {name}")
                        output.write(block)
                if not inspect(temporary, size, digest):
                    raise RuntimeError(f"Publisher model checksum mismatch: {name}")
                temporary.replace(path)
            finally:
                temporary.unlink(missing_ok=True)
    from PIL import Image, ImageDraw, ImageFont
    image = Image.new("RGB", (640, 240), "white")
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(str(args.font), 52)
    draw.text((28, 35), "HELLO HEARTH", font=font, fill="black")
    draw.text((28, 115), "Local OCR 2026", font=font, fill="black")
    fixture = args.directory / "fixture.ppm"
    image.save(fixture)
    build = args.build or root / "build/vision-host"
    subprocess.run(["cmake", "-S", str(root / "scripts/translation"), "-B", str(build),
        "-DCMAKE_BUILD_TYPE=Release", f"-DLLAMA_SOURCE={source}",
        f"-DCPP={root / 'common-jni/src/main/cpp'}", "-DHEARTH_HOST_METAL=OFF"], check=True)
    subprocess.run(["cmake", "--build", str(build), "--target", "vision_smoke", "-j", "4"], check=True)
    command = [str(build / "vision_smoke"), *(str(args.directory / name) for name in FILES), str(fixture)]
    started = time.monotonic()
    result = subprocess.run(command, text=True, capture_output=True, timeout=300)
    print(result.stdout)
    if result.returncode:
        raise RuntimeError(result.stderr or f"Native probe exited {result.returncode}")
    match = re.search(r"TEXT_BEGIN\n(.*?)\nTEXT_END", result.stdout, re.S)
    if not match or match.group(1).strip() != "HELLO HEARTH\nLocal OCR 2026":
        raise RuntimeError("Actual native GLM decode did not match the two-line fixture")
    if "CANCEL_PASS" not in result.stdout:
        raise RuntimeError("Native cancellation check is missing")
    def metric(name):
        found = re.search(rf"^{name} ([0-9.]+)$", result.stdout, re.M)
        if not found:
            raise RuntimeError(f"Native probe is missing {name}")
        return float(found.group(1))
    record = {
        "llamaRevision": LLAMA_PIN, "modelRevision": REVISION,
        "files": {name: {"bytes": size, "sha256": digest} for name, (size, digest) in FILES.items()},
        "platform": platform.platform(), "machine": platform.machine(), "backend": "CPU", "threads": 4,
        "fixture": {"width": 640, "height": 240, "expected": "HELLO HEARTH\nLocal OCR 2026", "actual": match.group(1).strip()},
        "loadMs": metric("LOAD_MS"), "inferenceMs": metric("INFERENCE_MS"),
        "inFlightCancelMs": metric("INFLIGHT_CANCEL_MS"), "cancelledRequestPublishedText": False,
        "elapsedSeconds": time.monotonic() - started,
        "limitations": ["one synthetic English image", "concurrent build workload", "not physical Android performance or multilingual/document accuracy"],
        "sources": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted((root / "common-jni/src/main/cpp/vision").glob("*")) if path.is_file()},
    }
    # ru_maxrss for child processes is bytes on macOS, KiB on Linux. It is the
    # largest child including the preceding build, not an exact model working set.
    record["maxChildRssBytes"] = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss * (1 if platform.system() == "Darwin" else 1024)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    print(f"Production GLM-OCR native proof PASS: {args.output}")

if __name__ == "__main__":
    main()
