import copy
import importlib.util
import json
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("model_handoff", Path(__file__).with_name("validate-model-handoff.py"))
handoff = importlib.util.module_from_spec(spec)
spec.loader.exec_module(handoff)


class ModelHandoffTest(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((handoff.CARDS / "stt.json").read_text())

    def test_all_fifteen_cards_validate_and_render(self):
        inventories = {}
        for category in ("stt", "translation", "tts"):
            inventories[category] = handoff.validate_inventory(json.loads((handoff.CARDS / f"{category}.json").read_text()), category)
        text = handoff.render(inventories)
        self.assertEqual(text.count("\n### "), 15)
        self.assertIn("No S25 results", text)

    def test_rejects_mutable_source_or_mismatched_pin(self):
        self.data["models"][0]["components"][0]["url"] = self.data["models"][0]["components"][0]["url"].replace("/resolve/", "/resolve/main/", 1)
        with self.assertRaisesRegex(ValueError, "pin disagree"):
            handoff.validate_inventory(self.data, "stt")

    def test_rejects_missing_required_component_from_total(self):
        self.data["models"][1]["downloadBytes"] -= self.data["models"][1]["components"][1]["bytes"]
        with self.assertRaisesRegex(ValueError, "omits or double-counts"):
            handoff.validate_inventory(self.data, "stt")

    def test_rejects_unsafe_paths_and_placeholder_hashes(self):
        for field, value in (("filename", "../model.gguf"), ("sha256", "0" * 64), ("bytes", -1)):
            data = copy.deepcopy(self.data)
            data["models"][0]["components"][0][field] = value
            with self.assertRaises(ValueError):
                handoff.validate_inventory(data, "stt")

    def test_rejects_duplicate_components_and_unsupported_language_claim(self):
        self.data["models"][0]["components"].append(copy.deepcopy(self.data["models"][0]["components"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate component"):
            handoff.validate_inventory(self.data, "stt")
        self.data = json.loads((handoff.CARDS / "stt.json").read_text())
        self.data["models"][0]["languages"] = ["en"]
        with self.assertRaisesRegex(ValueError, "bilingual coverage"):
            handoff.validate_inventory(self.data, "stt")

    def test_handoff_is_not_an_installable_package(self):
        self.data["models"][0]["installEnabled"] = True
        with self.assertRaisesRegex(ValueError, "not production installation"):
            handoff.validate_inventory(self.data, "stt")


if __name__ == "__main__":
    unittest.main()
