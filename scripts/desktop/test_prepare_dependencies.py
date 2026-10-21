import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

spec=importlib.util.spec_from_file_location('desktop_dependencies',Path(__file__).with_name('prepare-dependencies.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class RuntimeArchiveTests(unittest.TestCase):
    def test_zip_extracts_ordinary_files_and_rejects_traversal(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);archive=root/'runtime.zip'
            with zipfile.ZipFile(archive,'w') as package: package.writestr('runtime/lib/engine.dll',b'library')
            module.extract(archive,root/'valid')
            self.assertEqual((root/'valid/runtime/lib/engine.dll').read_bytes(),b'library')
            with zipfile.ZipFile(archive,'w') as package: package.writestr('../escape.dll',b'bad')
            with self.assertRaisesRegex(ValueError,'Unsafe'): module.extract(archive,root/'invalid')
            self.assertFalse((root/'escape.dll').exists())

    def test_tar_rejects_special_entries_and_skips_generic_symlink(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);archive=root/'runtime.tgz'
            with tarfile.open(archive,'w:gz') as package:
                info=tarfile.TarInfo('runtime/lib/actual.dylib');info.size=3;package.addfile(info,io.BytesIO(b'abc'))
                link=tarfile.TarInfo('runtime/lib/generic.dylib');link.type=tarfile.SYMTYPE;link.linkname='actual.dylib';package.addfile(link)
            module.extract(archive,root/'valid')
            self.assertEqual((root/'valid/runtime/lib/actual.dylib').read_bytes(),b'abc')
            self.assertFalse((root/'valid/runtime/lib/generic.dylib').exists())
            with tarfile.open(archive,'w:gz') as package:
                item=tarfile.TarInfo('runtime/device');item.type=tarfile.CHRTYPE;package.addfile(item)
            with self.assertRaisesRegex(ValueError,'Unsupported'): module.extract(archive,root/'invalid')

    def test_runtime_archives_have_exact_non_model_pins(self):
        self.assertEqual(set(module.ASSETS),{'macos-arm64','macos-x64','windows-x64'})
        for name,digest in module.ASSETS.values():
            self.assertIn('1.20.1',name);self.assertRegex(digest,r'^[a-f0-9]{64}$')

    def test_sherpa_pin_matches_the_android_backend_revision(self):
        versions=(module.ROOT/'common-jni/src/main/cpp/speech/backend_versions.h').read_text(encoding='utf-8')
        self.assertIn(f'HEARTH_QWEN_REVISION "{module.SHERPA_PIN}"',versions)


if __name__=='__main__': unittest.main()
