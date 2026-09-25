"""Contract tests for hearth_ml — runnable on bare system python3.

No numpy / torch / onnx / onnxruntime required. Every fixture here is
hand-computed so the frozen normalization math cannot silently drift.
"""

from __future__ import annotations

import json
import math
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hearth_ml  # noqa: E402


def make_sample(wrist, middle_mcp, placements=None):
    """Build a flat 63-float sample.

    Unspecified landmarks default to the WRIST position (so they vanish
    after normalization); placements maps landmark index -> (x, y, z).
    """
    wrist = list(wrist)
    pts = []
    for _ in range(hearth_ml.NUM_LANDMARKS):
        pts.extend(wrist)
    pts[0:3] = wrist
    pts[9 * 3:9 * 3 + 3] = list(middle_mcp)
    for idx, xyz in (placements or {}).items():
        pts[idx * 3:idx * 3 + 3] = list(xyz)
    return pts


class NormalizeMathTests(unittest.TestCase):
    def test_wrist_becomes_origin(self):
        sample = make_sample(
            wrist=(1.0, 2.0, 3.0),
            middle_mcp=(4.0, 6.0, 3.0),          # offset (3, 4, 0) -> palm 5
            placements={1: (1.0, 7.0, 5.0), 20: (6.0, 2.0, 3.0)},
        )
        out = hearth_ml.normalize_landmarks(sample)
        self.assertEqual(len(out), hearth_ml.INPUT_DIM)
        # wrist maps to the origin exactly
        self.assertEqual(out[0:3], [0.0, 0.0, 0.0])
        # landmark 9 lands exactly 1 unit from the origin (palm scaling)
        self.assertAlmostEqual(math.sqrt(sum(v * v for v in out[27:30])),
                               1.0, places=12)

    def test_hand_computed_values(self):
        # wrist (1,2,3); lm9 (4,6,3): offset (3,4,0), palm = 5
        # lm1 (1,7,5): offset (0,5,2) -> (0, 1, 0.4)
        # lm20 (6,2,3): offset (5,0,0) -> (1, 0, 0)
        sample = make_sample(
            wrist=(1.0, 2.0, 3.0),
            middle_mcp=(4.0, 6.0, 3.0),
            placements={1: (1.0, 7.0, 5.0), 20: (6.0, 2.0, 3.0)},
        )
        out = hearth_ml.normalize_landmarks(sample)
        expected_lm1 = [0.0, 1.0, 0.4]
        expected_lm9 = [0.6, 0.8, 0.0]
        expected_lm20 = [1.0, 0.0, 0.0]
        for got, want in zip(out[3:6], expected_lm1):
            self.assertAlmostEqual(got, want, places=12)
        for got, want in zip(out[27:30], expected_lm9):
            self.assertAlmostEqual(got, want, places=12)
        for got, want in zip(out[60:63], expected_lm20):
            self.assertAlmostEqual(got, want, places=12)
        # every unspecified landmark was wrist-coincident -> still origin
        self.assertEqual(out[6:27], [0.0] * 21)

    def test_scale_invariance(self):
        """Scaling the raw hand by k must not change the output."""
        base = make_sample(wrist=(0.4, 0.8, 0.0),
                           middle_mcp=(0.4, 0.5, 0.1),
                           placements={4: (0.1, 0.6, -0.2),
                                       17: (0.7, 0.6, 0.05)})
        scaled = [v * 7.5 for v in base]
        out_a = hearth_ml.normalize_landmarks(base)
        out_b = hearth_ml.normalize_landmarks(scaled)
        for a, b in zip(out_a, out_b):
            self.assertAlmostEqual(a, b, places=12)

    def test_translation_invariance(self):
        base = make_sample(wrist=(0.5, 0.9, 0.0),
                           middle_mcp=(0.5, 0.6, 0.0),
                           placements={8: (0.55, 0.3, 0.02)})
        shifted = [v + 11.25 for v in base]
        out_a = hearth_ml.normalize_landmarks(base)
        out_b = hearth_ml.normalize_landmarks(shifted)
        for a, b in zip(out_a, out_b):
            self.assertAlmostEqual(a, b, places=12)

    def test_degenerate_palm_raises(self):
        # middle MCP coincides with the wrist -> palm 0
        sample = make_sample(wrist=(0.5, 0.5, 0.5),
                             middle_mcp=(0.5, 0.5, 0.5),
                             placements={1: (0.6, 0.5, 0.5)})
        with self.assertRaises(ValueError) as ctx:
            hearth_ml.normalize_landmarks(sample)
        self.assertIn("palm", str(ctx.exception))

    def test_tiny_palm_raises(self):
        sample = make_sample(wrist=(0.5, 0.5, 0.5),
                             middle_mcp=(0.5, 0.5, 0.5 + 1e-9))
        with self.assertRaises(ValueError):
            hearth_ml.normalize_landmarks(sample)

    def test_wrong_length_raises(self):
        with self.assertRaises(ValueError):
            hearth_ml.normalize_landmarks([0.0] * 62)
        with self.assertRaises(ValueError):
            hearth_ml.normalize_landmarks([0.0] * 64)

    def test_batch_form(self):
        a = make_sample(wrist=(1.0, 2.0, 3.0), middle_mcp=(4.0, 6.0, 3.0))
        b = make_sample(wrist=(0.2, 0.1, 0.0), middle_mcp=(0.2, 0.6, 0.0))
        single = hearth_ml.normalize_landmarks(a)
        batch = hearth_ml.normalize_landmarks([a, b])
        self.assertEqual(len(batch), 2)
        self.assertEqual(batch[0], single)
        self.assertEqual(batch[1], hearth_ml.normalize_landmarks(b))

    def test_point_list_form_matches_flat(self):
        flat = make_sample(wrist=(0.5, 0.8, 0.0),
                           middle_mcp=(0.5, 0.5, 0.2),
                           placements={3: (0.2, 0.7, 0.1)})
        nested = [flat[i * 3:(i + 1) * 3] for i in range(21)]
        out_flat = hearth_ml.normalize_landmarks(flat)
        out_nested = hearth_ml.normalize_landmarks(nested)
        self.assertEqual(out_nested, out_flat)

    def test_numpy_roundtrip(self):
        try:
            import numpy as np
        except ImportError:
            self.skipTest("numpy not installed")
        flat = make_sample(wrist=(0.5, 0.8, 0.0),
                           middle_mcp=(0.5, 0.5, 0.2))
        arr = np.asarray([flat, flat], dtype=np.float32)
        out = hearth_ml.normalize_landmarks(arr)
        want = hearth_ml.normalize_landmarks(flat)
        self.assertIsInstance(out, np.ndarray)
        self.assertEqual(out.shape, (2, hearth_ml.INPUT_DIM))
        self.assertEqual(out.dtype, np.float32)
        for row in out:
            for got, exp in zip(row.tolist(), want):
                self.assertAlmostEqual(got, exp, places=6)


class LabelConstantTests(unittest.TestCase):
    EXPECTED_ASL = ["A", "B", "C", "D", "E", "F", "G", "H", "I",
                    "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T",
                    "U", "V", "W", "X", "Y"]

    # byte-identical ArSL list (codepoints verified against the deployed
    # arsl_classifier_labels.json): plain Arabic letters only.
    EXPECTED_ARSL = [
        "\u0627", "\u0628", "\u062A", "\u062B", "\u062C", "\u062D", "\u062E",
        "\u062F", "\u0630", "\u0631", "\u0632", "\u0633", "\u0634", "\u0635",
        "\u0636", "\u0637", "\u0638", "\u0639", "\u063A", "\u0641", "\u0642",
        "\u0643", "\u0644", "\u0645", "\u0646", "\u0647", "\u0648", "\u064A",
    ]

    def test_static_asl_labels_exact(self):
        self.assertEqual(hearth_ml.STATIC_ASL_LABELS, self.EXPECTED_ASL)
        self.assertEqual(len(self.EXPECTED_ASL), 24)
        self.assertNotIn("J", hearth_ml.STATIC_ASL_LABELS)
        self.assertNotIn("Z", hearth_ml.STATIC_ASL_LABELS)
        self.assertEqual(len(set(hearth_ml.STATIC_ASL_LABELS)), 24)

    def test_canonical_arsl_labels_exact(self):
        self.assertEqual(hearth_ml.CANONICAL_ARSL_LABELS, self.EXPECTED_ARSL)
        self.assertEqual(len(self.EXPECTED_ARSL), 28)
        self.assertEqual(len(set(hearth_ml.CANONICAL_ARSL_LABELS)), 28)

    def test_arsl_spot_checks(self):
        labels = hearth_ml.CANONICAL_ARSL_LABELS
        self.assertEqual(labels[0], "ا")     # bare alef U+0627
        self.assertEqual(labels[1], "ب")
        # hamza/carried forms must NOT be present (byte-identity guard)
        self.assertNotIn("\u0623", labels)   # أ alef with hamza above
        self.assertNotIn("\u0625", labels)   # إ alef with hamza below
        self.assertNotIn("\u0621", labels)   # ء stand-alone hamza
        self.assertNotIn("\u0640", labels)   # tatweel
        self.assertNotIn("\u0649", labels)   # ى dotless ya

    def test_frozen_contract_constants(self):
        self.assertEqual(hearth_ml.INPUT_DIM, 63)
        self.assertEqual(hearth_ml.NUM_LANDMARKS, 21)
        self.assertEqual(hearth_ml.INPUT_NAME, "landmarks")
        self.assertEqual(hearth_ml.OUTPUT_NAME, "logits")
        self.assertEqual(hearth_ml.ONNX_OPSET, 13)
        self.assertEqual(hearth_ml.MAX_MODEL_BYTES, 2 * 1024 * 1024)
        self.assertEqual(hearth_ml.WRIST, 0)
        self.assertEqual(hearth_ml.MIDDLE_MCP, 9)


class LabelsJsonTests(unittest.TestCase):
    def test_roundtrip_utf8(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "labels.json")
            labels = hearth_ml.CANONICAL_ARSL_LABELS + ["A", "B"]
            hearth_ml.save_labels(path, labels)
            with open(path, "rb") as fh:
                raw = fh.read()
            # file must be real UTF-8 (ensure_ascii=False)
            self.assertIn(hearth_ml.CANONICAL_ARSL_LABELS[0].encode("utf-8"),
                          raw)
            self.assertEqual(hearth_ml.load_labels(path), labels)

    def test_save_rejects_bad_labels(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "labels.json")
            for bad in (["a", "a"], ["a", ""], ["a", 3], "abc", []):
                with self.assertRaises(ValueError):
                    hearth_ml.save_labels(path, bad)

    def test_load_rejects_invalid_utf8(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "labels.json")
            with open(path, "wb") as fh:
                fh.write(b'["\xff\xfe", "a"]')
            with self.assertRaises(ValueError) as ctx:
                hearth_ml.load_labels(path)
            self.assertIn("UTF-8", str(ctx.exception))

    def test_load_rejects_non_array_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "labels.json")
            with open(path, "wb") as fh:
                fh.write(b'{"0": "a"}')
            with self.assertRaises(ValueError) as ctx:
                hearth_ml.load_labels(path)
            self.assertIn("array", str(ctx.exception))

    def test_load_rejects_duplicates(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "labels.json")
            with open(path, "w", encoding="utf-8") as fh:
                json.dump(["a", "b", "a"], fh)
            with self.assertRaises(ValueError) as ctx:
                hearth_ml.load_labels(path)
            self.assertIn("duplicate", str(ctx.exception))

    def test_load_missing_file_raises(self):
        with self.assertRaises(OSError):
            hearth_ml.load_labels("/nonexistent/labels.json")


class MirrorTests(unittest.TestCase):
    def test_mirror_flat(self):
        sample = make_sample(wrist=(0.3, 0.9, 0.1),
                             middle_mcp=(0.3, 0.5, 0.1),
                             placements={4: (0.9, 0.7, -0.2)})
        out = hearth_ml.mirror_landmarks(sample)
        for i in range(21):
            self.assertAlmostEqual(out[i * 3], 1.0 - sample[i * 3], places=12)
            self.assertAlmostEqual(out[i * 3 + 1], sample[i * 3 + 1], places=12)
            self.assertAlmostEqual(out[i * 3 + 2], sample[i * 3 + 2], places=12)

    def test_mirror_is_involutive(self):
        sample = make_sample(wrist=(0.25, 0.75, 0.05),
                             middle_mcp=(0.3, 0.5, 0.1),
                             placements={8: (0.8, 0.4, 0.0)})
        twice = hearth_ml.mirror_landmarks(
            hearth_ml.mirror_landmarks(sample))
        for a, b in zip(twice, sample):
            self.assertAlmostEqual(a, b, places=12)

    def test_mirror_batch_form(self):
        a = make_sample(wrist=(0.3, 0.9, 0.1), middle_mcp=(0.3, 0.5, 0.1))
        b = make_sample(wrist=(0.6, 0.2, 0.0), middle_mcp=(0.7, 0.5, 0.0))
        out = hearth_ml.mirror_landmarks([a, b])
        self.assertEqual(out,
                         [hearth_ml.mirror_landmarks(a),
                          hearth_ml.mirror_landmarks(b)])

    def test_mirror_wrong_length_raises(self):
        with self.assertRaises(ValueError):
            hearth_ml.mirror_landmarks([0.5] * 10)


class CsvLoaderTests(unittest.TestCase):
    @staticmethod
    def data_row(label, value):
        return ",".join([f"{value:.4f}"] * hearth_ml.INPUT_DIM + [label])

    def test_csv_with_header(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "data.csv")
            header = ",".join(f"x{i}" for i in range(63)) + ",label"
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(header + "\n")
                fh.write(self.data_row("A", 0.1) + "\n")
                fh.write(self.data_row("B", 0.2) + "\n")
                fh.write(self.data_row("A", 0.3) + "\n")
            feats, labels, skipped = hearth_ml.load_landmarks_csv(path)
            self.assertEqual(len(feats), 3)
            self.assertEqual(labels, ["A", "B", "A"])
            self.assertEqual(skipped, 0)
            self.assertEqual(len(feats[0]), 63)
            self.assertTrue(all(abs(v - 0.3) < 1e-9 for v in feats[2]))

    def test_csv_without_header(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "data.csv")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(self.data_row("ا", 0.5) + "\n")
                fh.write(self.data_row("ب", 0.6) + "\n")
            feats, labels, skipped = hearth_ml.load_landmarks_csv(path)
            self.assertEqual(len(feats), 2)
            self.assertEqual(labels, ["ا", "ب"])
            self.assertEqual(skipped, 0)

    def test_csv_messy(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "messy.csv")
            content = "\n".join([
                ",".join(f"x{i}" for i in range(63)) + ",label",  # header
                "",                                              # blank
                self.data_row("  A  ", 0.1),                     # padded label
                self.data_row("B", 0.2) + ",extra",              # 65 columns
                ",".join(["nan!"] * 63) + ",C",                  # bad floats
                self.data_row("D", 0.4),                         # fine
                self.data_row("", 0.5),                          # empty label
                "",                                              # trailing blank
            ])
            # CRLF line endings everywhere
            with open(path, "w", encoding="utf-8", newline="") as fh:
                fh.write(content.replace("\n", "\r\n"))
            feats, labels, skipped = hearth_ml.load_landmarks_csv(path)
            self.assertEqual(labels, ["A", "D"])   # label stripped
            self.assertEqual(len(feats), 2)
            self.assertEqual(skipped, 3)
            self.assertTrue(all(abs(v - 0.4) < 1e-9 for v in feats[1]))

    def test_csv_numeric_labels_stay_strings(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "data.csv")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(self.data_row("0", 0.1) + "\n")
                fh.write(self.data_row("1", 0.2) + "\n")
            _feats, labels, _skipped = hearth_ml.load_landmarks_csv(path)
            self.assertEqual(labels, ["0", "1"])

    def test_csv_quoted_label(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "data.csv")
            row = ",".join(["0.5"] * 63) + ',"W"'
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(row + "\n")
            _feats, labels, skipped = hearth_ml.load_landmarks_csv(path)
            self.assertEqual(labels, ["W"])
            self.assertEqual(skipped, 0)

    def test_csv_missing_file_raises(self):
        with self.assertRaises(OSError):
            hearth_ml.load_landmarks_csv("/nonexistent/data.csv")


class SoftmaxTests(unittest.TestCase):
    def test_softmax_sums_to_one(self):
        out = hearth_ml.softmax([1.0, 2.0, 3.0])
        self.assertAlmostEqual(sum(out), 1.0, places=12)
        self.assertLess(out[0], out[1])
        self.assertLess(out[1], out[2])

    def test_softmax_numerically_stable(self):
        out = hearth_ml.softmax([1000.0, 1001.0])
        self.assertTrue(all(math.isfinite(v) for v in out))
        self.assertAlmostEqual(sum(out), 1.0, places=12)

    def test_softmax_uniform(self):
        out = hearth_ml.softmax([0.0] * 7)
        self.assertEqual(len(out), 7)
        for v in out:
            self.assertAlmostEqual(v, 1 / 7, places=12)


class TorchModelTests(unittest.TestCase):
    def test_reference_mlp_architecture(self):
        try:
            import torch
        except ImportError:
            self.skipTest("torch not installed")
        model = hearth_ml.build_reference_mlp(24)
        linear_dims = [m.in_features for m in model if
                       isinstance(m, torch.nn.Linear)]
        self.assertEqual(linear_dims, [63, 128, 64])
        self.assertEqual([m for m in model if isinstance(m, torch.nn.Linear)][-1]
                         .out_features, 24)
        self.assertEqual(sum(1 for m in model if isinstance(m, torch.nn.BatchNorm1d)), 2)
        with torch.no_grad():
            out = model(torch.randn(4, 63))
        self.assertEqual(out.shape, (4, 24))


if __name__ == "__main__":
    unittest.main()
