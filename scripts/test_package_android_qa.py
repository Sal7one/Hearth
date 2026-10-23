"""Small-file staging coverage; never builds APKs, invokes signing tools or creates a release."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("hearth_qa_packager", Path(__file__).with_name("package-android-qa.py"))
packager = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(packager)


class IndividualQaAssetsTest(unittest.TestCase):
    def test_all_ten_apks_are_individual_assets_with_matching_receipts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            staging = root / "staging"
            staging.mkdir()
            for flavor in ("play", "foss"):
                for abi in packager.ABIS:
                    source = root / f"app/build/outputs/apk/{flavor}/qa/app-{flavor}-{abi}-qa.apk"
                    source.parent.mkdir(parents=True, exist_ok=True)
                    source.write_bytes(f"verified fixture {flavor} {abi}".encode())
            receipts = packager.stage_apks(root, staging, "0.30.0-rc.7")
            self.assertEqual(10, len(receipts))
            self.assertEqual(10, len(list(staging.iterdir())))
            self.assertFalse(list(staging.glob("*.zip")))
            for item in receipts:
                asset = staging / item["name"]
                self.assertTrue(asset.name.endswith(".apk"))
                self.assertEqual(asset.stat().st_size, item["bytes"])
                self.assertEqual(hashlib.sha256(asset.read_bytes()).hexdigest(), item["sha256"])
            self.assertEqual({(flavor, abi) for flavor in ("play", "foss") for abi in packager.ABIS},
                             {(item["flavor"], item["abi"]) for item in receipts})

    def test_individual_evidence_preserves_bytes_and_uses_safe_flat_validation_names(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "evidence"
            staging = root / "staging"
            evidence.mkdir(); staging.mkdir()
            (evidence / "qa-upgrade-identity.json").write_text('{"verified":true}\n')
            (evidence / "metal checks").mkdir()
            (evidence / "metal checks/result file.json").write_text('{"actualGpuInference":true}\n')
            receipts = packager.stage_evidence(evidence, staging)
            self.assertEqual(2, len(receipts))
            self.assertTrue((staging / "validation-qa-upgrade-identity.json").is_file())
            self.assertTrue((staging / "validation-metal-checks--result-file.json").is_file())
            for item in receipts:
                self.assertRegex(item["name"], r"^validation-[A-Za-z0-9._-]+$")
                self.assertEqual((evidence / item["evidencePath"]).read_bytes(), (staging / item["name"]).read_bytes())
                self.assertEqual(packager.digest(staging / item["name"]), item["sha256"])

    def test_evidence_archives_are_rejected_by_suffix_or_magic(self):
        fixtures = [("all-results.zip", b"plain text still forbidden"), ("all-results.tar.gz", b"source"),
                    ("hidden.log", b"PK\x03\x04payload"), ("hidden.json", b"\x1f\x8bpayload"),
                    ("hidden.txt", b"\0" * 257 + b"ustar" + b"\0" * 250)]
        for name, data in fixtures:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); evidence = root / "evidence"; staging = root / "staging"
                evidence.mkdir(); staging.mkdir(); (evidence / name).write_bytes(data)
                with self.assertRaisesRegex(SystemExit, "Evidence archives"):
                    packager.stage_evidence(evidence, staging)
                self.assertEqual([], list(staging.iterdir()))

    def test_symlinked_files_and_directories_cannot_leave_evidence_root(self):
        for directory in (False, True):
            with self.subTest(directory=directory), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); evidence = root / "evidence"; evidence.mkdir()
                outside = root / "outside"
                if directory: outside.mkdir()
                else: outside.write_text("do not publish")
                try: (evidence / "link").symlink_to(outside, target_is_directory=directory)
                except OSError as error: self.skipTest(f"This host cannot create the symlink fixture: {error}")
                with self.assertRaisesRegex(SystemExit, "ordinary files"):
                    packager.evidence_files(evidence)

    def test_flattening_collisions_are_rejected_instead_of_overwriting_receipts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); evidence = root / "evidence"; staging = root / "staging"
            evidence.mkdir(); staging.mkdir(); (evidence / "a").mkdir()
            (evidence / "a/b.log").write_text("first")
            (evidence / "a--b.log").write_text("second")
            with self.assertRaisesRegex(SystemExit, "collide"):
                packager.stage_evidence(evidence, staging)

    def test_evidence_byte_and_file_limits_remain_enforced(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / "a.log").write_text("12345")
            with patch.object(packager, "MAX_EVIDENCE_BYTES", 4):
                with self.assertRaisesRegex(SystemExit, "bound"): packager.evidence_files(root)
            with patch.object(packager, "MAX_EVIDENCE_FILES", 0):
                with self.assertRaisesRegex(SystemExit, "bound"): packager.evidence_files(root)

    def test_changed_copy_never_has_a_valid_checksum_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); source = root / "source.apk"; source.write_bytes(b"original")
            with self.assertRaisesRegex(SystemExit, "changed while being packaged"):
                packager.checked_copy(source, root / "published.apk", "0" * 64)


if __name__ == "__main__":
    unittest.main()
