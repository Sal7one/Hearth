"""Contract tests for verify_onnx — runnable on bare system python3.

The ONNX fixtures below are hand-serialized protobuf (a single
MatMul + Add graph: landmarks[?,63] @ W[63,N] + b -> logits), so the
verifier's structural logic is fully covered without the `onnx` (or
`torch`) package. Runtime-dependent paths (onnxruntime inference,
end-to-end softmax rejection) are exercised only when onnxruntime
happens to be installed; the raw-logit heuristic itself is a pure
function and tested directly.
"""

from __future__ import annotations

import os
import random
import struct
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hearth_ml  # noqa: E402
import verify_onnx  # noqa: E402

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "verify_onnx.py")
GOOD_LABELS = ["a", "b", "c"]
FLOAT32 = 1
DOUBLE = 11


# ── Minimal protobuf wire-format writer ──────────────────────────────────────
def _varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("varints here are non-negative")
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def _tag(field: int, wire: int) -> bytes:
    return _varint((field << 3) | wire)


def _vf(field: int, value: int) -> bytes:      # varint field
    return _tag(field, 0) + _varint(value)


def _fd(field: int, payload: bytes) -> bytes:  # length-delimited field
    return _tag(field, 2) + _varint(len(payload)) + payload


def _dimension(dim):
    """int -> dim_value, str -> dim_param, None -> unspecified."""
    if isinstance(dim, int):
        return _vf(1, dim)
    if isinstance(dim, str):
        return _fd(2, dim.encode("utf-8"))
    return b""


def _shape(dims) -> bytes:                     # TensorShapeProto
    return b"".join(_fd(1, _dimension(d)) for d in dims)


def _tensor_type(elem_type: int, dims) -> bytes:
    """TypeProto.Tensor: elem_type (1, varint) + shape (2, message)."""
    return _vf(1, elem_type) + _fd(2, _shape(dims))


def _value_info(name: str, elem_type: int, dims) -> bytes:
    """ValueInfoProto { name (1) } wrapping TypeProto { tensor_type (1) }."""
    type_proto = _fd(1, _tensor_type(elem_type, dims))
    return _fd(1, name.encode("utf-8")) + _fd(2, type_proto)


def _tensor(name: str, dims, floats, raw_data=None) -> bytes:
    body = _fd(8, name.encode("utf-8"))
    body += b"".join(_vf(1, d) for d in dims)   # dims (unpacked varints)
    body += _vf(2, FLOAT32)                     # data_type FLOAT
    body += _fd(9, raw_data if raw_data is not None else
                struct.pack(f"<{len(floats)}f", *floats))
    return body


def _node(op_type: str, inputs, outputs, name: str) -> bytes:
    body = b"".join(_fd(1, i.encode("utf-8")) for i in inputs)
    body += b"".join(_fd(2, o.encode("utf-8")) for o in outputs)
    body += _fd(3, name.encode("utf-8"))
    body += _fd(4, op_type.encode("utf-8"))
    return body


def build_model_bytes(num_classes: int = 3, *, opset: int = 13,
                      input_name: str = "landmarks",
                      output_name: str = "logits",
                      input_dims=("batch", 63),
                      output_dims=None,
                      input_elem: int = FLOAT32,
                      output_elem: int = FLOAT32,
                      extra_inputs=0,
                      softmax: bool = False,
                      pad_floats: int = 0,
                      include_opset: bool = True,
                      seed: int = 7) -> bytes:
    """Serialize a MatMul+Add graph honoring the requested deviations."""
    rng = random.Random(seed)
    weights = [rng.uniform(-1.0, 1.0)
               for _ in range(hearth_ml.INPUT_DIM * num_classes)]
    bias = [rng.uniform(-1.0, 1.0) for _ in range(num_classes)]
    if output_dims is None:
        output_dims = ("batch", num_classes)

    nodes = [_node("MatMul", [input_name, "W"], ["hidden"], "n_matmul"),
             _node("Add", ["hidden", "b"],
                   ["logits_pre" if softmax else output_name], "n_add")]
    if softmax:
        nodes.append(_node("Softmax", ["logits_pre"], [output_name],
                           "n_softmax"))

    graph = b"".join(_fd(1, n) for n in nodes)
    graph += _fd(2, b"sign_test_graph")
    graph += _fd(5, _tensor("W", [hearth_ml.INPUT_DIM, num_classes], weights))
    graph += _fd(5, _tensor("b", [num_classes], bias))
    if pad_floats:
        graph += _fd(5, _tensor("pad", [pad_floats], [],
                                raw_data=b"\x00" * (4 * pad_floats)))
    graph += _fd(11, _value_info(input_name, input_elem, input_dims))
    for k in range(extra_inputs):
        graph += _fd(11, _value_info(f"extra{k}", input_elem,
                                     ("batch", 4)))
    graph += _fd(12, _value_info(output_name, output_elem, output_dims))

    model = _vf(1, 8)                     # ir_version 8
    model += _fd(2, b"hearth-test")       # producer_name
    model += _fd(7, graph)                # graph
    if include_opset:
        model += _fd(8, _fd(1, b"") + _vf(2, opset))  # ai.onnx opset
    return model


def write(path: str, data: bytes) -> str:
    with open(path, "wb") as fh:
        fh.write(data)
    return path


def write_labels(path: str, labels) -> str:
    import json
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(labels, fh, ensure_ascii=False)
    return path


def model_logits(num_classes=3, seed=7, batches=(1, 3), feed_seed=0):
    """Pure-python forward pass of the fixture graph on the verifier's feed."""
    rng_w = random.Random(seed)
    weights = [rng_w.uniform(-1.0, 1.0)
               for _ in range(hearth_ml.INPUT_DIM * num_classes)]
    bias = [rng_w.uniform(-1.0, 1.0) for _ in range(num_classes)]
    rng = random.Random(feed_seed)
    rows = []
    for _ in batches:
        feed = [rng.uniform(-1, 1) for _ in range(hearth_ml.INPUT_DIM)]
        rows.append([sum(feed[i] * weights[j * num_classes + i]
                         for i in range(hearth_ml.INPUT_DIM)) + bias[j]
                     for j in range(num_classes)])
    return rows


class Fixture:
    """Temp dir with the standard good model + labels."""

    def __init__(self, tmp):
        self.dir = tmp
        self.model = write(os.path.join(tmp, "good.onnx"),
                           build_model_bytes())
        self.labels = write_labels(os.path.join(tmp, "labels.json"),
                                   GOOD_LABELS)


class StructuralTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        cls.fix = Fixture(cls._tmp.name)

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def verify(self, model=None, labels=None, **kwargs):
        return verify_onnx.verify_file(
            model or self.fix.model, labels or self.fix.labels, **kwargs)

    def test_good_model_accepted(self):
        verdict = self.verify()
        self.assertTrue(verdict.ok, verdict.failures())
        text = " | ".join(msg for _s, msg in verdict.rows)
        for expected in ("exactly 1 graph input",
                         f"input name '{hearth_ml.INPUT_NAME}'",
                         f"output name '{hearth_ml.OUTPUT_NAME}'",
                         "input dtype float32",
                         "output batch axis dynamic",
                         "labels JSON valid: 3 unique UTF-8 entries"):
            self.assertIn(expected, text)
        self.assertEqual(verdict.opset, 13)
        self.assertEqual(verdict.input_shape, ["batch", 63])
        self.assertEqual(verdict.output_shape, ["batch", 3])
        self.assertEqual(verdict.num_classes, 3)

    def test_good_fixture_logits_escape_unit_box(self):
        # fixture sanity: the hand-built weights must produce raw logits
        # outside [0,1] on the verifier's deterministic feed, otherwise
        # the runtime raw-logit check could not distinguish them
        rows = model_logits()
        flat = [v for row in rows for v in row]
        self.assertTrue(max(flat) > 1.0 or min(flat) < 0.0)

    def test_missing_file_rejected(self):
        verdict = self.verify(model=os.path.join(self.fix.dir, "nope.onnx"))
        self.assertFalse(verdict.ok)
        self.assertTrue(any("model file not found" in m
                            for m in verdict.failures()))

    def test_wrong_input_name_rejected(self):
        path = write(os.path.join(self.fix.dir, "bad_name.onnx"),
                     build_model_bytes(input_name="features"))
        verdict = self.verify(model=path)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("input name 'features'" in m
                            for m in verdict.failures()))

    def test_wrong_input_rank_rejected(self):
        path = write(os.path.join(self.fix.dir, "rank3.onnx"),
                     build_model_bytes(input_dims=("batch", 21, 3)))
        verdict = self.verify(model=path)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("rank 3 != 2" in m for m in verdict.failures()))

    def test_wrong_feature_dim_rejected(self):
        path = write(os.path.join(self.fix.dir, "dim64.onnx"),
                     build_model_bytes(input_dims=("batch", 64)))
        verdict = self.verify(model=path)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("feature dim 64 != 63" in m
                            for m in verdict.failures()))

    def test_wrong_input_dtype_rejected(self):
        path = write(os.path.join(self.fix.dir, "double.onnx"),
                     build_model_bytes(input_elem=DOUBLE))
        verdict = self.verify(model=path)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("elem_type 11 != 1" in m
                            for m in verdict.failures()))

    def test_wrong_output_name_rejected(self):
        path = write(os.path.join(self.fix.dir, "probs.onnx"),
                     build_model_bytes(output_name="probs"))
        verdict = self.verify(model=path)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("output name 'probs'" in m
                            for m in verdict.failures()))

    def test_wrong_output_classes_rejected(self):
        # model emits 3 logits but labels file has 4 entries
        labels = write_labels(os.path.join(self.fix.dir, "labels4.json"),
                              ["a", "b", "c", "d"])
        verdict = self.verify(labels=labels)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("output classes 3 != 4" in m
                            for m in verdict.failures()))

    def test_fixed_output_batch_rejected(self):
        path = write(os.path.join(self.fix.dir, "fixed_batch.onnx"),
                     build_model_bytes(output_dims=(1, 3)))
        verdict = self.verify(model=path)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("batch axis is fixed" in m
                            for m in verdict.failures()))

    def test_two_inputs_rejected(self):
        path = write(os.path.join(self.fix.dir, "two_in.onnx"),
                     build_model_bytes(extra_inputs=1))
        verdict = self.verify(model=path)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("exactly 1 input, found 2" in m
                            for m in verdict.failures()))

    def test_opset_too_new_rejected(self):
        path = write(os.path.join(self.fix.dir, "opset15.onnx"),
                     build_model_bytes(opset=15))
        verdict = self.verify(model=path)
        self.assertFalse(verdict.ok)
        self.assertEqual(verdict.opset, 15)
        self.assertTrue(any("opset 15 exceeds 13" in m
                            for m in verdict.failures()))

    def test_missing_opset_entry_rejected(self):
        path = write(os.path.join(self.fix.dir, "no_opset.onnx"),
                     build_model_bytes(include_opset=False))
        verdict = self.verify(model=path)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("no ai.onnx opset_import entry" in m
                            for m in verdict.failures()))

    def test_oversized_rejected(self):
        path = write(os.path.join(self.fix.dir, "big.onnx"),
                     build_model_bytes(pad_floats=600_000))  # ~2.4 MB
        verdict = self.verify(model=path)
        self.assertFalse(verdict.ok)
        self.assertGreater(verdict.size_bytes, hearth_ml.MAX_MODEL_BYTES)
        self.assertTrue(any("exceeds" in m and "byte cap" in m
                            for m in verdict.failures()))

    def test_garbage_bytes_rejected_without_crash(self):
        path = write(os.path.join(self.fix.dir, "garbage.onnx"),
                     b"\xff\xff\xff\xff not a protobuf at all")
        verdict = self.verify(model=path)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("unreadable" in m for m in verdict.failures()))

    def test_truncated_protobuf_rejected(self):
        good = build_model_bytes()
        path = write(os.path.join(self.fix.dir, "truncated.onnx"),
                     good[: len(good) // 2])
        verdict = self.verify(model=path)
        self.assertFalse(verdict.ok)


class LabelCheckTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        cls.fix = Fixture(cls._tmp.name)

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def test_duplicate_labels_rejected(self):
        path = write_labels(os.path.join(self.fix.dir, "dup.json"),
                            ["a", "b", "a"])
        verdict = verify_onnx.verify_file(self.fix.model, path)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("duplicate" in m for m in verdict.failures()))

    def test_invalid_utf8_labels_rejected(self):
        path = os.path.join(self.fix.dir, "bad_utf8.json")
        with open(path, "wb") as fh:
            fh.write(b'["\xff", "a", "b"]')
        verdict = verify_onnx.verify_file(self.fix.model, path)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("not valid UTF-8" in m
                            for m in verdict.failures()))

    def test_non_array_labels_rejected(self):
        path = os.path.join(self.fix.dir, "obj.json")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write('{"a": 1}')
        verdict = verify_onnx.verify_file(self.fix.model, path)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("array" in m for m in verdict.failures()))

    def test_missing_labels_file_rejected(self):
        verdict = verify_onnx.verify_file(
            self.fix.model, os.path.join(self.fix.dir, "absent.json"))
        self.assertFalse(verdict.ok)
        self.assertTrue(any("labels invalid" in m
                            for m in verdict.failures()))

    def test_classes_override_mismatch_rejected(self):
        verdict = verify_onnx.verify_file(self.fix.model, self.fix.labels,
                                          classes=24)
        self.assertFalse(verdict.ok)
        failures = " | ".join(verdict.failures())
        self.assertIn("output classes 3 != 24", failures)
        self.assertIn("labels count 3 != --classes 24", failures)

    def test_classes_override_consistent_accepted(self):
        verdict = verify_onnx.verify_file(self.fix.model, self.fix.labels,
                                          classes=3)
        self.assertTrue(verdict.ok, verdict.failures())


class RuntimeTests(unittest.TestCase):
    ort = None

    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        cls.fix = Fixture(cls._tmp.name)  # fixtures need no onnxruntime
        try:
            import onnxruntime as ort
        except ImportError:
            return
        cls.ort = ort

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def _skip_without_ort(self):
        if self.ort is None:
            self.skipTest("onnxruntime not installed")

    def test_runtime_inference_passes(self):
        self._skip_without_ort()
        verdict = verify_onnx.verify_file(self.fix.model, self.fix.labels)
        self.assertTrue(verdict.ok, verdict.failures())
        text = " | ".join(msg for _s, msg in verdict.rows)
        self.assertIn("onnxruntime inference [1,63]", text)
        self.assertIn("onnxruntime inference [3,63]", text)
        self.assertIn("raw logits confirmed", text)

    def test_softmax_graph_rejected_end_to_end(self):
        self._skip_without_ort()
        path = write(os.path.join(self.fix.dir, "softmax.onnx"),
                     build_model_bytes(softmax=True))
        verdict = verify_onnx.verify_file(path, self.fix.labels)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("softmax" in m for m in verdict.failures()))

    def test_require_runtime_without_ort_fails(self):
        if self.ort is not None:
            self.skipTest("onnxruntime is installed")
        verdict = verify_onnx.verify_file(self.fix.model, self.fix.labels,
                                          require_runtime=True)
        self.assertFalse(verdict.ok)
        self.assertTrue(any("--require-runtime" in m
                            for m in verdict.failures()))

    def test_runtime_skips_cleanly_without_ort(self):
        if self.ort is not None:
            self.skipTest("onnxruntime is installed")
        verdict = verify_onnx.verify_file(self.fix.model, self.fix.labels)
        self.assertTrue(verdict.ok, verdict.failures())
        skips = [msg for status, msg in verdict.rows if status == "SKIP"]
        self.assertTrue(any("runtime inference skipped" in m for m in skips))
        self.assertTrue(any("raw-logit check skipped" in m for m in skips))


class LogitHeuristicTests(unittest.TestCase):
    """verify_onnx.assess_logits is pure python — cover it without ORT."""

    def test_raw_logits_pass(self):
        ok, msg = verify_onnx.assess_logits([[5.2, -1.7, 0.3],
                                             [0.0, 12.9, -3.1]])
        self.assertTrue(ok)
        self.assertIn("escapes [0,1]", msg)

    def test_softmax_rows_fail(self):
        ok, msg = verify_onnx.assess_logits([[0.7, 0.2, 0.1],
                                             [0.1, 0.8, 0.1]])
        self.assertFalse(ok)
        self.assertIn("softmax", msg)

    def test_bounded_but_not_softmax_passes(self):
        # all values in [0,1] but rows do NOT sum to 1 — weakly scaled
        # raw logits, not a softmax
        ok, _msg = verify_onnx.assess_logits([[0.5, 0.2, 0.05],
                                              [0.01, 0.02, 0.0]])
        self.assertTrue(ok)

    def test_non_finite_fails(self):
        ok, msg = verify_onnx.assess_logits([[float("nan"), 0.1, 0.2]])
        self.assertFalse(ok)
        self.assertIn("non-finite", msg)
        ok, _msg = verify_onnx.assess_logits([[float("inf"), 0.0, 0.0]])
        self.assertFalse(ok)

    def test_empty_fails(self):
        ok, _msg = verify_onnx.assess_logits([])
        self.assertFalse(ok)


class ProtobufReaderTests(unittest.TestCase):
    def test_roundtrip_dimensions(self):
        vi = _value_info("landmarks", FLOAT32, ("batch", 63))
        parsed = verify_onnx._parse_value_info(vi)
        self.assertEqual(parsed["name"], "landmarks")
        self.assertEqual(parsed["elem_type"], 1)
        self.assertEqual(parsed["dims"], ["batch", 63])

    def test_fixed_dimension_roundtrip(self):
        vi = _value_info("logits", FLOAT32, (1, 24))
        self.assertEqual(verify_onnx._parse_value_info(vi)["dims"],
                         [1, 24])

    def test_varint_encoding(self):
        for value in (0, 1, 127, 128, 300, 2 ** 31, 2 ** 62):
            self.assertEqual(verify_onnx._read_varint(_varint(value), 0),
                             (value, len(_varint(value))))

    def test_garbage_raises_proto_error(self):
        with self.assertRaises(verify_onnx._ProtoError):
            verify_onnx.parse_onnx_structure(b"\xff" * 16)
        with self.assertRaises(verify_onnx._ProtoError):
            verify_onnx._read_varint(b"\x80\x80\x80", 0)


class CliTests(unittest.TestCase):
    """Exercise the argparse entry point exactly as CI / owners would."""

    def run_cli(self, *argv):
        return subprocess.run(
            [sys.executable, SCRIPT, *argv],
            capture_output=True, text=True, timeout=120)

    def test_cli_good_model_exits_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            fix = Fixture(tmp)
            result = self.run_cli("--model", fix.model,
                                  "--labels", fix.labels)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("[PASS]", result.stdout)
            self.assertIn("ACCEPTED", result.stdout)

    def test_cli_broken_model_exits_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp:
            fix = Fixture(tmp)
            broken = write(os.path.join(tmp, "broken.onnx"),
                           build_model_bytes(input_name="wrong"))
            result = self.run_cli("--model", broken, "--labels", fix.labels)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("[FAIL]", result.stdout)
            self.assertIn("REJECTED", result.stdout)

    def test_cli_missing_file_exits_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp:
            fix = Fixture(tmp)
            result = self.run_cli("--model", os.path.join(tmp, "no.onnx"),
                                  "--labels", fix.labels)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("model file not found", result.stdout)


if __name__ == "__main__":
    unittest.main()
