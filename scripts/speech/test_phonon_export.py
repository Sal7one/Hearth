"""Host exporter source/profile and verified extraction regression tests; never loads model weights."""
import hashlib
import importlib.util
import io
import tarfile
import tempfile
import unittest
from pathlib import Path

import zstandard

spec = importlib.util.spec_from_file_location("phonon_export", Path(__file__).with_name("phonon-2-export.py"))
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class PhononExportTest(unittest.TestCase):
    def test_original_default_and_multilingual_profile_keep_distinct_pins(self):
        self.assertIs(exporter.PINNED, exporter.PROFILES["phonon-2"])
        self.assertEqual(exporter.PINNED["languages"], ["en"])
        new = exporter.PROFILES["phonon-2.5"]
        self.assertEqual(len(new["languages"]), 25)
        self.assertIn("ru", new["languages"])
        self.assertNotIn("ar", new["languages"])
        self.assertNotEqual(new["container_sha256"], exporter.PINNED["container_sha256"])
        for pins in exporter.PROFILES.values():
            self.assertIn("/resolve/" + pins["revision"] + "/", pins["archive_url"])
            self.assertEqual(len(pins["revision"]), 40)
            self.assertNotIn("/main/", pins["archive_url"])

    def archive(self, root, member_name="model.fermion", symlink=False):
        data = b"verified-model-container-fixture"
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w") as tar:
            info = tarfile.TarInfo(member_name)
            if symlink:
                info.type = tarfile.SYMTYPE
                info.linkname = "elsewhere"
                tar.addfile(info)
            else:
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        raw = zstandard.ZstdCompressor().compress(buffer.getvalue())
        archive = root / "source.tar.zst"
        archive.write_bytes(raw)
        pins = dict(exporter.PHONON_25, archive_bytes=len(raw), archive_sha256=hashlib.sha256(raw).hexdigest(),
                    container_bytes=len(data), container_sha256=hashlib.sha256(data).hexdigest())
        return archive, pins, data

    def test_verified_nested_container_is_published_then_reused_without_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, pins, data = self.archive(root, exporter.PHONON_25["unpack_dir"] + "/model.fermion")
            target = exporter.obtain_container(archive, root, pins)
            self.assertEqual(target.read_bytes(), data)
            archive.unlink()
            self.assertEqual(exporter.obtain_container(archive, root, pins), target)
            self.assertFalse(target.with_name(target.name + ".partial").exists())

    def test_wrong_pinned_archive_cannot_replace_existing_container(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, pins, _ = self.archive(root)
            target = root / "model.fermion"
            target.write_bytes(b"previous unmatching input")
            archive.write_bytes(archive.read_bytes() + b"changed")
            with self.assertRaisesRegex(RuntimeError, "archive hash/size mismatch"):
                exporter.obtain_container(archive, root, pins)
            self.assertEqual(target.read_bytes(), b"previous unmatching input")

    def test_symlink_and_unexpected_member_cannot_publish_a_container(self):
        for member, symlink in [("model.fermion", True), ("../model.fermion", False)]:
            with self.subTest(member=member), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive, pins, _ = self.archive(root, member, symlink)
                with self.assertRaises(RuntimeError):
                    exporter.obtain_container(archive, root, pins)
                self.assertFalse((root / "model.fermion").exists())
                self.assertFalse((root / "model.fermion.partial").exists())

    def test_cached_download_is_hash_checked_before_reuse(self):
        with tempfile.TemporaryDirectory() as directory:
            cached = Path(directory) / "config.json"
            cached.write_bytes(b"valid")
            digest = hashlib.sha256(b"valid").hexdigest()
            self.assertEqual(exporter.verified_download("https://unreachable.invalid/model", cached, 5, digest), cached)
            cached.write_bytes(b"wrong")
            with self.assertRaisesRegex(RuntimeError, "pinned file hash/size mismatch"):
                exporter.verified_download("https://unreachable.invalid/model", cached, 5, digest)


if __name__ == "__main__":
    unittest.main()
