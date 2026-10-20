import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("stage_windows_crt", Path(__file__).with_name("stage-windows-crt.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def pe_fixture(import_name="vcruntime140.dll", machine=0x8664, delay=False):
    data = bytearray(2048)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 0x3c, 0x80)
    data[0x80:0x84] = b"PE\0\0"
    struct.pack_into("<HH", data, 0x84, machine, 1)
    struct.pack_into("<H", data, 0x94, 240)
    optional = 0x98
    struct.pack_into("<H", data, optional, 0x20b)
    struct.pack_into("<I", data, optional + 108, 16)
    struct.pack_into("<IIII", data, optional + 240 + 8, 1024, 0x1000, 1024, 0x200)
    if delay:
        struct.pack_into("<II", data, optional + 112 + 13 * 8, 0x1000, 64)
        struct.pack_into("<II", data, 0x200, 1, 0x1080)
    else:
        struct.pack_into("<II", data, optional + 112 + 8, 0x1000, 40)
        struct.pack_into("<I", data, 0x200 + 12, 0x1080)
    encoded = import_name.encode("ascii") + b"\0"
    data[0x280:0x280 + len(encoded)] = encoded
    return bytes(data)


class WindowsCrtStageTests(unittest.TestCase):
    def test_only_release_x64_visual_studio_redist_candidates_are_considered(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for version, arch, family in (("14.44.1", "x64", "Microsoft.VC143.CRT"),
                                          ("14.50.2", "x64", "Microsoft.VC145.CRT"),
                                          ("14.51.1", "x86", "Microsoft.VC145.CRT"),
                                          ("debug_nonredist", "x64", "Microsoft.VC143.CRT")):
                directory = root / "VC/Redist/MSVC" / version / arch / family
                directory.mkdir(parents=True)
                for name in module.DLLS:
                    (directory / name).write_bytes(b"fixture")
            candidates = module.redist_candidates(root)
            self.assertEqual([item.parents[1].name for item in candidates], ["14.50.2", "14.44.1"])
            (candidates[0] / module.DLLS[0]).unlink()
            self.assertEqual(len(module.redist_candidates(root)), 1)

    def test_normal_and_delay_imports_are_checked_and_x86_is_rejected(self):
        self.assertEqual(module.pe_imports(pe_fixture()), ["vcruntime140.dll"])
        self.assertEqual(module.pe_imports(pe_fixture("MSVCP140_1.dll", delay=True)), ["msvcp140_1.dll"])
        with self.assertRaisesRegex(ValueError, "x64"):
            module.pe_imports(pe_fixture(machine=0x14c))
        with self.assertRaises(ValueError):
            module.pe_imports(pe_fixture()[:600])

    def test_fifth_runtime_dependency_cannot_be_silently_omitted(self):
        module.check_import_closure([*module.DLLS, "kernel32.dll", "api-ms-win-crt-runtime-l1-1-0.dll"])
        with self.assertRaisesRegex(ValueError, "msvcp140_atomic_wait.dll"):
            module.check_import_closure(["msvcp140_atomic_wait.dll"])
        with self.assertRaisesRegex(ValueError, "unexpected.dll"):
            module.check_import_closure(["unexpected.dll"])


if __name__ == "__main__":
    unittest.main()
