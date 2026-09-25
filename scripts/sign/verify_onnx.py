#!/usr/bin/env python3
"""Hearth sign-classifier ONNX artifact gate.

A candidate model cannot ship unless EVERY check passes:

  1. size <= MAX_MODEL_BYTES (2 MiB)
  2. ai.onnx opset <= 13
  3. exactly 1 input named "landmarks", rank 2, dim1 == 63, float32
  4. exactly 1 output named "logits", rank 2, dim1 == len(labels)
     (or --classes), float32, dynamic batch axis
  5. loads under onnxruntime and infers [1,63] and [3,63]
  6. logits finite and NOT softmax-ed (range check: raw logits must not
     be bounded in [0,1] with rows summing to 1)
  7. labels JSON: valid UTF-8 array, correct count, unique entries

Structural checks (1-4, 7) parse the ONNX protobuf directly in pure
python — no `onnx` package required. The runtime checks (5-6) need
onnxruntime and are reported as SKIP when it is not installed; pass
--require-runtime to make a missing onnxruntime a failure (use that for
final artifact sign-off). Exit code 0 = accepted, 1 = rejected,
2 = usage error.

Usage:
    python3 verify_onnx.py --model model.onnx --labels labels.json
    python3 verify_onnx.py --model M.onnx --labels L.json --classes 24
"""

from __future__ import annotations

import argparse
import os
import random
import sys
from dataclasses import dataclass, field

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hearth_ml  # noqa: E402  (single source of truth for the contract)

PASS = "PASS"
FAIL = "FAIL"
SKIP = "SKIP"

RUNTIME_BATCHES = (1, 3)
SOFTMAX_SUM_TOL = 1e-3
BOUND_TOL = 1e-6
FEED_SEED = 0


# ── Minimal protobuf wire-format reader (ONNX is protobuf3) ─────────────────
class _ProtoError(ValueError):
    pass


def _read_varint(buf: bytes, pos: int):
    result = 0
    shift = 0
    while True:
        if pos >= len(buf):
            raise _ProtoError("truncated varint")
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7
        if shift > 70:
            raise _ProtoError("varint too long")


def _iter_fields(buf: bytes):
    """Yield (field_number, wire_type, value) over a message buffer."""
    pos = 0
    while pos < len(buf):
        tag, pos = _read_varint(buf, pos)
        field_no, wire = tag >> 3, tag & 0x07
        if field_no == 0:
            raise _ProtoError("field number 0")
        if wire == 0:
            value, pos = _read_varint(buf, pos)
        elif wire == 2:
            length, pos = _read_varint(buf, pos)
            end = pos + length
            if end > len(buf):
                raise _ProtoError("truncated length-delimited field")
            value = buf[pos:end]
            pos = end
        elif wire == 1:  # fixed64
            value = buf[pos:pos + 8]
            pos += 8
        elif wire == 5:  # fixed32
            value = buf[pos:pos + 4]
            pos += 4
        else:
            raise _ProtoError(f"unsupported wire type {wire}")
        yield field_no, wire, value


def _parse_operator_set(buf: bytes):
    domain, version = "", 0
    for field_no, _wire, value in _iter_fields(buf):
        if field_no == 1:
            domain = value.decode("utf-8", "replace")
        elif field_no == 2:
            version = int(value)
    return domain, version


def _parse_dimension(buf: bytes):
    """Dimension oneof: dim_value (int) / dim_param (str) / None."""
    for field_no, _wire, value in _iter_fields(buf):
        if field_no == 1:
            return int(value)
        if field_no == 2:
            return value.decode("utf-8", "replace")
    return None


def _parse_value_info(buf: bytes):
    """ValueInfoProto -> {name, elem_type, dims: [int|str|None]}."""
    info = {"name": "", "elem_type": 0, "dims": []}
    for field_no, _wire, value in _iter_fields(buf):
        if field_no == 1:
            info["name"] = value.decode("utf-8", "replace")
        elif field_no == 2:  # TypeProto
            for t_no, _t_wire, t_val in _iter_fields(value):
                if t_no != 1:  # tensor_type
                    continue
                for tt_no, _tt_wire, tt_val in _iter_fields(t_val):
                    if tt_no == 1:
                        info["elem_type"] = int(tt_val)
                    elif tt_no == 2:  # TensorShapeProto
                        for s_no, _s_wire, s_val in _iter_fields(tt_val):
                            if s_no == 1:
                                info["dims"].append(_parse_dimension(s_val))
    return info


def parse_onnx_structure(data: bytes) -> dict:
    """Extract opset + graph input/output ValueInfos from raw ONNX bytes.

    Raises _ProtoError on structurally invalid protobuf. Only the fields
    the contract needs are decoded; everything else is skipped.
    """
    out = {"opset": None, "opsets": [], "inputs": [], "outputs": []}
    for field_no, _wire, value in _iter_fields(data):
        if field_no == 8:  # opset_import
            out["opsets"].append(_parse_operator_set(value))
        elif field_no == 7:  # graph
            for g_no, _g_wire, g_val in _iter_fields(value):
                if g_no == 11:
                    out["inputs"].append(_parse_value_info(g_val))
                elif g_no == 12:
                    out["outputs"].append(_parse_value_info(g_val))
    ai_onnx = [v for domain, v in out["opsets"] if domain in ("", "ai.onnx")]
    out["opset"] = max(ai_onnx) if ai_onnx else None
    return out


# ── Logit sanity (pure logic, unit-testable without onnxruntime) ─────────────
def assess_logits(rows) -> tuple:
    """Check inferred output rows for finiteness and softmax leakage.

    Returns (ok, message). Softmax evidence = every value bounded in
    [0,1] (within BOUND_TOL) AND every row summing to 1 (SOFTMAX_SUM_TOL)
    — raw logits of a real classifier essentially always leave that box.
    """
    flat = [float(v) for row in rows for v in row]
    if not flat:
        return False, "no logits produced"
    bad = [v for v in flat if v != v or v in (float("inf"), float("-inf"))]
    if bad:
        return False, f"non-finite logits ({len(bad)} values)"
    low, high = min(flat), max(flat)
    if high > 1.0 + BOUND_TOL or low < -BOUND_TOL:
        return True, (f"raw logits confirmed: range [{low:.4f}, {high:.4f}] "
                      "escapes [0,1]")
    bounded = all(-BOUND_TOL <= v <= 1.0 + BOUND_TOL for v in flat)
    sums = [sum(float(v) for v in row) for row in rows]
    if bounded and rows and all(abs(s - 1.0) <= SOFTMAX_SUM_TOL for s in sums):
        return False, ("outputs are bounded in [0,1] and every row sums to "
                       "1 — model emits softmax probabilities, the contract "
                       "requires RAW logits")
    return True, (f"no softmax evidence: range [{low:.4f}, {high:.4f}] "
                  "(weakly-scaled but raw)")


# ── Verdict ──────────────────────────────────────────────────────────────────
@dataclass
class Verdict:
    path: str
    ok: bool = True
    rows: list = field(default_factory=list)      # (status, message)
    opset: int = 0
    input_shape: list = field(default_factory=list)
    output_shape: list = field(default_factory=list)
    size_bytes: int = 0
    num_classes: int = 0

    def counts(self):
        return (sum(1 for s, _ in self.rows if s == PASS),
                sum(1 for s, _ in self.rows if s == FAIL),
                sum(1 for s, _ in self.rows if s == SKIP))

    def failures(self):
        return [msg for status, msg in self.rows if status == FAIL]


def _check(verdict: Verdict, cond: bool, ok_msg: str, err_msg: str | None = None):
    verdict.rows.append((PASS if cond else FAIL,
                         ok_msg if cond else (err_msg or ok_msg)))
    if not cond:
        verdict.ok = False


def _skip(verdict: Verdict, msg: str):
    verdict.rows.append((SKIP, msg))


def _describe(info) -> str:
    dims = ",".join("?" if d is None else str(d) for d in info["dims"])
    return f"'{info['name']}' rank {len(info['dims'])} [{dims}] elem_type {info['elem_type']}"


def verify_file(model_path, labels_path=None, classes: int | None = None,
                require_runtime: bool = False, seed: int = FEED_SEED) -> Verdict:
    """Run every contract check; returns a Verdict (never raises for a
    bad model — rejections are recorded as FAIL rows)."""
    verdict = Verdict(path=str(model_path))

    # Labels are parsed first because the output-dim check needs the
    # expected class count; their rows are emitted in contract order.
    labels, label_error = None, None
    if labels_path is not None:
        try:
            labels = hearth_ml.load_labels(labels_path)
        except (OSError, ValueError) as exc:
            label_error = str(exc)

    expected_classes = None
    if classes is not None:
        expected_classes = classes
    elif labels is not None:
        expected_classes = len(labels)
    if expected_classes is not None:
        verdict.num_classes = expected_classes

    data, structure, parse_error = None, None, None
    exists = os.path.isfile(model_path)

    # 1. size
    if not exists:
        verdict.size_bytes = 0
        _check(verdict, False, "", f"model file not found: {model_path}")
    else:
        verdict.size_bytes = os.path.getsize(model_path)
        _check(verdict, verdict.size_bytes <= hearth_ml.MAX_MODEL_BYTES,
               f"size {verdict.size_bytes} <= {hearth_ml.MAX_MODEL_BYTES} bytes",
               f"size {verdict.size_bytes} exceeds {hearth_ml.MAX_MODEL_BYTES} "
               "byte cap")
        with open(model_path, "rb") as fh:
            data = fh.read()
        try:
            structure = parse_onnx_structure(data)
        except _ProtoError as exc:
            parse_error = f"not parseable as ONNX protobuf: {exc}"

    # 2. opset
    if exists and structure is not None:
        verdict.opset = structure["opset"] if structure["opset"] is not None else 0
        _check(verdict, structure["opset"] is not None
               and structure["opset"] <= hearth_ml.ONNX_OPSET,
               f"opset {verdict.opset} <= {hearth_ml.ONNX_OPSET}",
               "no ai.onnx opset_import entry found"
               if structure["opset"] is None
               else f"opset {verdict.opset} exceeds {hearth_ml.ONNX_OPSET}")
    elif exists and parse_error is not None:
        _check(verdict, False, "", f"opset unreadable — {parse_error}")
    verdict.input_shape = []
    verdict.output_shape = []

    if exists and structure is not None:
        inputs, outputs = structure["inputs"], structure["outputs"]

        # 3. input
        _check(verdict, len(inputs) == 1,
               f"exactly 1 graph input ({len(inputs)} found)",
               f"graph must have exactly 1 input, found {len(inputs)}")
        if len(inputs) == 1:
            inp = inputs[0]
            verdict.input_shape = inp["dims"]
            _check(verdict, inp["name"] == hearth_ml.INPUT_NAME,
                   f"input name '{inp['name']}'",
                   f"input name '{inp['name']}' != '{hearth_ml.INPUT_NAME}'")
            _check(verdict, len(inp["dims"]) == 2,
                   f"input rank 2 ({_describe(inp)})",
                   f"input rank {len(inp['dims'])} != 2 ({_describe(inp)})")
            if len(inp["dims"]) == 2:
                _check(verdict, inp["dims"][1] == hearth_ml.INPUT_DIM,
                       f"input feature dim {inp['dims'][1]} == {hearth_ml.INPUT_DIM}",
                       f"input feature dim {inp['dims'][1]} != {hearth_ml.INPUT_DIM}")
            _check(verdict, inp["elem_type"] == 1,
                   "input dtype float32",
                   f"input elem_type {inp['elem_type']} != 1 (float32)")

        # 4. output
        _check(verdict, len(outputs) == 1,
               f"exactly 1 graph output ({len(outputs)} found)",
               f"graph must have exactly 1 output, found {len(outputs)}")
        if len(outputs) == 1:
            out = outputs[0]
            verdict.output_shape = out["dims"]
            _check(verdict, out["name"] == hearth_ml.OUTPUT_NAME,
                   f"output name '{out['name']}'",
                   f"output name '{out['name']}' != '{hearth_ml.OUTPUT_NAME}'")
            _check(verdict, len(out["dims"]) == 2,
                   f"output rank 2 ({_describe(out)})",
                   f"output rank {len(out['dims'])} != 2 ({_describe(out)})")
            if len(out["dims"]) == 2:
                if expected_classes is not None:
                    _check(verdict, out["dims"][1] == expected_classes,
                           f"output classes {out['dims'][1]} == {expected_classes} expected",
                           f"output classes {out['dims'][1]} != {expected_classes} expected")
                _check(verdict, isinstance(out["dims"][0], str),
                       f"output batch axis dynamic ('{out['dims'][0]}')",
                       f"output batch axis is fixed ({out['dims'][0]!r}), "
                       "contract requires a dynamic batch")
            _check(verdict, out["elem_type"] == 1,
                   "output dtype float32",
                   f"output elem_type {out['elem_type']} != 1 (float32)")
    elif exists and parse_error is not None:
        _check(verdict, False, "", f"input checks unreadable — {parse_error}")
        _check(verdict, False, "", f"output checks unreadable — {parse_error}")

    # 5./6. runtime load + raw-logit sanity
    logits_rows = None
    if not exists or parse_error is not None or not verdict.ok:
        _skip(verdict, "runtime inference skipped (structural checks failed)")
        _skip(verdict, "raw-logit check skipped (no inference performed)")
    else:
        try:
            import onnxruntime as ort  # noqa: PLC0415 — lazy by contract
        except ImportError:
            if require_runtime:
                _check(verdict, False, "",
                       "onnxruntime not installed but --require-runtime was "
                       "given — install onnxruntime==1.20.0 and re-run")
            else:
                _skip(verdict, "onnxruntime not installed — runtime inference "
                               "skipped (pass --require-runtime to enforce)")
            _skip(verdict, "raw-logit check skipped (no inference performed)")
        else:
            runtime_ok = True
            try:
                session = ort.InferenceSession(str(model_path))
                rng = random.Random(seed)
                for batch in RUNTIME_BATCHES:
                    feed = [[rng.uniform(-1, 1) for _ in range(hearth_ml.INPUT_DIM)]
                            for _ in range(batch)]
                    outputs = session.run(None, {hearth_ml.INPUT_NAME: feed})
                    logits = outputs[0]
                    rows = [list(map(float, r)) for r in logits]
                    if logits_rows is None:
                        logits_rows = []
                    logits_rows.extend(rows)
                    verdict.rows.append((
                        PASS, f"onnxruntime inference [{batch},{hearth_ml.INPUT_DIM}] "
                        f"-> {list(logits.shape)} ok"))
            except Exception as exc:  # noqa: BLE001 — surface engine errors
                runtime_ok = False
                _check(verdict, False, "",
                       f"onnxruntime failed on this model: {exc}")
            if runtime_ok and logits_rows:
                logits_ok, logits_msg = assess_logits(logits_rows)
                _check(verdict, logits_ok, f"logits finite and raw: {logits_msg}",
                       logits_msg)
            elif runtime_ok:
                _check(verdict, False, "", "onnxruntime produced no logits")

    # 7. labels
    if labels_path is None:
        _check(verdict, False, "", "no --labels file given")
    elif label_error is not None:
        _check(verdict, False, "", f"labels invalid: {label_error}")
    else:
        _check(verdict, True,
               f"labels JSON valid: {len(labels)} unique UTF-8 entries")
        if classes is not None:
            _check(verdict, len(labels) == classes,
                   f"labels count {len(labels)} == --classes {classes}",
                   f"labels count {len(labels)} != --classes {classes}")

    return verdict


def format_report(verdict: Verdict) -> str:
    lines = [f"Hearth ONNX contract verification: {verdict.path}"]
    for status, msg in verdict.rows:
        lines.append(f"[{status}] {msg}")
    passed, failed, skipped = verdict.counts()
    verdict_word = "ACCEPTED" if verdict.ok else "REJECTED"
    lines.append(f"=> {verdict_word} "
                 f"({passed} PASS, {failed} FAIL, {skipped} SKIP)")
    return "\n".join(lines)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Verify a sign-classifier ONNX model against the "
                    "frozen Hearth deployment contract.")
    parser.add_argument("--model", required=True, help="path to the .onnx file")
    parser.add_argument("--labels", required=True,
                        help="path to the labels JSON file")
    parser.add_argument("--classes", type=int, default=None,
                        help="expected class count (default: len(labels))")
    parser.add_argument("--require-runtime", action="store_true",
                        help="fail instead of skipping when onnxruntime "
                             "is unavailable")
    args = parser.parse_args(argv)

    verdict = verify_file(args.model, args.labels, args.classes,
                          require_runtime=args.require_runtime)
    print(format_report(verdict))
    return 0 if verdict.ok else 1


if __name__ == "__main__":
    sys.exit(main())
