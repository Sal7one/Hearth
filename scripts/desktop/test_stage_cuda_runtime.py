import importlib.util
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("stage_cuda_runtime", Path(__file__).with_name("stage-cuda-runtime.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def pe_fixture(imports=(), delayed=(), machine=0x8664):
    data = bytearray(8192)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 0x3c, 0x80)
    data[0x80:0x84] = b"PE\0\0"
    struct.pack_into("<HH", data, 0x84, machine, 1)
    struct.pack_into("<H", data, 0x94, 240)
    optional = 0x98
    struct.pack_into("<H", data, optional, 0x20b)
    struct.pack_into("<I", data, optional + 108, 16)
    struct.pack_into("<IIII", data, optional + 240 + 8, 7168, 0x1000, 7168, 0x400)
    name_offset = 0x1000
    for directory, names, table, stride in ((1, imports, 0x400, 20), (13, delayed, 0x800, 32)):
        if not names:
            continue
        struct.pack_into("<II", data, optional + 112 + directory * 8, table + 0xc00, (len(names) + 1) * stride)
        for index, name in enumerate(names):
            encoded = name.encode("ascii") + b"\0"
            data[name_offset:name_offset + len(encoded)] = encoded
            if directory == 1:
                struct.pack_into("<I", data, table + index * stride + 12, name_offset + 0xc00)
            else:
                struct.pack_into("<II", data, table + index * stride, 1, name_offset + 0xc00)
            name_offset += len(encoded)
    return bytes(data)


class CudaStageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.toolkit = self.root / "CUDA" / "v12.8"
        self.bundle = self.root / "native"
        self.bundle.mkdir()
        (self.toolkit / "bin").mkdir(parents=True)
        (self.toolkit / "include").mkdir()
        (self.toolkit / "version.json").write_text(json.dumps({"cuda": {"version": "12.8.1"}}))
        (self.toolkit / "bin" / "nvcc.exe").write_bytes(b"installed compiler fixture")
        (self.toolkit / "include" / "cuda.h").write_bytes(b"installed header fixture")
        (self.toolkit / "EULA.txt").write_text("NVIDIA CUDA toolkit license fixture\n")
        self.cublas = self.toolkit / "bin" / "cublas64_12.dll"
        self.lt = self.toolkit / "bin" / "cublasLt64_12.dll"
        self.cublas.write_bytes(pe_fixture(["vcruntime140.dll"]))
        self.lt.write_bytes(pe_fixture(["kernel32.dll", "api-ms-win-crt-runtime-l1-1-0.dll"]))
        for name in module.DEFAULT_ENTRIES:
            (self.bundle / name).write_bytes(pe_fixture(["cublas64_12.dll"], delayed=["nvcuda.dll"]))
        self.roots = mock.patch.object(module, "installed_roots", return_value=[self.toolkit.resolve()])
        self.roots.start()
        self.addCleanup(self.roots.stop)
        self.signed = mock.patch.object(module, "signatures", side_effect=lambda paths: [
            {"name": path.name.lower(), "publisher": "fixture only", "status": "Valid", "thumbprint": "fixture"} for path in paths])
        self.signed.start()
        self.addCleanup(self.signed.stop)

    def stage(self):
        return module.stage_files(self.bundle, self.toolkit)

    def test_actual_closure_includes_dynamic_cublas_lt_and_not_system_driver(self):
        receipt = self.stage()
        self.assertEqual(receipt["loadOrder"], ["cublaslt64_12.dll", "cublas64_12.dll"])
        self.assertEqual(receipt["entries"], list(module.DEFAULT_ENTRIES))
        self.assertTrue(receipt["requiresSystemDriver"])
        self.assertFalse((self.bundle / "nvcuda.dll").exists())
        self.assertEqual((self.bundle / module.LICENSE).read_bytes(), (self.toolkit / "EULA.txt").read_bytes())
        for name, digest in receipt["sha256"].items():
            self.assertEqual(module.sha256(self.bundle / name), digest)
        provenance = json.loads((self.bundle / module.PROVENANCE).read_text())
        self.assertEqual(provenance["sourceFiles"]["cublaslt64_12.dll"]["path"], "bin/cublasLt64_12.dll")
        self.assertIn("nvcuda.dll", provenance["imports"][module.DEFAULT_ENTRIES[0]])
        self.assertIn("cublaslt64_12.dll", provenance["imports"]["cublas64_12.dll"])
        self.assertFalse((self.bundle / "manifest.json").exists())

    def test_delay_loaded_cudart_is_staged_dependency_first(self):
        cudart = self.toolkit / "bin" / "cudart64_12.dll"
        cudart.write_bytes(pe_fixture(["kernel32.dll"]))
        self.cublas.write_bytes(pe_fixture(delayed=["cudart64_12.dll"]))
        receipt = self.stage()
        self.assertIn("cudart64_12.dll", receipt["loadOrder"])
        self.assertLess(receipt["loadOrder"].index("cudart64_12.dll"), receipt["loadOrder"].index("cublas64_12.dll"))

    def test_missing_dynamic_lt_and_unknown_imports_fail_before_publication(self):
        self.lt.unlink()
        with self.assertRaisesRegex(ValueError, "cublaslt64_12"):
            self.stage()
        self.assertFalse((self.bundle / "cublas64_12.dll").exists())
        self.lt.write_bytes(pe_fixture(delayed=["unexpected.dll"]))
        with self.assertRaisesRegex(ValueError, "unexpected.dll"):
            self.stage()
        self.assertFalse((self.bundle / module.PROVENANCE).exists())

    def test_cpu_entry_or_wrong_major_is_not_claimed_cuda(self):
        for name in module.DEFAULT_ENTRIES:
            (self.bundle / name).write_bytes(pe_fixture(["kernel32.dll"]))
        with self.assertRaisesRegex(ValueError, "CPU-only"):
            self.stage()
        (self.bundle / module.DEFAULT_ENTRIES[0]).write_bytes(pe_fixture(["cublas64_13.dll"]))
        with self.assertRaisesRegex(ValueError, "cublas64_13"):
            self.stage()

    def test_unregistered_directory_and_escaping_runtime_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "installer-registered"):
            module.stage_files(self.bundle, self.root)
        outside = self.root / "cublasLt64_12.dll"
        outside.write_bytes(self.lt.read_bytes())
        self.lt.unlink()
        try:
            self.lt.symlink_to(outside)
        except OSError:
            self.skipTest("Host does not permit symlink fixtures")
        with self.assertRaisesRegex(ValueError, "regular file"):
            self.stage()

    def test_license_and_signature_are_required_without_partial_output(self):
        (self.toolkit / "EULA.txt").unlink()
        with self.assertRaisesRegex(ValueError, "EULA.txt"):
            self.stage()
        (self.toolkit / "EULA.txt").write_text("NVIDIA CUDA license fixture")
        with mock.patch.object(module, "signatures", return_value=[]):
            with self.assertRaisesRegex(ValueError, "Authenticode"):
                self.stage()
        self.assertFalse((self.bundle / "cublas64_12.dll").exists())

    def test_changed_source_after_signature_check_is_not_published(self):
        def changed(paths):
            records = [{"name": path.name.lower(), "status": "Valid"} for path in paths]
            self.cublas.write_bytes(self.cublas.read_bytes() + b"changed")
            return records
        with mock.patch.object(module, "signatures", side_effect=changed):
            with self.assertRaisesRegex(ValueError, "source changed"):
                self.stage()
        self.assertFalse((self.bundle / "cublas64_12.dll").exists())
        self.assertFalse((self.bundle / module.LICENSE).exists())

    def test_existing_manifest_is_preserved_and_additions_are_hashed(self):
        manifest = {"schemaVersion": 1, "platform": "windows-x64", "sourceSetSha256": "original",
                    "files": {name: module.sha256(self.bundle / name) for name in module.DEFAULT_ENTRIES}}
        (self.bundle / "manifest.json").write_text(json.dumps(manifest))
        receipt = module.stage(self.bundle, self.toolkit)
        result = json.loads((self.bundle / "manifest.json").read_text())
        self.assertEqual(result["sourceSetSha256"], "original")
        self.assertEqual(result["cudaRuntime"], receipt)
        self.assertEqual(result["files"][module.LICENSE], module.sha256(self.bundle / module.LICENSE))

    def test_nvidia_signature_verifier_uses_utf8_stdin_and_host_own_modules(self):
        self.signed.stop()
        with mock.patch.dict(os.environ, {"PSModulePath": "wrong-host-modules"}), \
                mock.patch.object(module.shutil, "which", side_effect=lambda name: "pwsh.exe" if name == "pwsh.exe" else None), \
                mock.patch.object(module.subprocess, "check_output", return_value=b'[]') as process:
            module.signatures([self.cublas])
        args, options = process.call_args
        self.assertIn("NVIDIA Corporation", args[0][-1])
        self.assertIn("$signature.Status -ne 'Valid'", args[0][-1])
        self.assertNotIn("psmodulepath", {key.casefold() for key in options["env"]})
        self.assertEqual(json.loads(options["input"]), [str(self.cublas)])


if __name__ == "__main__":
    unittest.main()
