import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_macos_whisper import load_samples


class MacWhisperInputTests(unittest.TestCase):
    def test_pinned_language_set_includes_speech_and_two_silence_checks(self):
        _, _, samples = load_samples("ru", 6)
        speech = [sample for sample in samples if not sample["silence"]]
        silence = [sample for sample in samples if sample["silence"]]
        self.assertEqual(6, len(speech))
        self.assertEqual([1000, 2500], [sample["audioMs"] for sample in silence])
        self.assertTrue(all(len(sample["pcm"]) == sample["audioMs"] * 32 for sample in samples))

    def test_missing_language_fixtures_are_rejected(self):
        with self.assertRaises(ValueError):
            load_samples("xx", 6)


if __name__ == "__main__":
    unittest.main()
