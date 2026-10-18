"""Host coverage for the source snapshot used by speech build publication."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("runtime_sources", Path(__file__).with_name("runtime-sources.py"))
sources = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sources)

class RuntimeSourceTest(unittest.TestCase):
    def test_revision_recipe_and_adapter_changes_invalidate_actual_build_snapshot(self):
        original = sources.REPO
        try:
            with tempfile.TemporaryDirectory() as d:
                root = Path(d)
                sources.REPO = root
                names = ["common-jni/src/main/cpp/speech/backends/transcribepsy_backend.cpp",
                         "common-jni/src/main/cpp/speech/backend_versions.h",
                         "scripts/speech/build-runtimes.sh", "scripts/speech/transcribepsy.cmake",
                         "scripts/speech/runtime-sources.py"]
                for name in names:
                    p = root / name
                    p.parent.mkdir(parents=True, exist_ok=True)
                    p.write_text("version 1")
                before = sources.snapshot()
                self.assertEqual(set(before), set(names))
                self.assertEqual(before, sources.snapshot())
                for name in names:
                    p = root / name
                    p.write_text("version 2")
                    after = sources.snapshot()
                    self.assertNotEqual(before[name], after[name])
                    self.assertTrue(all(before[n] == after[n] for n in names if n != name))
                    p.write_text("version 1")
                generated = root / "build/model.gguf"
                generated.parent.mkdir()
                generated.write_bytes(b"weights must not enter source records")
                self.assertEqual(before, sources.snapshot())
        finally:
            sources.REPO = original

if __name__ == "__main__":
    unittest.main()
