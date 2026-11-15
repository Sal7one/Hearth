#!/usr/bin/env python3
"""Build and run Hearth's isolated native audio pipeline benchmark."""

import argparse
from contextlib import contextmanager
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import uuid


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUTPUT = ROOT / "build/benchmarks/audio-pipeline"
RUST = ["rustc", "+1.99.0"]
NDK_VERSION = "27.0.12077973"
SUITES = ("transport", "resampler", "meter", "timeline", "mix", "waveform", "pixels", "probe")
ANDROID_TARGETS = {
    # Rust target, NDK clang target prefix, ELF machine name.
    "android-arm64": ("aarch64-linux-android", "aarch64-linux-android", "AArch64"),
    "android-armv7": ("armv7-linux-androideabi", "armv7a-linux-androideabi", "ARM"),
    "android-x86": ("i686-linux-android", "i686-linux-android", "Intel 80386"),
    "android-x86_64": ("x86_64-linux-android", "x86_64-linux-android", "Advanced Micro Devices X86-64"),
}


def output_of(command, timeout=60):
    return subprocess.check_output(command, text=True, timeout=timeout).strip()


def android_sdk():
    configured = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    default = Path.home() / ("Library/Android/sdk" if platform.system() == "Darwin" else "Android/Sdk")
    return Path(configured) if configured else default


def ndk_tools(ndk):
    if ndk is None:
        ndk = Path(os.environ.get("ANDROID_NDK_HOME", str(android_sdk() / "ndk" / NDK_VERSION)))
    revision = ndk / "source.properties"
    if not revision.is_file() or f"Pkg.Revision = {NDK_VERSION}" not in revision.read_text().splitlines():
        raise ValueError(f"Use Hearth's NDK {NDK_VERSION}, found {ndk}")
    tags = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}
    tag = tags.get(platform.system())
    if tag is None:
        raise ValueError("Android cross-build requires macOS or Linux")
    return ndk / "toolchains" / "llvm" / "prebuilt" / tag / "bin"


def build(target, ndk):
    directory = OUTPUT / target
    directory.mkdir(parents=True, exist_ok=True)
    archive = directory / "libhearth_audio_experiment.a"
    executable = directory / "hearth-audio-bench"
    rust_flags = []
    link_flags = ["-pthread"]
    if target in ANDROID_TARGETS:
        rust_target, clang_target, elf_machine = ANDROID_TARGETS[target]
        tools = ndk_tools(ndk)
        compiler = str(tools / f"{clang_target}28-clang++")
        rust_flags = ["--target", rust_target]
        link_flags += ["-static-libstdc++", "-Wl,-z,max-page-size=16384", "-ldl", "-llog", "-lm"]
    else:
        compiler = os.environ.get("CXX", "clang++")
        if platform.system() == "Linux":
            link_flags += ["-ldl", "-lm"]
    subprocess.run(RUST + [
        "--edition", "2024", "--crate-name", "hearth_audio_experiment", "--crate-type", "staticlib",
        "-C", "opt-level=3", "-C", "codegen-units=1", "-C", "panic=abort",
        *rust_flags, HERE / "rust/src/lib.rs", "-o", archive,
    ], check=True, timeout=180)
    subprocess.run([
        compiler, "-std=c++17", "-O3", "-Wall", "-Wextra",
        HERE / "main.cpp", *(HERE / f"{name}.cpp" for name in SUITES),
        ROOT / "common-jni/src/main/cpp/common/stt_kit.cpp",
        archive, *link_flags, "-o", executable,
    ], check=True, timeout=180)
    if target in ANDROID_TARGETS:
        header = output_of([tools / "llvm-readelf", "-h", executable])
        loads = output_of([tools / "llvm-readelf", "-lW", executable])
        segments = [line.split() for line in loads.splitlines() if line.split()[:1] == ["LOAD"]]
        machine = next((line.split(":", 1)[1].strip() for line in header.splitlines()
                        if line.lstrip().startswith("Machine:")), "")
        if machine != elf_machine or not segments or any(int(row[-1], 16) < 16384 for row in segments):
            raise RuntimeError(f"Expected {elf_machine} ELF with 16 KiB-aligned load segments, found {machine}")
        if "libc++_shared.so" in output_of([tools / "llvm-readelf", "-d", executable]):
            raise RuntimeError("The standalone Android benchmark unexpectedly requires libc++_shared")
    return executable


def android_device(serial):
    adb = shutil.which("adb") or str(android_sdk() / "platform-tools/adb")
    states = [line.split() for line in output_of([adb, "devices"]).splitlines()[1:]]
    physical = []
    for fields in states:
        if len(fields) < 2 or fields[1] != "device" or fields[0].startswith("emulator-"):
            continue
        device = fields[0]
        if serial is not None and device != serial:
            continue
        command = [adb, "-s", device]
        if output_of(command + ["shell", "getprop", "ro.kernel.qemu"]) != "1":
            physical.append(command)
    if len(physical) != 1:
        raise RuntimeError("Connect and authorize one physical Android phone, or specify its adb serial")
    command = physical[0]
    abi_list = output_of(command + ["shell", "getprop", "ro.product.cpu.abilist64"]).split(",")
    if "arm64-v8a" not in abi_list:
        raise RuntimeError("The selected phone does not support arm64-v8a")
    return command


@contextmanager
def stage_android(executable, adb):
    """Stage the native runner in a unique shell-owned directory."""
    remote = f"/data/local/tmp/hearth-audio-bench-{uuid.uuid4().hex}"
    binary = remote + "/hearth-audio-bench"
    subprocess.run(adb + ["shell", "mkdir", remote], check=True, timeout=30)
    try:
        subprocess.run(adb + ["push", executable, binary], check=True, timeout=60)
        subprocess.run(adb + ["shell", "chmod", "700", binary], check=True, timeout=30)
        yield remote
    finally:
        subprocess.run(adb + ["shell", "rm", "-r", remote], check=False, timeout=30)


def run_android(executable, suite, repeats, serial):
    adb = android_device(serial)
    environment = {
        "platform": "Android",
        "device": output_of(adb + ["shell", "getprop", "ro.product.model"]),
        "android_version": output_of(adb + ["shell", "getprop", "ro.build.version.release"]),
        "architecture": "arm64-v8a",
    }
    with stage_android(executable, adb) as remote:
        binary = remote + "/hearth-audio-bench"
        runs = [output_of(adb + ["shell", binary, suite], timeout=180) for _ in range(repeats)]
    return environment, runs


def invalid_constant(value):
    raise ValueError(f"Non-finite benchmark result {value}")


def run(executable, args):
    if args.target == "android-arm64":
        environment, runs = run_android(executable, args.suite, args.repeat, args.serial)
    else:
        environment = {"platform": platform.system(), "version": platform.release(), "architecture": platform.machine()}
        if platform.system() == "Darwin":
            environment["processor"] = output_of(["sysctl", "-n", "machdep.cpu.brand_string"])
        runs = [output_of([executable, args.suite], timeout=180) for _ in range(args.repeat)]

    requested = set(SUITES) if args.suite == "all" else {args.suite}
    measurements = []
    for number, raw in enumerate(runs, 1):
        rows = [json.loads(line, parse_constant=invalid_constant) for line in raw.splitlines()]
        if {row.get("suite") for row in rows} != requested:
            raise RuntimeError(f"Run {number} did not produce every requested suite")
        measurements.append(rows)
        print(raw, flush=True)
    report = {
        "schema_version": 1,
        "target": args.target,
        "utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "binary_sha256": hashlib.sha256(executable.read_bytes()).hexdigest(),
        "environment": environment,
        "runs": measurements,
    }
    path = args.output or executable.parent / "results.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(f"Saved {path}", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("build", "run"))
    parser.add_argument("--target", choices=("host", *ANDROID_TARGETS), default="host")
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--serial")
    parser.add_argument("--suite", choices=("all", *SUITES), default="all")
    parser.add_argument("--repeat", type=int, default=3, choices=range(1, 11), metavar="1..10")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.action == "run" and args.target not in ("host", "android-arm64"):
        parser.error("Run supports the current desktop host and physical Android ARM64. Use build for the other Android ABIs.")
    try:
        executable = build(args.target, args.ndk)
        if args.action == "run":
            run(executable, args)
        else:
            print(executable)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Benchmark failed: {error}\n")


if __name__ == "__main__":
    main()
