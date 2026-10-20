#!/usr/bin/env python3
"""Verify optional SDK distribution boundaries in the actual Release AAR files."""
from pathlib import Path
import io
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
MODULES = ["hearth-tts", "hearth-onnx-runtime", "hearth-tts-supertonic", "hearth-tts-magpie",
           "hearth-tts-omnivoice", "hearth-tts-moss", "hearth-tts-oute", "hearth-tts-chatterbox"]


def main():
    for module in MODULES:
        path = ROOT / module / "build/outputs/aar" / f"{module}-release.aar"
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            assert len(names) == len(set(names)), f"Duplicate AAR members: {module}"
            native = {name for name in names if name.startswith("jni/") and name.endswith(".so")}
            ort = {f"jni/{abi}/libonnxruntime.so" for abi in ABIS}
            if module == "hearth-onnx-runtime":
                assert native == ort, f"Shared ONNX runtime inventory changed: {module}"
            else:
                assert not native & ort, f"{module} duplicates the shared ONNX runtime"
                if module == "hearth-tts":
                    assert not native, "The core SDK must remain native/model free"
                else:
                    assert native, f"Missing adapter runtime: {module}"
            assert not any(name.endswith((".gguf", ".safetensors", ".onnx", ".pth", ".bin")) for name in names), f"Model weights leaked into {module}"
            if "classes.jar" in names:
                with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
                    entries = classes.namelist()
                    assert not any(name.startswith("com/sal7one/transiber/") for name in entries), f"Hearth app code leaked into {module}"
                    assert not any(name.startswith(("com/sal7one/common_jni/speech/", "com/sal7one/common_jni/translation/")) for name in entries), f"Broad speech/translation dependency leaked into {module}"
            print(f"{module}: {path.stat().st_size:,} bytes; {len(native)} native libraries; distribution boundary verified")


if __name__ == "__main__":
    main()
