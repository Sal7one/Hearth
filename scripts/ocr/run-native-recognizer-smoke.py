#!/usr/bin/env python3
"""Real-weight host smoke of production C++ detector/crop/recognizer/CTC code.

Requires Pillow + numpy and a CPU ONNX Runtime shared library on macOS/Linux.
Run verify-recognizers.py first; supply its model cache and the pinned detector.
Every new recognition profile also advertises English, so a generated English
fixture is a shared integration check, not a multilingual accuracy benchmark.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--onnx-library", type=Path, required=True)
    parser.add_argument("--ids", default="english,korean,greek,thai,devanagari,tamil,telugu")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    import numpy as np
    from PIL import Image, ImageDraw, ImageFont
    root = Path(__file__).resolve().parents[2]
    dictionaries = root / "common-jni/src/main/assets/ocr"
    catalog = {entry["id"]: entry for entry in json.loads((dictionaries / "provenance.json").read_text())}
    detector = args.directory / "detector.onnx"
    if detector.stat().st_size != 4826518 or hashlib.sha256(detector.read_bytes()).hexdigest() != "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d":
        raise RuntimeError("Detector pin mismatch")
    library = args.onnx_library.resolve(strict=True)
    results = []
    with tempfile.TemporaryDirectory(prefix="hearth-native-ocr-") as temporary:
        temporary = Path(temporary)
        executable = temporary / "recognizer-smoke"
        subprocess.run([os.environ.get("CXX", "c++"), "-std=c++17", "-Wall", "-Wextra", "-fsanitize=address,undefined",
                        str(root / "common-jni/src/main/cpp/ocr/tests/recognizer_smoke.cpp"),
                        str(root / "common-jni/src/main/cpp/ocr/paddle_ocr.cpp"), str(library),
                        f"-Wl,-rpath,{library.parent}", "-o", str(executable)], check=True)
        expected = "HELLO HEARTH"
        image = Image.new("RGB", (1000, 180), "white")
        ImageDraw.Draw(image).text((50, 45), expected, font=ImageFont.load_default(size=64), fill="black")
        pixels = np.asarray(image, dtype=np.uint32)
        packed = ((255 << 24) | (pixels[:, :, 0] << 16) | (pixels[:, :, 1] << 8) | pixels[:, :, 2]).astype(np.uint32)
        fixture = temporary / "hello.argb"
        fixture.write_bytes(packed.tobytes())
        for model_id in args.ids.split(","):
            entry = catalog[model_id]
            model = args.directory / f"{model_id}.onnx"
            dictionary = dictionaries / f"{model_id}.json"
            if model.stat().st_size != entry["model_bytes"] or hashlib.sha256(model.read_bytes()).hexdigest() != entry["model_sha256"]:
                raise RuntimeError(f"Recognizer pin mismatch: {model_id}")
            if hashlib.sha256(dictionary.read_bytes()).hexdigest() != entry["dictionary_sha256"]:
                raise RuntimeError(f"Dictionary pin mismatch: {model_id}")
            vocabulary = json.loads(dictionary.read_text())
            output = subprocess.run([str(executable), str(detector), str(model), str(len(vocabulary)), str(fixture), "1000", "180"],
                                    text=True, capture_output=True, check=True)
            lines = json.loads(output.stdout)
            actual = "\n".join("".join(vocabulary[token] for token in line["tokens"]) for line in lines)
            if actual != expected:
                raise RuntimeError(f"Native fixture mismatch {model_id}: expected {expected!r}, got {actual!r}")
            results.append({"id": model_id, "expected": expected, "actual": actual,
                            "confidence": [line["confidence"] for line in lines]})
            print(f"{model_id}: native detection + crop + recognition + CTC = {actual!r}")
    if args.report:
        args.report.write_text(json.dumps({"sanitizers": ["address", "undefined"], "recognizers": results}, indent=2) + "\n")


if __name__ == "__main__":
    main()
