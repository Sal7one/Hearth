#!/usr/bin/env python3
"""Check the self-instrumenting JNI test APK before starting an emulator."""
from pathlib import Path
import zipfile

from native_abis import ABIS, verify_elf


def verify_test_apk(apk):
    required = ("libcommon_jni.so", "libonnxruntime.so", "libc++_shared.so")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate members in the JNI test APK")
        for abi in ABIS:
            for library in required:
                member = f"lib/{abi}/{library}"
                if member not in names:
                    raise ValueError(f"JNI test APK is missing {member}; its test process cannot load native engines")
                with archive.open(member) as stream:
                    verify_elf(abi, stream.read(64))
    return ABIS


def main():
    root = Path(__file__).resolve().parents[1]
    apk = root / "common-jni/build/outputs/apk/androidTest/debug/common-jni-debug-androidTest.apk"
    for abi in verify_test_apk(apk):
        print(f"{abi}: JNI test APK includes its common/ONNX/C++ runtime dependencies")


if __name__ == "__main__":
    main()
