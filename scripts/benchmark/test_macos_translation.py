import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("host_benchmark", Path(__file__).with_name("run_macos_translation.py"))
host = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(host)


class HostTranslationBenchmarkTests(unittest.TestCase):
    def test_model_families_use_the_same_translation_prompts_as_android(self):
        self.assertEqual(
            host.make_prompt("Привет", "hy-mt2", "ru", "ar"),
            "Translate the following text into Arabic. Note that you should only output the translated result without any additional explanation:\n\nПривет",
        )
        self.assertEqual(
            host.make_prompt("你好", "hy-mt1.5", "zh", "en"),
            "将以下文本翻译为English，注意只需要输出翻译后的结果，不要额外解释：\n\n你好",
        )
        self.assertIn("Russian (ru) to Arabic (ar)", host.make_prompt("Hi", "translategemma", "ru", "ar"))

    def test_error_rates_and_sentence_chrf(self):
        self.assertEqual(0.0, host.error_rate("Hello, world!", "hello world"))
        self.assertAlmostEqual(1 / 3, host.error_rate("we saw cat", "we saw a cat"))
        self.assertIsNone(host.error_rate("", "unreferenced"))
        self.assertAlmostEqual(100.0, host.chrf("你好世界", "你好世界"))
        self.assertGreater(host.chrf("The train leaves soon.", "The train departs soon."), 0)


if __name__ == "__main__":
    unittest.main()
