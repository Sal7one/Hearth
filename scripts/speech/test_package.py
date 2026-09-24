import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("packager", Path(__file__).with_name("make-package.py"))
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)

class PackageTest(unittest.TestCase):
    def test_known_digest_and_manifest_exclusion(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "model.gguf").write_bytes(b"GGUF-fixture")
            (root / "hearth-speech.json").write_text("old manifest")
            result = packager.create_manifest(root, "nemotron-3.5-asr-0.6b", {"model": "model.gguf"})
            self.assertEqual(len(result["files"]), 1)
            self.assertEqual(result["files"][0]["bytes"], 12)
            self.assertEqual(len(result["files"][0]["sha256"]), 64)
    def test_missing_tokenizer_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "graph.onnx").write_bytes(b"fixture")
            with self.assertRaisesRegex(ValueError, "Missing assets"):
                packager.create_manifest(root, "qwen3-asr-0.6b", {"frontend": "graph.onnx", "encoder": "graph.onnx", "decoder": "graph.onnx", "tokenizer": "tokenizer"})
    def test_escape_and_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "model.gguf").write_bytes(b"GGUF-fixture")
            with self.assertRaisesRegex(ValueError, "Invalid role"):
                packager.create_manifest(root, "nemotron-3.5-asr-0.6b", {"model": "../model.gguf"})
            (root / "alias").symlink_to(root / "model.gguf")
            with self.assertRaisesRegex(ValueError, "Symbolic"):
                packager.create_manifest(root, "nemotron-3.5-asr-0.6b", {"model": "model.gguf"})
    def test_moonshine_requires_encoder_decoder_and_tokens(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for name in ("tokens.txt", "encoder.ort", "decoder.ort"):
                (root / name).write_bytes(b"fixture")
            roles = {"model": "tokens.txt", "encoder": "encoder.ort", "decoder": "decoder.ort"}
            result = packager.create_manifest(root, "moonshine-tiny-en-v2", roles)
            self.assertEqual(len(result["files"]), 3)
            (root / "decoder.ort").unlink()
            with self.assertRaisesRegex(ValueError, "Missing assets"):
                packager.create_manifest(root, "moonshine-tiny-en-v2", roles)
    def test_omnilingual_requires_model_and_tokens(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "model.int8.onnx").write_bytes(b"onnx-fixture")
            (root / "tokens.txt").write_bytes(b"a 1\n")
            roles = {"model": "model.int8.onnx", "tokenizer": "tokens.txt"}
            result = packager.create_manifest(root, "omnilingual-ctc-300m-v2-int8", roles)
            self.assertEqual({entry["path"] for entry in result["files"]}, set(roles.values()))
            (root / "tokens.txt").unlink()
            with self.assertRaisesRegex(ValueError, "Missing assets"):
                packager.create_manifest(root, "omnilingual-ctc-300m-v2-int8", roles)
    def test_unknown_model_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(ValueError, "Unsupported"):
                packager.create_manifest(Path(d), "whisper", {"model": "model.gguf"})

if __name__ == "__main__": unittest.main()
