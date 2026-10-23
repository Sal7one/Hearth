"""Host coverage for Git-free aria2 corresponding-source delivery; no compiler is launched."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).parent / "downloads/build-aria2-android.py"
spec = importlib.util.spec_from_file_location("hearth_aria2_builder", SCRIPT)
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


def archive(name, member="README", content=b"pinned fixture\n"):
    result = io.BytesIO()
    with tarfile.open(fileobj=result, mode="w") as tar:
        item = tarfile.TarInfo(name + "/" + member)
        item.size = len(content)
        tar.addfile(item, io.BytesIO(content))
    return result.getvalue()


class Aria2SourceBundleTest(unittest.TestCase):
    def test_received_archives_are_verified_and_build_sources_need_no_git(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data = archive("aria2")
            (root / "source-archives").mkdir()
            (root / "source-archives/aria2.tar").write_bytes(data)
            with patch.object(builder, "ROOT", root), patch.dict(builder.SOURCE_ARCHIVES, aria2=hashlib.sha256(data).hexdigest()), \
                 patch.object(builder.subprocess, "check_output", side_effect=AssertionError("Git must not be invoked")):
                source = builder.source("aria2")
                self.assertEqual(root / "build/aria2-offline-source/aria2", source)
                self.assertEqual(b"pinned fixture\n", (source / "README").read_bytes())
                destination = root / "build/work"
                builder.extract_source(source, destination)
                self.assertEqual(b"pinned fixture\n", (destination / "README").read_bytes())
                self.assertFalse((destination / "aria2").exists(), "The original source prefix must be stripped")

    def test_modified_archive_is_rejected_without_online_fallback(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "source-archives").mkdir()
            (root / "source-archives/aria2.tar").write_bytes(b"changed")
            with patch.object(builder, "ROOT", root), patch.object(builder.subprocess, "check_output", side_effect=AssertionError("No fallback fetch")):
                with self.assertRaisesRegex(RuntimeError, "SHA-256"):
                    builder.source("aria2")

    def test_archive_path_escape_stays_rejected(self):
        data = archive("aria2", "../outside")
        with tempfile.TemporaryDirectory() as temporary, patch.dict(builder.SOURCE_ARCHIVES, aria2=hashlib.sha256(data).hexdigest()):
            with self.assertRaises(tarfile.FilterError):
                builder.extract_archive("aria2", data, Path(temporary) / "source")
            self.assertFalse((Path(temporary) / "outside").exists())

    def test_delivery_contains_every_helper_and_repackages_identically(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            files = {
                "LICENSE": b"Apache fixture", "NOTICE": b"Hearth fixture", "scripts/native_abis.py": b"# host helper\n",
                "licenses/aria2/GPL-3.0.txt": b"GPL fixture", "licenses/aria2/README.md": b"Build instructions\n",
                "licenses/aria2/NDK-NOTICE.txt": b"NDK fixture", "licenses/aria2/NDK-TOOLCHAIN-NOTICE.txt": b"Toolchain fixture",
            }
            for name, content in files.items():
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
            data = {name: archive(name) for name in builder.SOURCES}
            (root / "source-archives").mkdir()
            for name, content in data.items():
                (root / "source-archives" / (name + ".tar")).write_bytes(content)
            hashes = {name: hashlib.sha256(content).hexdigest() for name, content in data.items()}
            with patch.object(builder, "ROOT", root), patch.dict(builder.SOURCE_ARCHIVES, hashes):
                sources = {name: builder.source(name) for name in builder.SOURCES}
                bundle, first, notices = builder.write_source_bundle(sources, root / "unused-ndk")
                _, second, _ = builder.write_source_bundle(sources, root / "unused-ndk")
                self.assertEqual(first, second)
                self.assertEqual(first, builder.file_sha256(bundle))
                self.assertEqual(2, len(notices))
                with tarfile.open(bundle) as tar:
                    names = set(tar.getnames())
                    for name in [*files, "scripts/downloads/build-aria2-android.py", "source-archives/aria2.tar", "source-archives/openssl.tar", "source-archives/manifest.json"]:
                        self.assertIn(name, names)
                manifest = json.loads((root / "build/aria2-source-manifest.json").read_text())
                self.assertEqual(hashes, manifest["sourceArchives"])
                self.assertEqual(first, manifest["correspondingSourceSha256"])


if __name__ == "__main__":
    unittest.main()
