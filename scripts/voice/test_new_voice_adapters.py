"""Upstream call contracts, without downloading weights or importing heavyweight engines."""
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import hearth_voice_server as server


class Array(list):
    def reshape(self, *shape): return self
    @property
    def size(self): return len(self)


NUMPY = SimpleNamespace(asarray=lambda data: Array(data),
    isfinite=lambda data: SimpleNamespace(all=lambda: True),
    concatenate=lambda chunks: Array(x for chunk in chunks for x in chunk))


class KittenContractTest(unittest.TestCase):
    def test_downloads_exact_revision_and_retains_aliases_and_speed_priors(self):
        downloads = []
        with tempfile.TemporaryDirectory() as temp:
            config = dict(type="ONNX2", voice_aliases={"Bella": "expr-voice-2-f", "Jasper": "expr-voice-2-m"},
                speed_priors={"expr-voice-2-f": .8})
            path = Path(temp) / "config.json"; path.write_text(json.dumps(config))
            def download(**args):
                downloads.append(args)
                return str(path) if args["filename"] == "config.json" else str(Path(temp) / args["filename"])
            calls = []
            def create(**args):
                calls.append(args)
                return SimpleNamespace(available_voices=["expr-voice-2-f", "expr-voice-2-m"])
            with patch.dict("sys.modules", {"huggingface_hub": SimpleNamespace(hf_hub_download=download),
                "kittentts.onnx_model": SimpleNamespace(KittenTTS_1_Onnx=create)}):
                backend = server.Kitten(SimpleNamespace())
            self.assertEqual(backend.languages, ["en"])
            self.assertEqual([x["filename"] for x in downloads], ["config.json", "kitten_tts_nano_v0_8.onnx", "voices.npz"])
            self.assertTrue(all(x["revision"] == server.KITTEN_REVISION and x["repo_id"] == server.KITTEN_REPO for x in downloads))
            self.assertEqual(calls[0]["voice_aliases"], config["voice_aliases"])
            self.assertEqual(calls[0]["speed_priors"], config["speed_priors"])
            self.assertIn("male", server.capabilities(backend)["voices"])

    def test_gender_alias_uses_actual_voice_and_rejects_unsupported_language(self):
        backend = server.Kitten.__new__(server.Kitten)
        backend.aliases = {"Bella": "expr-voice-2-f", "Jasper": "expr-voice-2-m"}
        calls = []
        backend.engine = SimpleNamespace(generate=lambda text, **args: calls.append((text, args)) or [0.1])
        with patch.object(server, "encode_wav", return_value=b"wav") as encode:
            self.assertEqual(backend.speech("Hello", "en", "male"), b"wav")
            encode.assert_called_once_with([0.1], 24000)
        self.assertEqual(calls, [("Hello", dict(voice="Jasper", speed=1.0))])
        with self.assertRaisesRegex(ValueError, "English only"): backend.speech("مرحبا", "ar", "default")
        with self.assertRaisesRegex(ValueError, "unavailable"): backend.speech("Hello", "en", "invented")
        self.assertEqual(len(calls), 1)


class KokoroContractTest(unittest.TestCase):
    def backend(self, outputs=None):
        backend = server.Kokoro.__new__(server.Kokoro)
        backend.styles = {"af_heart": object(), "am_michael": object(), "zf_xiaobei": object()}
        backend.calls = []
        def generate(text, **args):
            backend.calls.append((text, args))
            return outputs if outputs is not None else iter([("a", "a", [0.1, 0.2]), ("b", "b", [0.3])])
        backend.pipelines = {"en": generate, "zh": generate}
        return backend

    def test_shared_pipeline_preserves_all_chunks_and_uses_language_voice_tensor(self):
        backend = self.backend()
        with patch.dict("sys.modules", {"numpy": NUMPY}), patch.object(server, "encode_wav", return_value=b"wav") as encode:
            self.assertEqual(backend.speech("你好", "zh", "female"), b"wav")
            encode.assert_called_once_with([0.1, 0.2, 0.3], 24000)
        self.assertIs(backend.calls[0][1]["voice"], backend.styles["zf_xiaobei"])
        self.assertEqual(backend.calls[0][1]["speed"], 1.0)

    def test_cross_language_voice_and_unenabled_language_fail_before_generation(self):
        backend = self.backend()
        with patch.dict("sys.modules", {"numpy": NUMPY}):
            with self.assertRaisesRegex(ValueError, "no selected"): backend.speech("你好", "zh", "af_heart")
            with self.assertRaisesRegex(ValueError, "not enabled"): backend.speech("مرحبا", "ar", "default")
        self.assertEqual(backend.calls, [])

    def test_empty_generator_is_an_error_not_empty_success(self):
        backend = self.backend(iter([]))
        with patch.dict("sys.modules", {"numpy": NUMPY}):
            with self.assertRaisesRegex(ValueError, "no audio"): backend.speech("Hello", "en", "default")

    def test_language_inventory_has_no_arabic_and_preserves_regional_voices(self):
        self.assertNotIn("ar", server.KOKORO_LANGUAGES)
        self.assertEqual(server.KOKORO_LANGUAGES["en-GB"][0], "b")
        self.assertEqual(server.KOKORO_LANGUAGES["pt-BR"][0], "p")
        self.assertIsNone(server.KOKORO_LANGUAGES["fr"][2])


if __name__ == "__main__": unittest.main()
