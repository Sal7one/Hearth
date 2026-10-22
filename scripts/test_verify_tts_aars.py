import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("verify_tts_aars", Path(__file__).with_name("verify-tts-aars.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def archive(path, *, remove_library=None, extra_library=None, rules=True, missing_class=None, app_code=False):
    jar = io.BytesIO()
    with zipfile.ZipFile(jar, "w") as z:
        for name in module.SDK_CLASSES - {missing_class}:
            z.writestr(name, b"fixture")
        if app_code:
            z.writestr("com/sal7one/transiber/App.class", b"fixture")
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("classes.jar", jar.getvalue())
        for name in module.TTS_NATIVE - {remove_library}:
            z.writestr("jni/arm64-v8a/" + name, b"fixture")
        if extra_library:
            z.writestr(extra_library, b"fixture")
        if rules:
            lines = [f"-keep class {name} {{ *; }}" for name in module.JNI_CLASSES]
            for name, sample in module.CALLBACKS.items():
                lines += [f"-keep interface {name} {{ *; }}", f"-keepclassmembers class * implements {name} {{ boolean onPcm({sample}[], int); }}"]
            z.writestr("proguard.txt", "\n".join(lines))
        else:
            z.writestr("proguard.txt", "# The adapters used to own these rules")


class TtsAarDistributionTests(unittest.TestCase):
    def test_complete_single_tts_archive_retains_shrinking_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sdk.aar"
            archive(path)
            self.assertEqual(14, module.verify_archive("hearth-tts", path))

    def test_comment_only_rules_from_module_collapse_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sdk.aar"
            archive(path, rules=False)
            with self.assertRaisesRegex(ValueError, "consumer JNI keep rule"):
                module.verify_archive("hearth-tts", path)

    def test_missing_family_and_wrapper_only_unsupported_abi_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sdk.aar"
            archive(path, remove_library="libhearth_moss.so")
            with self.assertRaisesRegex(ValueError, "native inventory mismatch"):
                module.verify_archive("hearth-tts", path)
            archive(path, extra_library="jni/x86/libhearth_moss_jni.so")
            with self.assertRaisesRegex(ValueError, "native inventory mismatch"):
                module.verify_archive("hearth-tts", path)

    def test_missing_api_and_app_dependency_leaks_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sdk.aar"
            archive(path, missing_class="com/sal7one/hearth/tts/ming/MingSpeechEngine.class")
            with self.assertRaisesRegex(ValueError, "adapter/API classes missing"):
                module.verify_archive("hearth-tts", path)
            archive(path, app_code=True)
            with self.assertRaisesRegex(ValueError, "Hearth app code leaked"):
                module.verify_archive("hearth-tts", path)


if __name__ == "__main__":
    unittest.main()
