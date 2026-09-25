#!/usr/bin/env python3
"""Hearth sign-classifier ML contract — the single source of truth.

Frozen deployment contract for the on-device sign classifiers
(ASL: 24 static letters, ArSL: 28 letters). Everything in this folder
(train.py, evaluate.py, verify_onnx.py) imports these values; never
re-implement them locally:

    input    tensor "landmarks"  [batch, 63]   float32
    output   tensor "logits"     [batch, N]    float32, RAW scores
             (no softmax baked in), dynamic batch axis
    opset    ai.onnx <= 13
    size     <= MAX_MODEL_BYTES (2 MiB hard cap)
    labels   sibling JSON file: array of N unique, non-empty UTF-8 strings
    features MediaPipe 21 hand landmarks x (x, y, z), normalized by
             translate-wrist-to-origin then divide by palm size
             (3D distance landmark 0 -> landmark 9, middle-finger MCP)

This module MUST stay importable on bare CPython (no numpy / torch /
onnx): the heavyweight imports happen lazily inside the functions that
need them, so CI machines with only system python3 can run the
contract tests unmodified.
"""

from __future__ import annotations

import json
import math
import os

# ── Frozen contract constants ────────────────────────────────────────────────
INPUT_DIM = 63                          # 21 landmarks x (x, y, z)
NUM_LANDMARKS = 21
INPUT_NAME = "landmarks"
OUTPUT_NAME = "logits"
ONNX_OPSET = 13                         # maximum accepted ai.onnx opset
MAX_MODEL_BYTES = 2 * 1024 * 1024       # 2 MB hard cap
RUNTIME_THRESHOLD = 0.4                 # app-side softmax-confidence gate

# MediaPipe hand-landmark order:
#   0 wrist; 1-4 thumb (CMC, MCP, IP, TIP); 5-8 index; 9-12 middle;
#   13-16 ring; 17-20 pinky. Landmark 9 = middle-finger MCP.
WRIST = 0
MIDDLE_MCP = 9

DEGENERATE_PALM_EPS = 1e-6              # palm size at/below this is fatal

# ── Canonical label sets ─────────────────────────────────────────────────────
# 24 STATIC ASL letters. J and Z are motion letters — excluded because a
# single-frame landmark classifier cannot represent them.
STATIC_ASL_LABELS = [
    "A", "B", "C", "D", "E", "F", "G", "H", "I",
    "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T",
    "U", "V", "W", "X", "Y",
]

# 28 canonical ArSL letters, byte-identical to the deployed classifier's
# labels file (autoresearch2/final/arsl_classifier_labels.json).
# Codepoint order: U+0627 U+0628 U+062A U+062B U+062C U+062D U+062E U+062F
#   U+0630 U+0631 U+0632 U+0633 U+0634 U+0635 U+0636 U+0637 U+0638 U+0639
#   U+063A U+0641 U+0642 U+0643 U+0644 U+0645 U+0646 U+0647 U+0648 U+064A
# (plain letters only — no hamza forms, no tatweel, no lam-alef ligatures).
CANONICAL_ARSL_LABELS = [
    "ا", "ب", "ت", "ث", "ج", "ح", "خ", "د", "ذ",
    "ر", "ز", "س", "ش", "ص", "ض", "ط", "ظ", "ع", "غ",
    "ف", "ق", "ك", "ل", "م", "ن", "ه", "و", "ي",
]

DATASET_LABELS = {"asl": STATIC_ASL_LABELS, "arsl": CANONICAL_ARSL_LABELS}


# ── Landmark normalization ───────────────────────────────────────────────────
def _normalize_flat(values) -> list:
    """Hearth's exact per-sample normalization over 63 flat floats.

    1. subtract the wrist (landmark 0) xyz from every point;
    2. palm_size = 3D distance wrist -> landmark 9 (middle MCP);
    3. divide every coordinate by palm_size.

    No per-feature standardization, no per-axis scaling, no min-max.
    Raises ValueError on wrong length or a degenerate (near-zero) palm.
    """
    if len(values) != INPUT_DIM:
        raise ValueError(
            f"expected {INPUT_DIM} landmark values, got {len(values)}")

    pts = [float(v) for v in values]
    wx, wy, wz = pts[0], pts[1], pts[2]
    for i in range(NUM_LANDMARKS):
        pts[i * 3 + 0] -= wx
        pts[i * 3 + 1] -= wy
        pts[i * 3 + 2] -= wz

    mx = pts[MIDDLE_MCP * 3 + 0]
    my = pts[MIDDLE_MCP * 3 + 1]
    mz = pts[MIDDLE_MCP * 3 + 2]
    palm = math.sqrt(mx * mx + my * my + mz * mz)
    if not (palm > DEGENERATE_PALM_EPS) or not math.isfinite(palm):
        raise ValueError(
            "degenerate palm: 3D distance wrist->landmark 9 is "
            f"{palm:g} (<= {DEGENERATE_PALM_EPS:g}); landmarks are "
            "unusable for the Hearth contract")
    return [v / palm for v in pts]


def _mirror_flat(values) -> list:
    """Horizontal flip of one raw sample: x -> 1 - x (y and z untouched)."""
    if len(values) != INPUT_DIM:
        raise ValueError(
            f"expected {INPUT_DIM} landmark values, got {len(values)}")
    out = [float(v) for v in values]
    for i in range(NUM_LANDMARKS):
        out[i * 3 + 0] = 1.0 - out[i * 3 + 0]
    return out


def _is_row_of(seq, width) -> bool:
    try:
        return len(seq) > 0 and all(len(r) == width for r in seq)
    except TypeError:
        return False


def _all_scalars(seq) -> bool:
    try:
        return all(not hasattr(v, "__len__") for v in seq)
    except TypeError:
        return False


def _apply_per_sample(data, fn):
    """Dispatch flat / [N,63] / [21,3] input, apply `fn` per sample.

    Returns (python_result, was_array, result_shape) where result_shape
    is (63,), (N, 63) or (21, 3) — used to rebuild array inputs.
    """
    was_array = hasattr(data, "tolist")  # numpy ndarray (or torch tensor)
    raw = data.tolist() if was_array else data

    try:
        len(raw)
    except TypeError:
        raise ValueError(
            "landmarks must be a flat sequence of 63 floats, an [N,63] "
            "batch, or an [21,3] point list")

    if len(raw) == INPUT_DIM and _all_scalars(raw):
        return fn(list(raw)), was_array, (INPUT_DIM,)
    if len(raw) == NUM_LANDMARKS and _is_row_of(raw, 3):
        flat = [float(v) for point in raw for v in point]
        return fn(flat), was_array, (NUM_LANDMARKS, 3)
    if _is_row_of(raw, INPUT_DIM):
        return ([fn([float(v) for v in row]) for row in raw],
                was_array, (len(raw), INPUT_DIM))
    raise ValueError(
        f"landmarks must be flat {INPUT_DIM}, [N,{INPUT_DIM}] or "
        f"[{NUM_LANDMARKS},3]; got {len(raw)} entries")


def _rebuild_as_array(result, shape):
    """Wrap a pure-python result back into a numpy array when possible."""
    try:
        import numpy as np
    except ImportError:
        return result
    return np.asarray(result, dtype=np.float32).reshape(shape)


def normalize_landmarks(landmarks):
    """Contract normalization; accepts flat 63, [N,63] batch or [21,3].

    Pure-python math so results are bit-identical with or without numpy.
    Numpy arrays in -> numpy float32 array out (same shape); plain
    sequences in -> nested python lists out. Raises ValueError for wrong
    shapes and for degenerate palms (wrist == middle MCP).
    """
    result, was_array, shape = _apply_per_sample(landmarks, _normalize_flat)
    return _rebuild_as_array(result, shape) if was_array else result


def mirror_landmarks(landmarks):
    """Horizontal-mirror augmentation helper: x -> 1 - x on every x.

    Apply to RAW image-space landmarks (MediaPipe coordinates in [0,1])
    BEFORE normalize_landmarks — mirroring wrist-relative coordinates
    would not produce the mirrored hand. Accepts the same shapes as
    normalize_landmarks and mirrors every sample.
    """
    result, was_array, shape = _apply_per_sample(landmarks, _mirror_flat)
    return _rebuild_as_array(result, shape) if was_array else result


# ── Labels JSON ──────────────────────────────────────────────────────────────
def _validate_labels(labels) -> list:
    if not isinstance(labels, list):
        raise ValueError(f"labels must be a JSON array, got {type(labels).__name__}")
    if not labels:
        raise ValueError("labels must contain at least one entry")
    for entry in labels:
        if not isinstance(entry, str) or not entry:
            raise ValueError(
                f"label entries must be non-empty strings, got {entry!r}")
    if len(set(labels)) != len(labels):
        duplicates = sorted({l for l in labels if labels.count(l) > 1})
        raise ValueError(f"duplicate labels: {duplicates}")
    return list(labels)


def save_labels(path, labels) -> None:
    """Write the sibling labels JSON (UTF-8, ensure_ascii=False, 2-space)."""
    labels = _validate_labels(labels)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(labels, fh, ensure_ascii=False, indent=2)
        fh.write("\n")


def load_labels(path) -> list:
    """Read and validate a labels JSON file (UTF-8 array of unique strings)."""
    with open(path, "rb") as fh:
        raw = fh.read()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError(f"labels file is not valid UTF-8: {exc}") from exc
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"labels file is not valid JSON: {exc}") from exc
    return _validate_labels(parsed)


# ── Landmark CSV loading ─────────────────────────────────────────────────────
def _row_features_ok(cells) -> bool:
    """True when the first 63 cells all parse as floats (i.e. a data row)."""
    if len(cells) != INPUT_DIM + 1:
        return False
    try:
        for cell in cells[:INPUT_DIM]:
            float(cell)
    except ValueError:
        return False
    return True


def load_landmarks_csv(path):
    """Load a 64-column landmark CSV: 63 feature columns + label LAST.

    Tolerant by design:
      * optional header row — detected when any of the first 63 cells of
        the first row is not numeric (``x0,...,z20,label`` style);
      * UTF-8 BOM, CRLF line endings, blank lines (ignored silently);
      * spaces and quotes around the label cell are stripped;
      * malformed data rows (wrong column count, non-float feature,
        empty label) are skipped and counted, never silently dropped.

    Returns ``(features, labels, skipped)`` where features is a list of
    63-float lists of RAW (unnormalized) landmark coordinates and labels
    is a list of stripped label strings (numeric labels stay strings).
    """
    import csv

    features: list = []
    labels: list = []
    skipped = 0
    saw_first_row = False
    with open(path, newline="", encoding="utf-8-sig") as fh:
        for row in csv.reader(fh):
            cells = [c.strip() for c in row]
            if not cells or all(c == "" for c in cells):
                continue
            if not saw_first_row:
                saw_first_row = True
                header = (len(cells) != INPUT_DIM + 1) or any(
                    _to_float_or_none(cell) is None
                    for cell in cells[:INPUT_DIM])
                if header:
                    continue  # header row consumed
            if _row_features_ok(cells):
                label = cells[INPUT_DIM]
                if not label:
                    skipped += 1
                    continue
                features.append([float(v) for v in cells[:INPUT_DIM]])
                labels.append(label)
            else:
                skipped += 1
    return features, labels, skipped


def _to_float_or_none(cell):
    try:
        return float(cell)
    except ValueError:
        return None


# ── Torch model + ONNX export (lazy imports) ─────────────────────────────────
def build_reference_mlp(num_classes: int, widths=(128, 64), dropout=0.3):
    """The reference classifier architecture (torch, imported lazily).

    63 -> Linear -> BatchNorm1d -> ReLU -> Dropout
      -> Linear -> BatchNorm1d -> ReLU -> Dropout
      -> Linear(num_classes)   (raw logits)
    """
    import torch.nn as nn

    layers = []
    prev = INPUT_DIM
    for width in widths:
        layers.append(nn.Linear(prev, width))
        layers.append(nn.BatchNorm1d(width))
        layers.append(nn.ReLU())
        layers.append(nn.Dropout(dropout))
        prev = width
    layers.append(nn.Linear(prev, num_classes))
    return nn.Sequential(*layers)


def export_onnx(model, output_path: str) -> int:
    """Export `model` under the frozen contract; returns the file size.

    Opset 13, dynamic batch, names "landmarks"/"logits". Torch >= 2.9's
    onnxscript-backed exporter IGNORES the requested opset and emits
    ai.onnx 18, so the opset is pinned back down with the `onnx` package
    (our graphs only use classic ops that exist since opset 13). Raises
    ValueError when the export would exceed MAX_MODEL_BYTES.
    """
    import torch

    model.eval()
    dummy = torch.randn(1, INPUT_DIM)
    torch.onnx.export(
        model,
        dummy,
        output_path,
        input_names=[INPUT_NAME],
        output_names=[OUTPUT_NAME],
        dynamic_axes={
            INPUT_NAME: {0: "batch"},
            OUTPUT_NAME: {0: "batch"},
        },
        opset_version=ONNX_OPSET,
    )

    try:
        import onnx
    except ImportError as exc:
        raise ImportError(
            "the onnx package is required to pin the exported opset back "
            "to 13 — run inside the research venv or: pip install onnx"
        ) from exc
    loaded = onnx.load(output_path)
    changed = False
    for entry in loaded.opset_import:
        if entry.domain in ("", "ai.onnx") and entry.version > ONNX_OPSET:
            entry.version = ONNX_OPSET
            changed = True
    if changed:
        onnx.save(loaded, output_path)

    size = os.path.getsize(output_path)
    if size > MAX_MODEL_BYTES:
        raise ValueError(
            f"exported model is {size} bytes > {MAX_MODEL_BYTES} cap")
    return size


# ── Numeric helper shared by trainers/evaluators ─────────────────────────────
def softmax(values) -> list:
    """Numerically stable softmax over a sequence of floats."""
    vals = [float(v) for v in values]
    peak = max(vals)
    exps = [math.exp(v - peak) for v in vals]
    total = sum(exps)
    return [e / total for e in exps]


if __name__ == "__main__":  # pragma: no cover — importable module
    raise SystemExit(
        "hearth_ml is a library; run train.py / evaluate.py / verify_onnx.py")
