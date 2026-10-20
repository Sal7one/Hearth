"""Offline adapter fixtures use the exact pinned public header, not ignored research state."""
import hashlib
import json
from pathlib import Path
import unittest

REPO = Path(__file__).resolve().parents[2]
class ExpansionFixtureTest(unittest.TestCase):
    def test_public_header_fixture_pin_and_hashes(self):
        root = REPO / 'common-jni/src/main/cpp/speech/tests/fixtures/transcribe_cpp'
        record = json.loads((root / 'provenance.json').read_text())
        self.assertEqual(record['sourceRevision'], '1dc22facfc931e2f8adb43924b0e38b9c46f83c7')
        self.assertEqual(set(record['files']), {'LICENSE', 'include/transcribe.h', 'include/transcribe/voxtral_realtime.h'})
        for name, digest in record['files'].items():
            self.assertEqual(hashlib.sha256((root / name).read_bytes()).hexdigest(), digest, name)
        self.assertIn('TRANSCRIBE_EXT_KIND_VOXTRAL_REALTIME_STREAM', (root / 'include/transcribe/voxtral_realtime.h').read_text())

    def test_sources_snapshot_tracks_recipe_and_real_adapter_inputs(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('expansion_sources', Path(__file__).with_name('expansion-runtime-sources.py'))
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        snapshot = module.snapshot()
        for path in ('common-jni/src/main/cpp/speech/expansion/transcribe_cpp_backend.cpp',
                     'common-jni/src/main/cpp/speech/expansion/versions.h',
                     'common-jni/src/main/cpp/speech/backend_abi.h',
                     'common-jni/src/main/cpp/common/audio_gate.h',
                     'scripts/speech/build-expansion-runtimes.sh', 'scripts/speech/transcribe-cpp.cmake'):
            self.assertIn(path, snapshot)
        self.assertFalse(any('tests/fixtures/' in path for path in snapshot))

if __name__ == '__main__': unittest.main()
