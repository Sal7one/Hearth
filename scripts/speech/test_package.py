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
    def test_non_english_moonshine_profiles_keep_same_verified_roles(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for name in ("tokens.txt", "encoder_model.ort", "decoder_model_merged.ort"):
                (root / name).write_bytes(b"fixture")
            roles = {"model": "tokens.txt", "encoder": "encoder_model.ort", "decoder": "decoder_model_merged.ort"}
            for stem in ("base-ar", "base-es", "tiny-ja", "tiny-ko", "base-ja", "base-uk", "base-vi", "base-zh"):
                profile = "moonshine-" + stem + "-v2"
                result = packager.create_manifest(root, profile, roles)
                self.assertEqual(result["profile"], profile)
                self.assertEqual({entry["path"] for entry in result["files"]}, set(roles.values()))
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
    def test_phonon_requires_tokens_joiner_encoder_and_decoder(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for name in ("tokens.txt", "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx"):
                (root / name).write_bytes(b"fixture")
            roles = {"model": "tokens.txt", "frontend": "joiner.int8.onnx", "encoder": "encoder.int8.onnx", "decoder": "decoder.int8.onnx"}
            result = packager.create_manifest(root, "phonon-2", roles)
            self.assertEqual({entry["path"] for entry in result["files"]}, set(roles.values()))
            (root / "encoder.int8.onnx").unlink()
            with self.assertRaisesRegex(ValueError, "Missing assets"):
                packager.create_manifest(root, "phonon-2", roles)
    def test_phonon25_keeps_distinct_profile_and_hashes_license_assets(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            roles = {"model": "tokens.txt", "frontend": "joiner.int8.onnx", "encoder": "encoder.int8.onnx", "decoder": "decoder.int8.onnx"}
            for name in [*roles.values(), "NOTICE", "LICENSE-WEIGHTS-CC-BY-4.0.txt"]:
                (root / name).write_bytes(b"fixture")
            result = packager.create_manifest(root, "phonon-2.5", roles)
            self.assertEqual(result["profile"], "phonon-2.5")
            self.assertEqual({entry["path"] for entry in result["files"]}, set(roles.values()) | {"NOTICE", "LICENSE-WEIGHTS-CC-BY-4.0.txt"})
            with self.assertRaisesRegex(ValueError, "Required roles"):
                packager.create_manifest(root, "phonon-2.5", {"model": "tokens.txt", "encoder": "encoder.int8.onnx", "decoder": "decoder.int8.onnx"})

    def test_transcribepsy_uses_one_gguf_role(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "model.gguf").write_bytes(b"GGUF-fixture")
            result = packager.create_manifest(root, "transcribepsy-250m-eu-sub-q8", {"model": "model.gguf"})
            self.assertEqual(result["profile"], "transcribepsy-250m-eu-sub-q8")
            self.assertEqual(result["roles"], {"model": "model.gguf"})
            with self.assertRaisesRegex(ValueError, "Required roles"):
                packager.create_manifest(root, "transcribepsy-250m-eu-sub-q8", {"encoder": "model.gguf"})
    def test_unknown_model_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(ValueError, "Unsupported"):
                packager.create_manifest(Path(d), "whisper", {"model": "model.gguf"})
    def test_audar_requires_decoder_and_encoder_while_owsm_is_one_model(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "model.gguf").write_bytes(b"GGUF-decoder")
            (root / "encoder.gguf").write_bytes(b"GGUF-encoder")
            result = packager.create_manifest(root, "audar-asr-v1-flash", {"model":"model.gguf", "encoder":"encoder.gguf"})
            self.assertEqual(set(result["roles"]), {"model","encoder"})
            with self.assertRaisesRegex(ValueError, "Required roles"):
                packager.create_manifest(root, "audar-asr-v1-flash", {"model":"model.gguf"})
            result = packager.create_manifest(root, "owsm-v4-base-102m", {"model":"model.gguf"})
            self.assertEqual(result["profile"], "owsm-v4-base-102m")

if __name__ == "__main__": unittest.main()
