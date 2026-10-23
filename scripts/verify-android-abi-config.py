#!/usr/bin/env python3
"""Exercise the real finalized AGP ABI model and native task graphs without a device or clean."""
import argparse
import json
import zipfile
from native_abis import verify_elf
import os
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--build-switches", action="store_true", help="Also build and inspect real ARM64/x86 AAR switches without clean")
args = parser.parse_args()
if not __debug__:
    raise SystemExit("Verification requires Python assertions enabled; remove -O or PYTHONOPTIMIZE")

ROOT = Path(__file__).resolve().parents[1]
GRADLE = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
ALL = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
OUT = ROOT / "build/android-release-audit/abi-switches"
OUT.mkdir(parents=True, exist_ok=True)


def invoke(name, properties, tasks):
    result = subprocess.run([GRADLE, "--console=plain", *properties, *tasks], cwd=ROOT,
                            text=True, capture_output=True, timeout=180)
    (OUT / (name + ".log")).write_text(result.stdout + result.stderr)
    return result


cases = [
    ("default", [], ALL, "default"),
    ("ide-no-device", ["-Pandroid.injected.build.abi="], ALL, "default"),
    ("ide-arm64", ["-Pandroid.injected.build.abi=arm64-v8a"], {"arm64-v8a"}, "android.injected.build.abi"),
    ("ide-x86", ["-Pandroid.injected.build.abi=x86"], {"x86"}, "android.injected.build.abi"),
    ("ide-x86_64", ["-Pandroid.injected.build.abi=x86_64"], {"x86_64"}, "android.injected.build.abi"),
    ("ide-armv7", ["-Pandroid.injected.build.abi=armeabi-v7a"], {"armeabi-v7a"}, "android.injected.build.abi"),
    ("explicit-multiple", ["-PhearthAbis=arm64-v8a,x86_64"], {"arm64-v8a", "x86_64"}, "hearthAbis"),
    ("legacy-matching-ide", ["-Phearth.abis=arm64-v8a", "-Pandroid.injected.build.abi=arm64-v8a"], {"arm64-v8a"}, "hearth.abis"),
    ("ide-back-to-arm64", ["-Pandroid.injected.build.abi=arm64-v8a"], {"arm64-v8a"}, "android.injected.build.abi"),
    ("release-all", ["-PhearthAbis=arm64-v8a,armeabi-v7a,x86,x86_64"], ALL, "hearthAbis"),
]
for name, properties, expected, source in cases:
    result = invoke(name, properties, ["verifyAndroidAbiConfiguration"])
    if result.returncode:
        raise SystemExit(f"{name}: Gradle ABI verification failed; see {OUT / (name + '.log')}")
    model = json.loads((ROOT / "build/reports/android/abi-configuration.json").read_text())
    assert set(model["activeAbis"]) == expected and model["selectionSource"] == source, name
    modules = model["modules"]
    for module in (":app", ":common-jni", ":hearth-onnx-runtime"):
        assert set(modules[module]["packagingAbis"]) == expected, (name, module)
    assert set(modules[":common-jni"]["nativeAbis"]) == expected, name
    tts = expected & {"arm64-v8a"}
    assert set(modules[":hearth-tts"]["nativeAbis"]) == tts, name
    assert modules[":hearth-tts"]["nativeBuildEnabled"] == bool(tts), name
    if not tts:
        assert modules[":hearth-tts"]["jniDirectories"] == [], name
    (OUT / (name + ".json")).write_text(json.dumps(model, indent=2) + "\n")
    print(f"PASS {name}: {','.join(sorted(expected))}; TTS {'arm64-v8a' if tts else 'disabled'}", flush=True)

for index, value in enumerate(("arm64", "", "arm64-v8a,bogus", "arm64-v8a,")):
    result = invoke(f"invalid-{index}", [f"-PhearthAbis={value}"], ["verifyAndroidAbiConfiguration"])
    assert result.returncode and "must contain Android ABI names" in result.stdout + result.stderr, value
    print(f"PASS invalid ABI rejected: {value!r}", flush=True)

for name, properties in (
    ("explicit-conflict", ["-PhearthAbis=arm64-v8a", "-Pandroid.injected.build.abi=x86"]),
    ("legacy-conflict", ["-Phearth.abis=arm64-v8a", "-Pandroid.injected.build.abi=x86"]),
    ("partial-release-conflict", ["-PhearthAbis=arm64-v8a,x86_64", "-Pandroid.injected.build.abi=arm64-v8a"]),
):
    result = invoke(name, properties, [":app:assemblePlayQa", "--dry-run"])
    assert result.returncode and "Conflicting IDE ABI hint" in result.stdout + result.stderr, name
    print(f"PASS conflicting IDE/native selection rejected: {name}", flush=True)

for abi in ("armeabi-v7a", "x86", "x86_64"):
    result = subprocess.run(["cmake", "-S", str(ROOT / "hearth-tts/src/main/cpp"),
        "-B", str(OUT / ("rejected-tts-" + abi)), "-DANDROID=ON", f"-DANDROID_ABI={abi}"],
        text=True, capture_output=True, timeout=30)
    assert result.returncode and "Hearth TTS supports only arm64-v8a" in result.stdout + result.stderr, abi
    print(f"PASS direct CMake rejects unsupported TTS ABI: {abi}", flush=True)

# A resolved DSL report alone is insufficient: AGP must schedule only the matching native tasks.
for abi in ("arm64-v8a", "x86", "x86_64"):
    result = invoke("task-graph-" + abi, [f"-Pandroid.injected.build.abi={abi}"], [":app:assemblePlayQa", "--dry-run"])
    assert result.returncode == 0, abi
    native = re.findall(r":(common-jni|hearth-tts):buildCMake[^\[]*\[([^\]]+)\]", result.stdout)
    assert ("common-jni", abi) in native, (abi, native)
    assert all(selected == abi for _, selected in native), (abi, native)
    assert any(module == "hearth-tts" for module, _ in native) == (abi == "arm64-v8a"), (abi, native)
    print(f"PASS real AGP native task graph: {abi}", flush=True)

for name, properties, expected in (
    ("explicit-multiple", ["-PhearthAbis=arm64-v8a,x86_64"], {"arm64-v8a", "x86_64"}),
    ("explicit-matching-ide", ["-PhearthAbis=arm64-v8a", "-Pandroid.injected.build.abi=arm64-v8a"], {"arm64-v8a"}),
):
    result = invoke("task-graph-" + name, properties, [":app:assemblePlayQa", "--dry-run"])
    assert result.returncode == 0, name
    native = re.findall(r":(common-jni|hearth-tts):buildCMake[^\[]*\[([^\]]+)\]", result.stdout)
    assert {abi for module, abi in native if module == "common-jni"} == expected, (name, native)
    assert {abi for module, abi in native if module == "hearth-tts"} == expected & {"arm64-v8a"}, (name, native)
    print(f"PASS real AGP native task graph: {name}", flush=True)

if args.build_switches:
    # Inspect archive bytes, not old CMake folders or the requested Gradle flags.
    for index, abi in enumerate(("arm64-v8a", "x86", "arm64-v8a")):
        name = f"aar-switch-{index}-{abi}"
        result = invoke(name, [f"-PhearthAbis={abi}", "--max-workers=2"], [
            ":common-jni:assembleDebug", ":hearth-tts:assembleRelease", ":hearth-onnx-runtime:assembleRelease"])
        assert result.returncode == 0, f"{name}: actual AAR build failed; see receipt log"
        archives = {}
        for module, variant in (("common-jni", "debug"), ("hearth-tts", "release"), ("hearth-onnx-runtime", "release")):
            path = ROOT / module / "build/outputs/aar" / f"{module}-{variant}.aar"
            with zipfile.ZipFile(path) as archive:
                native = sorted(n for n in archive.namelist() if n.startswith("jni/") and n.endswith(".so"))
                actual = {n.split("/")[1] for n in native}
                expected = set() if module == "hearth-tts" and abi != "arm64-v8a" else {abi}
                assert actual == expected, (name, module, actual, expected)
                for member in native:
                    with archive.open(member) as binary: verify_elf(abi, binary.read(64))
                if module == "common-jni": assert f"jni/{abi}/libcommon_jni.so" in native
                if module == "hearth-tts":
                    assert "classes.jar" in archive.namelist()
                    if abi == "arm64-v8a":
                        assert f"jni/{abi}/libhearth_supertonic_jni.so" in native
                        assert f"jni/{abi}/libming_jni.so" in native
                archives[module] = {"abis": sorted(actual), "nativeLibraries": native}
        (OUT / (name + ".json")).write_text(json.dumps(archives, indent=2) + "\n")
        print(f"PASS actual AAR switch without clean: {abi}; ELF identities verified", flush=True)

# Restore the explicit full release configuration after the switches, without deleting caches.
result = invoke("final-release", ["-PhearthAbis=arm64-v8a,armeabi-v7a,x86,x86_64"], ["verifyAndroidAbiConfiguration"])
assert result.returncode == 0
print(f"Android ABI switch verification passed without clean. Receipts: {OUT}")
