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

    def test_primary_non_hf_sources_require_exact_pins_and_no_credentials(self):
        tts=json.loads((handoff.CARDS / "tts.json").read_text())
        model=next(m for m in tts["models"] if m["id"]=="outetts-1.0-1b")
        for part in model["components"]:
            handoff.validate_component(part,"Oute")
        self.assertIn("/repo/files?Revision=",handoff.component_link(model["components"][0])[0])
        self.assertTrue(handoff.component_link(model["components"][1])[0].startswith("https://github.com/edwko/OuteTTS/blob/"))
        source=model["components"][0]
        for change in [source["url"].replace(source["revision"],"master"),source["url"]+"&token=secret",
                       source["url"]+"&Revision="+source["revision"],source["url"].replace("modelscope.cn","untrusted.example"),
                       source["url"].replace("FilePath=oute-tts-1b.bin","FilePath=../oute-tts-1b.bin")]:
            with self.assertRaises(ValueError):handoff.validate_component(dict(source,url=change),"Oute")
        reference=model["components"][1]
        with self.assertRaisesRegex(ValueError,"pin disagree"):
            handoff.validate_component(dict(reference,url=reference["url"].replace(reference["revision"],"main")),"Oute reference")

    def test_jisr_conversion_profiles_require_complete_root_files_and_matching_manifest(self):
        data=json.loads((handoff.CARDS / "translation.json").read_text())
        model=next(m for m in data["models"] if m["family"]=="jisr-mt")
        handoff.validate_jisr_profiles(model,"Jisr")
        cases=[]
        for edit in [lambda p:p.update(requiredBytes=p["requiredBytes"]-1),
                     lambda p:p["components"].append(copy.deepcopy(p["components"][0])),
                     lambda p:p["components"][0].update(filename="nested/LICENSE"),
                     lambda p:p["components"][0].update(sha256="0"*64),
                     lambda p:p.update(precision="float16"),lambda p:p.update(modelId="unknown-model"),
                     lambda p:p["importManifest"].update(modelId="other-model")]:
            changed=copy.deepcopy(model);edit(changed["conversionProfiles"][0]);cases.append(changed)
        for changed in cases:
            with self.assertRaises(ValueError):handoff.validate_jisr_profiles(changed,"Jisr")
        rendered=handoff.render({"translation":[model]})
        self.assertIn("Converted runtime packages",rendered)
        for profile in model["conversionProfiles"]:self.assertIn(str(format(profile["requiredBytes"],",")),rendered)

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
