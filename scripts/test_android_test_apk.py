import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import warnings
import zipfile

from native_abis import ABIS, ELF_IDENTITY

spec = importlib.util.spec_from_file_location("android_test_apk", Path(__file__).with_name("verify-android-test-apk.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class AndroidTestApkTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "self-test.apk"
        self.members = {}
        for abi in ABIS:
            width, machine = ELF_IDENTITY[abi]
            elf = bytearray(64)
            elf[:7] = b"\x7fELF" + bytes((width, 1, 1))
            struct.pack_into("<HH", elf, 16, 3, machine)
            for library in ("libcommon_jni.so", "libonnxruntime.so", "libc++_shared.so"):
                self.members[f"lib/{abi}/{library}"] = bytes(elf)

    def write(self):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for name, data in self.members.items():
                archive.writestr(name, data)

    def test_complete_self_instrumenting_apk_has_all_runtime_architectures(self):
        self.write()
        self.assertEqual(verifier.verify_test_apk(self.apk), ABIS)

    def test_missing_shared_runtime_fails_before_emulator_start(self):
        del self.members["lib/x86/libonnxruntime.so"]
        self.write()
        with self.assertRaisesRegex(ValueError, "missing lib/x86/libonnxruntime.so"):
            verifier.verify_test_apk(self.apk)

    def test_runtime_from_wrong_architecture_is_rejected(self):
        self.members["lib/x86/libonnxruntime.so"] = self.members["lib/arm64-v8a/libonnxruntime.so"]
        self.write()
        with self.assertRaisesRegex(ValueError, "incorrect ELF"):
            verifier.verify_test_apk(self.apk)

    def test_duplicate_runtime_member_is_rejected(self):
        self.write()
        with warnings.catch_warnings(), zipfile.ZipFile(self.apk, "a") as archive:
            warnings.simplefilter("ignore", UserWarning)
            name = "lib/x86/libonnxruntime.so"
            archive.writestr(name, self.members[name])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            verifier.verify_test_apk(self.apk)


if __name__ == "__main__":
    unittest.main()
