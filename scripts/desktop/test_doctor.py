import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("hearth_doctor", Path(__file__).with_name("doctor.py"))
doctor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(doctor)


class FakeHost(doctor.Host):
    """A computer described by the test: which tools answer, what is installed, an empty build tree."""

    def __init__(self, root, system="Windows", machine="amd64", **overrides):
        super().__init__(root=root, environment={"PATH": ""}, system=system, machine=machine)
        self.tools = {
            ("git", "--version"): "git version 2.55.0.windows.3",
            ("cmake", "--version"): "cmake version 3.29.2\n\nCMake suite maintained by Kitware",
            ("cargo", "--version"): "cargo 1.100.0 (3acc79c0c 2026-11-05)",
            ("rustc", "-vV"): "rustc 1.100.0\nhost: " + ("x86_64-pc-windows-msvc" if system == "Windows" else "aarch64-apple-darwin"),
            ("rustup", "toolchain", "list"): "stable (default)\n1.99.0-x86_64-pc-windows-msvc",
            ("xcrun", "--find", "clang"): "/usr/bin/clang",
        }
        self.installed_jdks = {17: "/jdk17", 21: "/jdk21"}
        self.studio = ("Visual Studio Build Tools 2022", "C:/BuildTools")
        self.sdk = "10.0.26100.0"
        self.gpus = ["NVIDIA GeForce RTX 5070, 621.14, 12227 MiB"]
        self.toolkit = (None, None, "Install an official Windows x64 CUDA 12/13 toolkit before packaging CUDA")
        self.runtime_folders = []
        self.build_tools = (None, None, "The CUDA build tools are not fetched at build/desktop-dependencies/cuda-toolkit/12.9.1")
        self.interpreter = (3, 12, 4)
        vars(self).update(overrides)

    def python(self): return self.interpreter
    def cores(self): return 16
    def which(self, name): return name if any(key[0] == name for key in self.tools) else None
    def tool(self, name, *arguments): return self.tools.get((name, *arguments))
    def jdks(self): return self.installed_jdks
    def visual_studio(self): return self.studio
    def windows_sdk(self): return self.sdk
    def nvidia_gpus(self): return self.gpus
    def cuda_toolkit(self): return self.toolkit
    def cuda_build_tools(self): return self.build_tools
    def cuda_runtime_folders(self): return self.runtime_folders


class DoctorTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def states(self, findings):
        return {finding.name: finding.state for finding in findings}

    def test_versions_are_read_from_real_tool_output(self):
        self.assertEqual((2, 55, 0), doctor.version("git version 2.55.0.windows.3"))
        self.assertEqual((4, 3, 3), doctor.version("cmake version 4.3.3"))
        self.assertEqual((), doctor.version(None))
        self.assertEqual(21, doctor.java_major("21.0.12"))
        self.assertEqual(8, doctor.java_major("1.8.0_392"))
        self.assertIsNone(doctor.java_major("unknown"))

    def test_a_complete_windows_computer_is_ready_and_the_gpu_is_never_required(self):
        host = FakeHost(self.root)
        findings, plan, ready = doctor.inspect(host)
        self.assertTrue(ready)
        states = self.states(findings)
        self.assertEqual(doctor.OK, states["NVIDIA GPU and driver"])
        self.assertEqual(doctor.ABSENT, states["NVIDIA CUDA Toolkit"])
        self.assertEqual({doctor.OK}, {finding.state for finding in findings if finding.group == doctor.REQUIRED})
        self.assertEqual(["Fetch the pinned sources and the ONNX Runtime SDK", "Build the native engines", "Stage the Microsoft C++ runtime",
                          "Build the media helpers", "Package the app", "Check the packaged app"], [step.title for step in plan])
        self.assertFalse(any(step.done for step in plan))
        native = plan[1].command
        self.assertEqual("build/desktop-dependencies/onnxruntime-win-x64-1.20.1", native[native.index("--ort-root") + 1])
        self.assertEqual("build/desktop-dependencies/llama.cpp", native[native.index("--llama-root") + 1])

    def test_each_missing_requirement_is_named_with_its_fix(self):
        host = FakeHost(self.root, studio=None, sdk=None, installed_jdks={21: "/jdk21"}, interpreter=(3, 9, 1))
        del host.tools[("cargo", "--version")]
        host.tools[("cmake", "--version")] = "cmake version 3.16.3"
        host.tools[("rustup", "toolchain", "list")] = "stable (default)"
        findings, _, ready = doctor.inspect(host)
        self.assertFalse(ready)
        missing = {finding.name: finding.fix for finding in findings if finding.state == doctor.MISSING}
        self.assertEqual({"Python", "CMake", "JDK 17", "Visual Studio C++ tools", "Windows SDK", "Rust", "Rust 1.99.0 toolchain"}, set(missing))
        self.assertIn("Amazon.Corretto.17.JDK", missing["JDK 17"])
        self.assertIn("Rustlang.Rustup", missing["Rust"])
        self.assertIn("rustup toolchain install 1.99.0", missing["Rust 1.99.0 toolchain"])
        self.assertIn("3.22", missing["CMake"])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(1, doctor.main([], host))
        self.assertIn("MISSING  Rust", output.getvalue())

    def test_a_gnu_rust_on_windows_is_not_accepted(self):
        host = FakeHost(self.root)
        host.tools[("rustc", "-vV")] = "rustc 1.100.0\nhost: x86_64-pc-windows-gnu"
        rust = next(finding for finding in doctor.required(host) if finding.name == "Rust")
        self.assertEqual(doctor.MISSING, rust.state)
        self.assertIn("msvc", rust.fix)

    def test_cuda_is_reported_from_the_stager_and_stray_runtime_files_are_not_a_toolkit(self):
        host = FakeHost(self.root, runtime_folders=["C:/llama/cuda"])
        findings = doctor.gpu(host)
        toolkit = next(finding for finding in findings if finding.name == "NVIDIA CUDA Toolkit")
        self.assertEqual(doctor.ABSENT, toolkit.state)
        self.assertIn("Install an official", toolkit.detail)
        self.assertIn("Nvidia.CUDA", toolkit.fix)
        stray = next(finding for finding in findings if finding.name == "CUDA runtime copy")
        self.assertIn("C:/llama/cuda", stray.detail)
        self.assertIn("never bundles", stray.detail)
        tools = next(finding for finding in findings if finding.name == "CUDA build tools for Nemotron")
        self.assertEqual(doctor.ABSENT, tools.state)
        self.assertIn("fetch-cuda-toolkit.py --accept-nvidia-eula", tools.fix)
        self.assertIn("no administrator rights", tools.fix)
        fetched = FakeHost(self.root, build_tools=("12.9.1", "build/cuda", None))
        tools = next(finding for finding in doctor.gpu(fetched) if finding.name == "CUDA build tools for Nemotron")
        self.assertEqual(doctor.OK, tools.state)
        self.assertIn("12.9.1", tools.detail)
        installed = FakeHost(self.root, toolkit=("C:/CUDA/v12.8", "12.8.1", None))
        toolkit = next(finding for finding in doctor.gpu(installed) if finding.name == "NVIDIA CUDA Toolkit")
        self.assertEqual(doctor.OK, toolkit.state)
        self.assertIn("12.8.1", toolkit.detail)
        self.assertIn("--cuda", toolkit.detail)

    def test_macos_uses_its_own_tools_and_has_no_windows_steps(self):
        host = FakeHost(self.root, system="Darwin", machine="arm64")
        findings, plan, ready = doctor.inspect(host)
        self.assertTrue(ready)
        names = set(self.states(findings))
        self.assertIn("Xcode command line tools", names)
        self.assertIn("Apple Metal", names)
        self.assertFalse(names & {"Visual Studio C++ tools", "Windows SDK", "NVIDIA CUDA Toolkit"})
        self.assertEqual(["bash", "scripts/desktop/build-media.sh"], plan[2].command)
        self.assertNotIn("Stage the Microsoft C++ runtime", [step.title for step in plan])
        self.assertEqual("./gradlew", plan[3].command[0])
        self.assertIn("build/desktop-dependencies/onnxruntime-osx-arm64-1.20.1", plan[1].command)

    def test_an_unsupported_computer_is_refused_without_a_plan(self):
        findings, plan, ready = doctor.inspect(FakeHost(self.root, system="Linux", machine="x86_64"))
        self.assertFalse(ready)
        self.assertEqual([], plan)
        self.assertEqual(doctor.MISSING, self.states(findings)["Computer"])

    def build(self, *parts):
        dependencies = self.root / "build/desktop-dependencies"
        bundle = self.root / "desktop-runtime/native-libs/windows-x64"
        if "dependencies" in parts:
            for name in ("llama.cpp/.git", "sherpa-onnx/.git", "onnxruntime-win-x64-1.20.1"):
                (dependencies / name).mkdir(parents=True)
        if "native" in parts or "crt" in parts:
            bundle.mkdir(parents=True)
            manifest = {"platform": "windows-x64", **({"microsoftCrt": {}} if "crt" in parts else {})}
            (bundle / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        if "media" in parts:
            helpers = self.root / "desktop-media/native-libs/windows-x64"
            helpers.mkdir(parents=True)
            (helpers / "hearth-media-decode.exe").write_bytes(b"MZ")

    def test_steps_are_done_only_when_their_result_is_in_the_tree(self):
        self.build("dependencies", "native")
        done = {step.title: step.done for step in doctor.steps(FakeHost(self.root))}
        self.assertEqual({"Fetch the pinned sources and the ONNX Runtime SDK": True, "Build the native engines": True,
                          "Stage the Microsoft C++ runtime": False, "Build the media helpers": False, "Package the app": False,
                          "Check the packaged app": False}, done)

    def test_a_native_bundle_for_another_platform_does_not_count(self):
        bundle = self.root / "desktop-runtime/native-libs/windows-x64"
        bundle.mkdir(parents=True)
        (bundle / "manifest.json").write_text(json.dumps({"platform": "macos-arm64", "microsoftCrt": {}}), encoding="utf-8")
        done = {step.title: step.done for step in doctor.steps(FakeHost(self.root))}
        self.assertFalse(done["Build the native engines"])
        self.assertFalse(done["Stage the Microsoft C++ runtime"])

    def test_run_does_the_remaining_steps_in_order_and_stops_at_the_first_failure(self):
        self.build("dependencies", "native", "crt")
        host = FakeHost(self.root)
        executed = []

        def execute(command):
            executed.append(command)
            return 0

        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, doctor.run(host, doctor.steps(host), execute=execute))
        self.assertEqual(["scripts/desktop/build-media.ps1", ":desktopApp:createDistributable", "scripts/desktop/check-packaged.py"],
                         [command[-1] for command in executed])
        self.assertTrue(executed[1][0].endswith("gradlew.bat") and Path(executed[1][0]).parent == self.root)
        executed.clear()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(7, doctor.run(host, doctor.steps(host), execute=lambda command: executed.append(command) or 7))
        self.assertEqual(1, len(executed))
        self.assertIn("Build the media helpers failed with exit code 7", output.getvalue())
        executed.clear()
        with contextlib.redirect_stdout(io.StringIO()):
            doctor.run(host, doctor.steps(host), everything=True, execute=execute)
        self.assertEqual(6, len(executed))

    def test_the_json_report_carries_the_same_facts(self):
        host = FakeHost(self.root)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, doctor.main(["--json"], host))
        payload = json.loads(output.getvalue())
        self.assertEqual("windows-x64", payload["target"])
        self.assertTrue(payload["ready"])
        self.assertEqual(6, len(payload["steps"]))
        self.assertIn({"group": doctor.REQUIRED, "name": "JDK 17", "state": "ok", "detail": "/jdk17"}, payload["findings"])

    def test_nothing_is_run_when_a_requirement_is_missing(self):
        host = FakeHost(self.root, studio=None)
        ran = []
        original = doctor.run
        doctor.run = lambda *arguments, **options: ran.append(arguments) or 0
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, doctor.main(["--run"], host))
        finally:
            doctor.run = original
        self.assertEqual([], ran)


if __name__ == "__main__":
    unittest.main()
