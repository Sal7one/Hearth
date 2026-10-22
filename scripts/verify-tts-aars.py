#!/usr/bin/env python3
"""Verify the merged TTS SDK and shared ORT boundaries in actual Release AARs."""
from pathlib import Path
import io
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
MODULES = ["hearth-tts", "hearth-onnx-runtime"]
FAMILIES = ("magpie", "omnivoice", "moss", "oute", "chatterbox")
TTS_NATIVE = {"libhearth_supertonic_jni.so", "libming_jni.so", "libming_jni_scalar.so", "libc++_shared.so"} | {
    f"libhearth_{family}{suffix}.so" for family in FAMILIES for suffix in ("", "_jni")
}
JNI_CLASSES = {f"com.sal7one.common_jni.voice.{family}Native" for family in
               ("Supertonic", "Magpie", "OmniVoice", "Moss", "Oute", "Chatterbox")} | {"com.sal7one.common_jni.MingNative"}
CALLBACKS = {f"com.sal7one.common_jni.voice.{family}PcmCallback": "short" if family == "Magpie" else "float"
             for family in ("Magpie", "OmniVoice", "Moss", "Oute", "Chatterbox")}
SDK_CLASSES = {"com/sal7one/hearth/tts/SpeechEngine.class", "com/sal7one/hearth/tts/ArticleReader.class"} | {
    name.replace(".", "/") + ".class" for name in JNI_CLASSES
} | {f"com/sal7one/hearth/tts/{family}/{name}SpeechEngine.class" for family, name in (
    ("supertonic", "Supertonic"), ("magpie", "Magpie"), ("omnivoice", "OmniVoice"),
    ("moss", "Moss"), ("oute", "Oute"), ("chatterbox", "Chatterbox"), ("ming", "Ming"))}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_archive(module, path):
    require(module in MODULES, f"Unknown SDK archive: {module}")
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), f"Duplicate AAR members: {module}")
        native = {name for name in names if name.startswith("jni/") and name.endswith(".so")}
        ort = {f"jni/{abi}/libonnxruntime.so" for abi in ABIS}
        expected = ort if module == "hearth-onnx-runtime" else {f"jni/arm64-v8a/{name}" for name in TTS_NATIVE}
        require(native == expected, f"{module} native inventory mismatch; missing={sorted(expected-native)}, unexpected={sorted(native-expected)}")
        require(not any(name.endswith((".gguf", ".safetensors", ".onnx", ".pth", ".bin")) for name in names), f"Model weights leaked into {module}")
        require("classes.jar" in names, f"Missing classes.jar: {module}")
        with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
            entries = set(classes.namelist())
            require(not any(name.startswith("com/sal7one/transiber/") for name in entries), f"Hearth app code leaked into {module}")
            require(not any(name.startswith(("com/sal7one/common_jni/speech/", "com/sal7one/common_jni/translation/")) for name in entries), f"Broad speech/translation dependency leaked into {module}")
            if module == "hearth-tts":
                require(SDK_CLASSES <= entries, f"Merged TTS adapter/API classes missing: {sorted(SDK_CLASSES-entries)}")
        if module == "hearth-tts":
            require("proguard.txt" in names, "Merged TTS AAR has no consumer shrinker rules")
            rules = archive.read("proguard.txt").decode("utf-8")
            rules = "\n".join(line.split("#", 1)[0] for line in rules.splitlines())
            compact = re.sub(r"\s+", "", rules)
            for name in JNI_CLASSES:
                require(f"-keepclass{name}{{*;}}" in compact, f"Missing consumer JNI keep rule: {name}")
            for name, sample in CALLBACKS.items():
                require(f"-keepinterface{name}{{*;}}" in compact, f"Missing consumer callback keep rule: {name}")
                require(f"-keepclassmembersclass*implements{name}{{booleanonPcm({sample}[],int);}}" in compact,
                        f"Missing consumer callback implementation keep rule: {name}")
    return len(native)


def main():
    for module in MODULES:
        path = ROOT / module / "build/outputs/aar" / f"{module}-release.aar"
        count = verify_archive(module, path)
        print(f"{module}: {path.stat().st_size:,} bytes; {count} native libraries; distribution boundary verified")


if __name__ == "__main__":
    main()
