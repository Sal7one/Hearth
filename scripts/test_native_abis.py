import struct
import unittest
from native_abis import ABIS, ELF_IDENTITY, source_set_digest, verify_elf

class NativeAbiTest(unittest.TestCase):
    def test_source_snapshot_ignores_order_but_not_changed_sources(self):
        original = {"adapter.cpp": "a" * 64, "recipe.sh": "b" * 64}
        self.assertEqual(source_set_digest(original), source_set_digest(dict(reversed(list(original.items())))))
        self.assertNotEqual(source_set_digest(original), source_set_digest({**original, "recipe.sh": "c" * 64}))
        self.assertNotEqual(source_set_digest(original), source_set_digest({"other.cpp": "a" * 64, "recipe.sh": "b" * 64}))
    def header(self, abi):
        width, machine = ELF_IDENTITY[abi]
        data = bytearray(64)
        data[:4] = b"\x7fELF"
        data[4:7] = bytes((width, 1, 1))
        struct.pack_into("<HH", data, 16, 3, machine)
        return data
    def test_all_four_accept_only_their_own_shared_machine_and_width(self):
        for expected in ABIS:
            verify_elf(expected, self.header(expected))
            for actual in ABIS:
                if actual != expected:
                    with self.assertRaises(ValueError): verify_elf(expected, self.header(actual))
    def test_corruption_and_executables_are_rejected(self):
        for abi in ABIS:
            for position, value in ((0, 0), (4, 0), (5, 2), (16, 2), (18, 0)):
                data = self.header(abi); data[position] = value
                with self.assertRaises(ValueError): verify_elf(abi, data)
            with self.assertRaises(ValueError): verify_elf(abi, self.header(abi)[:30])

if __name__ == "__main__": unittest.main()
