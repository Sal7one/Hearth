import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import platform
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("hearth_fetch_cuda", Path(__file__).with_name("fetch-cuda-toolkit.py"))
toolkit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(toolkit)

# What each real archive carries that matters here: the tools the build needs, the licence,
# and the runtime DLLs that must never leave the archive.
CONTENTS = {
    "cuda_nvcc": {"bin/nvcc.exe": b"nvcc", "nvvm/bin/cicc.exe": b"cicc", "nvvm/bin/nvvm64_40_0.dll": b"compiler-internal", "include/crt/host_config.h": b"crt"},
    "cuda_cudart": {"include/cuda.h": b"cuda", "include/cuda_runtime.h": b"runtime", "lib/x64/cudart_static.lib": b"static",
                    "lib/x64/cuda.lib": b"driver", "bin/cudart64_12.dll": b"RUNTIME"},
    "cuda_cccl": {"include/thrust/version.h": b"thrust"},
    "libcublas": {"include/cublas_v2.h": b"cublas", "lib/x64/cublas.lib": b"import", "lib/x64/cublasLt.lib": b"unused",
                  "bin/cublas64_12.dll": b"RUNTIME", "bin/cublasLt64_12.dll": b"RUNTIME"},
}


def archive(component, files, licence=True):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as package:
        top = f"{component}-windows-x86_64-archive/"
        if licence:
            package.writestr(top + "LICENSE", f"NVIDIA CUDA licence for {component}")
        for name, content in files.items():
            package.writestr(name if name.startswith(("/", "..")) or ":" in name else top + name, content)
    return data.getvalue()


class FetchCudaToolkitTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.work = Path(temporary.name)
        self.root = self.work / "toolkit"
        self.downloads = self.work / "downloads"
        self.saved = (toolkit.COMPONENTS, toolkit.TREE_SHA256)
        self.addCleanup(lambda: (setattr(toolkit, "COMPONENTS", self.saved[0]), setattr(toolkit, "TREE_SHA256", self.saved[1])))
        toolkit.TREE_SHA256 = None
        self.requests = []
        self.serve({name: archive(name, files) for name, files in CONTENTS.items()})

    def serve(self, archives, pinned=None):
        """Pins `pinned` (default: what is served) and answers downloads with `archives`."""
        pinned = pinned or archives
        self.bodies = {}
        components = {}
        for name, rule in self.saved[0].items():
            location = f"{name}/{name}.zip"
            components[name] = {"version": "1.0", "archive": location, "size": len(pinned[name]),
                                "sha256": hashlib.sha256(pinned[name]).hexdigest(), "keep": rule["keep"]}
            self.bodies[toolkit.SOURCE + location] = archives[name]
        toolkit.COMPONENTS = components

    def open_url(self, url):
        self.requests.append(url)
        return contextlib.closing(io.BytesIO(self.bodies[url]))

    def fetch(self):
        return toolkit.fetch(self.root, self.downloads, self.open_url, report=lambda _: None)

    def files(self):
        return sorted(path.relative_to(self.root).as_posix() for path in self.root.rglob("*") if path.is_file())

    def test_only_build_tools_and_licences_are_assembled(self):
        digest = self.fetch()
        present = self.files()
        for name in toolkit.REQUIRED:
            self.assertIn(name, present)
        self.assertIn("nvvm/bin/nvvm64_40_0.dll", present, "The compiler keeps its own internals")
        self.assertEqual([], [name for name in present if name.startswith("bin/") and name.endswith(".dll")])
        self.assertNotIn("lib/x64/cublasLt.lib", present)
        self.assertEqual(["licenses/cuda_cccl-LICENSE.txt", "licenses/cuda_cudart-LICENSE.txt", "licenses/cuda_nvcc-LICENSE.txt",
                          "licenses/libcublas-LICENSE.txt"], [name for name in present if name.startswith("licenses/")])
        receipt = json.loads((self.root / toolkit.RECEIPT).read_text(encoding="utf-8"))
        self.assertEqual(digest, receipt["treeSha256"])
        self.assertFalse(receipt["runtimeLibrariesIncluded"])
        self.assertEqual(toolkit.VERSION, toolkit.verify(self.root))

    def test_a_download_that_differs_from_its_pin_is_refused_and_nothing_is_extracted(self):
        served = {name: archive(name, files) for name, files in CONTENTS.items()}
        tampered = dict(served, cuda_cudart=archive("cuda_cudart", dict(CONTENTS["cuda_cudart"], **{"include/cuda.h": b"evil"})))
        self.serve(tampered, pinned=served)
        with self.assertRaisesRegex(toolkit.ToolkitError, "pinned size and SHA-256"):
            self.fetch()
        self.assertFalse(self.root.exists())
        self.assertEqual([], list(self.downloads.glob("*.part")))
        self.assertFalse((self.downloads / "cuda_cudart.zip").exists())

    def test_a_download_larger_than_its_pin_stops_early(self):
        served = {name: archive(name, files) for name, files in CONTENTS.items()}
        self.serve(dict(served, cuda_nvcc=served["cuda_nvcc"] + b"x" * 4096), pinned=served)
        with self.assertRaisesRegex(toolkit.ToolkitError, "larger than its pinned"):
            self.fetch()

    def test_unsafe_archive_paths_refuse_the_whole_archive(self):
        for unsafe in ("../escape.h", "/absolute.h", "C:/drive.h"):
            with self.subTest(unsafe):
                self.serve(dict({name: archive(name, files) for name, files in CONTENTS.items()},
                                cuda_cccl=archive("cuda_cccl", {unsafe: b"x"})))
                with self.assertRaisesRegex(toolkit.ToolkitError, "unsafe archive path"):
                    self.fetch()
                self.assertFalse(self.root.exists())

    def test_an_archive_without_its_licence_is_not_used(self):
        self.serve(dict({name: archive(name, files) for name, files in CONTENTS.items()},
                        libcublas=archive("libcublas", CONTENTS["libcublas"], licence=False)))
        with self.assertRaisesRegex(toolkit.ToolkitError, "no licence text"):
            self.fetch()

    def test_a_changed_or_added_file_fails_verification(self):
        self.fetch()
        header = self.root / "include/cuda.h"
        original = header.read_bytes()
        header.write_bytes(b"changed")
        with self.assertRaisesRegex(toolkit.ToolkitError, "changed after they were verified"):
            toolkit.verify(self.root)
        header.write_bytes(original)
        self.assertEqual(toolkit.VERSION, toolkit.verify(self.root))
        (self.root / "bin/cublas64_12.dll").write_bytes(b"planted")
        with self.assertRaisesRegex(toolkit.ToolkitError, "changed after they were verified"):
            toolkit.verify(self.root)

    def test_tools_made_from_other_pins_are_not_accepted(self):
        self.fetch()
        toolkit.COMPONENTS = {name: dict(pin, sha256="0" * 64) if name == "cuda_nvcc" else pin for name, pin in toolkit.COMPONENTS.items()}
        with self.assertRaisesRegex(toolkit.ToolkitError, "different pins"):
            toolkit.verify(self.root)

    def test_the_pinned_tree_digest_is_enforced_when_assembling(self):
        toolkit.TREE_SHA256 = "f" * 64
        with self.assertRaisesRegex(toolkit.ToolkitError, "not the pinned"):
            self.fetch()
        self.assertFalse(self.root.exists())

    def test_verified_downloads_are_reused(self):
        self.fetch()
        first = len(self.requests)
        self.fetch()
        self.assertEqual(4, first)
        self.assertEqual(first, len(self.requests))

    def test_missing_tools_are_reported_not_assumed(self):
        with self.assertRaisesRegex(toolkit.ToolkitError, "not fetched"):
            toolkit.verify(self.root)

    @unittest.skipUnless(platform.system() == "Windows" and platform.machine().lower() in ("amd64", "x86_64"), "Windows x64 command line")
    def test_nothing_is_downloaded_without_accepting_the_licence(self):
        output = io.StringIO()
        with contextlib.redirect_stderr(output), self.assertRaises(SystemExit) as stopped:
            toolkit.main(["--root", str(self.root)])
        self.assertEqual(2, stopped.exception.code)
        self.assertIn("--accept-nvidia-eula", output.getvalue())
        self.assertEqual([], self.requests)
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, toolkit.main(["--check", "--root", str(self.root)]))

    def test_the_real_pins_name_no_runtime_library(self):
        for name, pin in self.saved[0].items():
            self.assertRegex(pin["sha256"], "^[0-9a-f]{64}$")
            self.assertTrue(pin["archive"].startswith(name + "/windows-x86_64/"))
            self.assertFalse(any(rule.endswith(".dll") or rule.startswith("bin/") and name != "cuda_nvcc" for rule in pin["keep"]))
        self.assertRegex(self.saved[1], "^[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main()
