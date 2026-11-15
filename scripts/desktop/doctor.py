#!/usr/bin/env python3
"""Check this computer for a Hearth desktop build and say exactly what is missing.

Reading only: tool versions, the Windows registry and the build tree. Nothing is
downloaded, installed or changed. `--run` then executes the build steps that are
still to do, in order, with the same commands docs/desktop.md lists, and stops at
the first one that fails.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OK, MISSING, ABSENT = "ok", "missing", "absent"
REQUIRED, GPU = "Required", "GPU (optional)"
CMAKE_MINIMUM = (3, 22)
PYTHON_MINIMUM = (3, 11)
RUST_TOOLCHAIN = "1.99.0"
COMPILE_JDK, PACKAGING_JDK = 17, 21
GRADLE, GRADLE_WINDOWS = "./gradlew", ".\\gradlew.bat"
TARGETS = {("Windows", "amd64"): "windows-x64", ("Windows", "x86_64"): "windows-x64",
           ("Darwin", "arm64"): "macos-arm64", ("Darwin", "aarch64"): "macos-arm64", ("Darwin", "x86_64"): "macos-x64"}


def sibling(filename):
    """The build scripts have hyphenated names; load one to reuse its rules instead of restating them."""
    specification = importlib.util.spec_from_file_location("hearth_" + re.sub(r"\W", "_", filename), Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def version(text):
    """The first dotted number in a tool's version line, as a tuple of integers."""
    match = re.search(r"\d+(?:\.\d+)+|\d+", text or "")
    return tuple(int(part) for part in match[0].split(".")) if match else ()


def java_major(release):
    """The major version from a JDK's JAVA_VERSION; Java 8 and older wrote it as 1.8."""
    numbers = version(release)
    return (numbers[1] if numbers[:1] == (1,) and len(numbers) > 1 else numbers[0]) if numbers else None


def dotted(numbers):
    return ".".join(map(str, numbers))


class Finding:
    def __init__(self, group, name, state, detail, fix=None):
        self.group, self.name, self.state, self.detail, self.fix = group, name, state, detail, fix

    def record(self):
        return {key: value for key, value in vars(self).items() if value is not None}


class Step:
    def __init__(self, title, command, done, always=False):
        self.title, self.command, self.done, self.always = title, command, done, always

    def record(self):
        return {"title": self.title, "command": self.command, "done": self.done}


class Host:
    """Everything the checks look at. Tests replace these methods; none of them changes the computer."""

    def __init__(self, root=ROOT, environment=None, system=None, machine=None):
        self.root = Path(root)
        self.environment = dict(os.environ if environment is None else environment)
        self.system = system or platform.system()
        self.machine = (machine or platform.machine()).lower()

    @property
    def target(self):
        return TARGETS.get((self.system, self.machine))

    @property
    def windows(self):
        return self.system == "Windows"

    def python(self):
        return tuple(sys.version_info[:3])

    def cores(self):
        return os.cpu_count() or 2

    def search_path(self):
        # rustup installs here without always updating the PATH of an open terminal; the build looks here too.
        cargo = str(Path.home() / ".cargo" / "bin")
        path = self.environment.get("PATH", "")
        return path if cargo.lower() in path.lower() else path + os.pathsep + cargo

    def which(self, name):
        return shutil.which(name, path=self.search_path())

    def output(self, command, timeout=30):
        """A command's combined output, or None when it cannot be run or fails."""
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=timeout, env=self.environment)
        except (OSError, subprocess.SubprocessError):
            return None
        return (result.stdout + result.stderr).strip() if result.returncode == 0 else None

    def tool(self, name, *arguments):
        """The output of a tool found on the search path, or None when it is not installed."""
        program = self.which(name)
        return self.output([program, *arguments]) if program else None

    def jdk_homes(self):
        """Where Gradle looks for installed JDKs: JAVA_HOME, PATH, the vendors' folders and the per-user JDK stores."""
        homes = [self.environment.get("JAVA_HOME")]
        java = self.which("java")
        if java:
            homes.append(str(Path(java).resolve().parent.parent))
        nested = ""
        if self.windows:
            program_files = Path(self.environment.get("ProgramFiles", r"C:\Program Files"))
            stores = [program_files / vendor for vendor in ("Amazon Corretto", "Java", "Eclipse Adoptium", "Microsoft", "Zulu", "BellSoft")]
        else:
            nested = "Contents/Home"
            stores = [Path("/Library/Java/JavaVirtualMachines"), Path.home() / "Library/Java/JavaVirtualMachines"]
        for store in stores:
            if store.is_dir():
                homes += [str(home / nested) for home in sorted(store.iterdir())]
        for store in (Path.home() / ".jdks", Path.home() / ".gradle/jdks", Path.home() / ".sdkman/candidates/java"):
            if store.is_dir():
                homes += [str(candidate) for home in sorted(store.iterdir()) for candidate in (home, home / "Contents/Home")]
        return [home for home in homes if home]

    def jdks(self):
        """Installed JDKs as {major: home}, read from each JDK's own release file."""
        found = {}
        for home in self.jdk_homes():
            try:
                match = re.search(r'^JAVA_VERSION="([^"]+)"', (Path(home) / "release").read_text(encoding="utf-8", errors="replace"), re.MULTILINE)
            except OSError:
                continue
            major = java_major(match[1]) if match else None
            if major:
                found.setdefault(major, str(Path(home)))
        return found

    def visual_studio(self):
        """The newest Visual Studio with the x64 C++ tools, as (name, path), or None."""
        vswhere = Path(self.environment.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Microsoft Visual Studio/Installer/vswhere.exe"
        if not vswhere.is_file():
            return None
        text = self.output([str(vswhere), "-latest", "-products", "*", "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-format", "json", "-utf8"])
        try:
            installed = json.loads(text or "[]")
        except ValueError:
            return None
        return (installed[0].get("displayName", "Visual Studio"), installed[0].get("installationPath", "")) if installed else None

    def windows_sdk(self):
        include = Path(self.environment.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Windows Kits/10/Include"
        versions = sorted(path.name for path in include.iterdir() if (path / "um/Windows.h").is_file()) if include.is_dir() else []
        return versions[-1] if versions else None

    def nvidia_gpus(self):
        """Lines of "name, driver, memory" from the driver's own utility in System32, never one found on PATH."""
        utility = Path(self.environment.get("SystemRoot", r"C:\Windows")) / "System32/nvidia-smi.exe"
        if not utility.is_file():
            return []
        text = self.output([str(utility), "--query-gpu=name,driver_version,memory.total", "--format=csv,noheader"])
        return [line.strip() for line in (text or "").splitlines() if line.strip()]

    def cuda_toolkit(self):
        """(root, version, None) for a toolkit the CUDA stager accepts, else (None, None, its reason)."""
        try:
            root, toolkit, _ = sibling("stage-cuda-runtime.py").resolve_toolkit()
            return str(root), toolkit, None
        except (ValueError, OSError) as error:
            return None, None, str(error)

    def cuda_runtime_folders(self):
        """Folders on PATH or in CUDA_PATH that hold CUDA runtime DLLs but no compiler: another program's copies."""
        names = [value for key, value in self.environment.items() if key.upper().startswith("CUDA_PATH")]
        folders, seen = [], set()
        for entry in self.environment.get("PATH", "").split(os.pathsep) + names + [str(Path(name) / "bin") for name in names]:
            folder = Path(entry) if entry else None
            if folder is None or str(folder).lower() in seen or not folder.is_dir():
                continue
            seen.add(str(folder).lower())
            try:
                runtime = any(re.fullmatch(r"cudart64_\d+\.dll", item.name.lower()) for item in folder.iterdir())
            except OSError:
                continue
            if runtime and not (folder / "nvcc.exe").is_file():
                folders.append(str(folder))
        return folders


def required(host):
    findings = []

    def add(name, good, detail, fix=None):
        findings.append(Finding(REQUIRED, name, OK if good else MISSING, detail, None if good else fix))

    windows = host.windows
    add("Computer", host.target is not None, host.target or f"{host.system} {host.machine} is not a desktop target",
        "Hearth desktop builds on Windows x64 and macOS")
    python = host.python()
    add("Python", python >= PYTHON_MINIMUM, dotted(python), f"Install Python {dotted(PYTHON_MINIMUM)} or newer")
    git = version(host.tool("git", "--version"))
    add("Git", bool(git), dotted(git) or "not found", "winget install Git.Git" if windows else "xcode-select --install")
    cmake = version(host.tool("cmake", "--version"))
    add("CMake", cmake >= CMAKE_MINIMUM, dotted(cmake) or "not found",
        f"{'winget install Kitware.CMake' if windows else 'brew install cmake'} (needs {dotted(CMAKE_MINIMUM)} or newer)")
    jdks = host.jdks()
    for major, purpose in ((COMPILE_JDK, "compiles the app"), (PACKAGING_JDK, "packages the installer")):
        add(f"JDK {major}", major in jdks, jdks.get(major, f"not found; it {purpose}"),
            f"winget install Amazon.Corretto.{major}.JDK" if windows else f"brew install --cask corretto@{major}")
    if windows:
        studio = host.visual_studio()
        add("Visual Studio C++ tools", studio is not None, f"{studio[0]} at {studio[1]}" if studio else "not found",
            'winget install Microsoft.VisualStudio.2022.BuildTools --override "--add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"')
        sdk = host.windows_sdk()
        add("Windows SDK", sdk is not None, sdk or "not found", "Add a Windows 11 SDK in the Visual Studio Installer")
    elif host.system == "Darwin":
        clang = host.tool("xcrun", "--find", "clang")
        add("Xcode command line tools", clang is not None, clang or "not found", "xcode-select --install")
    rust = host.tool("cargo", "--version")
    triple = re.search(r"^host: (\S+)", host.tool("rustc", "-vV") or "", re.MULTILINE)
    msvc = not windows or (triple is not None and triple[1] == "x86_64-pc-windows-msvc")
    add("Rust", rust is not None and triple is not None and msvc,
        f"{dotted(version(rust))} ({triple[1]})" if rust and triple else "cargo and rustc not found",
        "rustup default stable-x86_64-pc-windows-msvc (the build links with Visual Studio)" if rust and triple
        else "winget install Rustlang.Rustup" if windows else "brew install rustup")
    toolchains = host.tool("rustup", "toolchain", "list")
    pinned = toolchains is not None and any(line.startswith(RUST_TOOLCHAIN) for line in toolchains.splitlines())
    add(f"Rust {RUST_TOOLCHAIN} toolchain", pinned, "installed" if pinned else "not installed; the Ming voice stage is pinned to it",
        f"rustup toolchain install {RUST_TOOLCHAIN} --profile minimal")
    return findings


def gpu(host):
    """What this computer offers for GPU builds. Nothing here is required: every engine also runs on the CPU."""
    if host.system == "Darwin":
        return [Finding(GPU, "Apple Metal", OK, "compiled by default on macOS; no setup")]
    if not host.windows:
        return []
    findings = []
    cards = host.nvidia_gpus()
    findings.append(Finding(GPU, "NVIDIA GPU and driver", OK if cards else ABSENT,
                            "; ".join(cards) if cards else "no NVIDIA driver found; CUDA builds would not run here"))
    root, toolkit, reason = host.cuda_toolkit()
    if root:
        findings.append(Finding(GPU, "NVIDIA CUDA Toolkit", OK, f"{toolkit} at {root}; add --cuda to build-native.py to use it (docs/desktop-cuda.md)"))
    else:
        findings.append(Finding(GPU, "NVIDIA CUDA Toolkit", ABSENT, f"none the build accepts ({reason})",
                                "winget install Nvidia.CUDA (asks for administrator approval; needed only to compile a CUDA build)"))
    for folder in host.cuda_runtime_folders():
        findings.append(Finding(GPU, "CUDA runtime copy", ABSENT,
                                f"{folder} holds another program's CUDA runtime files without the compiler; Hearth cannot build with it and never bundles it"))
    return findings


def steps(host):
    """The build, in order, with each step marked done when its result is present in the tree."""
    target, root, windows = host.target, host.root, host.windows
    python = "python" if windows else "python3"
    dependencies = root / "build/desktop-dependencies"
    archive = sibling("prepare-dependencies.py").ASSETS[target][0]
    runtime = "build/desktop-dependencies/" + re.sub(r"\.(zip|tgz)$", "", archive)
    bundle = root / "desktop-runtime/native-libs" / target
    try:
        manifest = json.loads((bundle / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        manifest = {}
    built = manifest.get("platform") == target
    helper = root / "desktop-media/native-libs" / target / ("hearth-media-decode.exe" if windows else "hearth-media-decode")
    images = root / "desktopApp/build/compose/binaries/main/app"
    image = images / "Hearth/Hearth.exe" if windows else images / "Hearth.app"
    plan = [
        Step("Fetch the pinned sources and the ONNX Runtime SDK", [python, "scripts/desktop/prepare-dependencies.py"],
             all((dependencies / name / ".git").is_dir() for name in ("llama.cpp", "sherpa-onnx")) and (root / runtime).is_dir()),
        Step("Build the native engines", [python, "scripts/desktop/build-native.py", "--stage", "all", "--llama-root", "build/desktop-dependencies/llama.cpp",
                                          "--ort-root", runtime, "--jobs", str(4 if host.cores() >= 8 else 2)], built),
    ]
    if windows:
        plan.append(Step("Stage the Microsoft C++ runtime", [python, "scripts/desktop/stage-windows-crt.py"], built and "microsoftCrt" in manifest))
        plan.append(Step("Build the media helpers", ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts/desktop/build-media.ps1"], helper.is_file()))
    else:
        plan.append(Step("Build the media helpers", ["bash", "scripts/desktop/build-media.sh"], helper.is_file()))
    plan.append(Step("Package the app", [GRADLE_WINDOWS if windows else GRADLE, "-PhearthDesktopOnly=true", ":desktopApp:createDistributable"], image.exists()))
    plan.append(Step("Check the packaged app", [python, "scripts/desktop/check-packaged.py"], False, always=True))
    return plan


def inspect(host):
    findings = required(host) + gpu(host)
    ready = all(finding.state != MISSING for finding in findings)
    return findings, (steps(host) if host.target else []), ready


def report(host, findings, plan, ready):
    lines = [f"Hearth desktop build check: {host.target or 'unsupported computer'}", ""]
    labels = {OK: "ok", MISSING: "MISSING", ABSENT: "not here"}
    for group in (REQUIRED, GPU):
        members = [finding for finding in findings if finding.group == group]
        if not members:
            continue
        lines.append(group)
        for finding in members:
            lines.append(f"  {labels[finding.state]:<9}{finding.name}: {finding.detail}")
            if finding.fix:
                lines.append(f"           fix: {finding.fix}")
        lines.append("")
    if plan:
        lines.append("Build steps")
        for step in plan:
            state = "run last" if step.always else "done" if step.done else "to do"
            lines.append(f"  {state:<9}{step.title}")
            if not step.done:
                lines.append(f"           {subprocess.list2cmdline(step.command)}")
        lines.append("")
    if not ready:
        lines.append("Install what is marked MISSING, then run this check again.")
    elif any(not step.done and not step.always for step in plan):
        lines.append("Ready to build. Run the steps marked \"to do\" in order, or let this script do it: doctor.py --run")
    else:
        lines.append("Everything is built. \"done\" means the result is present, not that it matches your latest changes; --run --all rebuilds every step.")
    return "\n".join(lines)


def run(host, plan, everything=False, execute=None):
    """Runs the steps still to do, in order. Returns 0, or the exit code of the first step that fails."""
    execute = execute or (lambda command: subprocess.run(command, cwd=host.root).returncode)
    for step in plan:
        if step.done and not everything and not step.always:
            continue
        command = list(step.command)
        if command[0] in ("python", "python3"):
            program = sys.executable  # the interpreter that passed the check, not whichever is first on PATH
        elif command[0] in (GRADLE, GRADLE_WINDOWS):
            program = str(host.root / ("gradlew.bat" if host.windows else "gradlew"))
        else:
            program = host.which(command[0]) or command[0]
        print(f"\n== {step.title}\n   {subprocess.list2cmdline(command)}", flush=True)
        code = execute([program] + command[1:])
        if code:
            print(f"\n{step.title} failed with exit code {code}. Its output above is the reason; later steps were not run.", flush=True)
            return code
    return 0


def main(arguments=None, host=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--json", action="store_true", help="Print the findings and steps as JSON")
    parser.add_argument("--run", action="store_true", help="Run the build steps that are still to do, in order")
    parser.add_argument("--all", action="store_true", help="With --run: run every step, including those already done")
    args = parser.parse_args(arguments)
    if args.all and not args.run:
        parser.error("--all needs --run")
    host = host or Host()
    findings, plan, ready = inspect(host)
    if args.json:
        print(json.dumps({"target": host.target, "ready": ready, "findings": [finding.record() for finding in findings],
                          "steps": [step.record() for step in plan]}, indent=2))
    else:
        print(report(host, findings, plan, ready))
    if not ready:
        return 1
    return run(host, plan, args.all) if args.run else 0


if __name__ == "__main__":
    sys.exit(main())
