import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('build_native', Path(__file__).with_name('build-native.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NativeBundleMetadataTests(unittest.TestCase):
    def test_qwen_auto_language_is_retained_before_scaffold_is_removed(self):
        anchor = '        cleaned_ids.assign(std::next(asr_text_it), generated_ids.end());'
        prepared = module.prepare_sherpa_qwen_language(anchor)
        self.assertLess(prepared.index('result.lang = prefix_text.substr'), prepared.index('cleaned_ids.assign'))
        self.assertIn('if (prefix_text.size() > 19)', prepared)
        self.assertNotIn('result.lang', anchor)
        with self.assertRaisesRegex(RuntimeError, 'language scaffold changed'):
            module.prepare_sherpa_qwen_language('changed upstream')

    def test_incremental_cpu_stage_does_not_relabel_metal_binary(self):
        with tempfile.TemporaryDirectory() as temp:
            bundle = Path(temp)
            for name in ('libhearth_desktop_whisper.dylib', 'libtransiber_translation.dylib'):
                (bundle / name).write_bytes(name.encode())
            with mock.patch.object(module, 'probe_compiled_backends', side_effect=[['cpu', 'metal'], ['cpu']]):
                first = module.library_backend_metadata(bundle, {}, ['whisper'], 'abc')
            previous = {'libraries': first['libraries']}
            with mock.patch.object(module, 'probe_compiled_backends', side_effect=[['cpu', 'metal'], ['cpu']]):
                second = module.library_backend_metadata(bundle, previous, ['supertonic'], 'new')
            self.assertEqual(second['backends'], ['cpu', 'metal'])
            self.assertTrue(second['compiledMetalSupport'])
            self.assertEqual(second['libraries']['libhearth_desktop_whisper.dylib']['sourceSetSha256'], 'abc')
            self.assertNotIn('sourceSetSha256', second['libraries']['libtransiber_translation.dylib'])

    def test_changed_or_old_unprobeable_binary_cannot_inherit_gpu_claim(self):
        with tempfile.TemporaryDirectory() as temp:
            bundle = Path(temp)
            name = 'libhearth_desktop_whisper.dylib'
            (bundle / name).write_bytes(b'changed')
            previous = {'compiledMetalSupport': True, 'libraries': {name: {
                'sha256': 'old', 'compiledBackends': ['cpu', 'metal'], 'sourceSetSha256': 'old-source'}}}
            with mock.patch.object(module, 'probe_compiled_backends', return_value=None):
                result = module.library_backend_metadata(bundle, previous, [], 'current')
            self.assertFalse(result['compiledMetalSupport'])
            self.assertEqual(result['backends'], ['cpu'])
            self.assertEqual(result['libraries'][name]['backendProbe'], 'unavailable')
            self.assertNotIn('sourceSetSha256', result['libraries'][name])

    def test_new_gpu_build_requires_native_probe(self):
        with tempfile.TemporaryDirectory() as temp:
            bundle = Path(temp)
            (bundle / 'transiber_translation.dll').write_bytes(b'fixture')
            with mock.patch.object(module, 'probe_compiled_backends', return_value=None):
                with self.assertRaisesRegex(RuntimeError, 'compiled-backend'):
                    module.library_backend_metadata(bundle, {}, ['translation'], 'current')

    def test_cpu_and_cuda_metadata_come_from_binary(self):
        with tempfile.TemporaryDirectory() as temp:
            bundle = Path(temp)
            (bundle / 'transiber_translation.dll').write_bytes(b'fixture')
            with mock.patch.object(module, 'probe_compiled_backends', return_value=['cpu', 'cuda']):
                result = module.library_backend_metadata(bundle, {}, ['translation'], 'current')
            self.assertTrue(result['compiledCudaSupport'])
            self.assertFalse(result['compiledMetalSupport'])
            self.assertEqual(result['libraries']['transiber_translation.dll']['compiledBackends'], ['cpu', 'cuda'])

    def test_translation_preparation_keeps_android_source_untouched_and_binds_device(self):
        header = (module.ROOT / 'common-jni/src/main/cpp/translation/text_model.h').read_text()
        prepared = module.prepare_translation_header(header)
        self.assertIn('mp.devices = devices;', prepared)
        self.assertIn('mp.split_mode = LLAMA_SPLIT_MODE_NONE;', prepared)
        self.assertNotIn('mp.devices = devices;', header)
        jni = module.prepare_translation((module.ROOT / 'common-jni/src/main/cpp/translation/translation_jni.cpp').read_text())
        self.assertIn('createWithBackend', jni)
        self.assertIn('return 5;', jni)
        self.assertIn('hearth_desktop_compiled_backends', jni)
        self.assertIn('private DesktopGpuPlacement, public transiber::TextModel', jni)


if __name__ == '__main__': unittest.main()
