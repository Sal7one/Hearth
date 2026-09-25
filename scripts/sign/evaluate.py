#!/usr/bin/env python3
"""Evaluate a Hearth sign-classifier ONNX model on a landmark CSV.

Runs the artifact under onnxruntime (lazy import — clear error when
missing), applies the contract normalization, and reports:

  * top-1 accuracy;
  * per-threshold coverage and accuracy-among-accepted (rows whose max
    softmax probability meets the threshold);
  * ECE with 10 equal-width confidence bins;
  * the full confusion matrix as a text grid (rows = true, cols = pred);
  * the top-5 worst true->pred pairs.

Also writes evaluate_report.json (--out, default ./evaluate_report.json).

Usage:
    python3 evaluate.py --model model.onnx --labels labels.json \
        --csv holdout.csv --thresholds 0.3,0.4,0.5,0.6
"""

from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hearth_ml  # noqa: E402  (single source of truth for the contract)

INFER_BATCH = 256
ECE_BINS = 10
DEFAULT_THRESHOLDS = "0.3,0.4,0.5,0.6"


def _fail(message: str, code: int = 2) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(code)


def parse_thresholds(text: str):
    values = []
    for part in text.split(","):
        try:
            t = float(part.strip())
        except ValueError:
            _fail(f"--thresholds must be comma-separated floats, got {text!r}")
        if not 0.0 < t <= 1.0:
            _fail(f"threshold {t} outside (0, 1]")
        values.append(t)
    return values


def expected_calibration_error(confidences, corrects, bins=ECE_BINS):
    """ECE = sum_b (n_b / N) * |acc_b - conf_b|."""
    n = len(confidences)
    if n == 0:
        return 0.0
    ece = 0.0
    for b in range(bins):
        lo, hi = b / bins, (b + 1) / bins
        members = [i for i, c in enumerate(confidences)
                   if (lo <= c < hi) or (b == bins - 1 and c == 1.0)]
        if not members:
            continue
        acc = sum(corrects[i] for i in members) / len(members)
        conf = sum(confidences[i] for i in members) / len(members)
        ece += (len(members) / n) * abs(acc - conf)
    return ece


def render_confusion(labels, matrix):
    """Text grid: rows true, cols pred; returns a list of lines."""
    abbr = [label if len(label) <= 4 else label[:3] + "." for label in labels]
    cell_w = max(4, max((len(str(v)) for row in matrix for v in row),
                        default=4))
    corner = "true\\pred"
    header = corner.ljust(len(corner) + 1) + " ".join(
        a.rjust(cell_w) for a in abbr)
    lines = [header]
    for label, row in zip(abbr, matrix):
        cells = " ".join(str(v).rjust(cell_w) for v in row)
        lines.append(label.rjust(len(corner)) + " " + cells)
    return lines


def worst_pairs(labels, matrix, top=5):
    pairs = []
    for i in range(len(labels)):
        row_total = sum(matrix[i])
        for j in range(len(labels)):
            if i != j and matrix[i][j] > 0:
                pairs.append((matrix[i][j], matrix[i][j] / row_total
                              if row_total else 0.0, labels[i], labels[j]))
    pairs.sort(key=lambda p: (-p[0], -p[1], p[2], p[3]))
    return pairs[:top]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", required=True, help="path to model.onnx")
    parser.add_argument("--labels", required=True, help="path to labels.json")
    parser.add_argument("--csv", required=True,
                        help="landmark CSV (63 features + label LAST)")
    parser.add_argument("--thresholds", default=DEFAULT_THRESHOLDS,
                        help=f"comma-separated acceptance thresholds "
                             f"(default {DEFAULT_THRESHOLDS})")
    parser.add_argument("--out", default="evaluate_report.json",
                        help="report path (default ./evaluate_report.json)")
    args = parser.parse_args(argv)
    thresholds = parse_thresholds(args.thresholds)

    try:
        labels = hearth_ml.load_labels(args.labels)
    except (OSError, ValueError) as exc:
        _fail(f"cannot load labels: {exc}")
    label_index = {label: i for i, label in enumerate(labels)}

    try:
        features, csv_labels, skipped = hearth_ml.load_landmarks_csv(args.csv)
    except OSError as exc:
        _fail(f"cannot read CSV: {exc}")

    rows = []
    unmapped = {}
    degenerate = 0
    for feats, label in zip(features, csv_labels):
        if label not in label_index:
            unmapped[label] = unmapped.get(label, 0) + 1
            continue
        try:
            rows.append((hearth_ml.normalize_landmarks(feats), label_index[label]))
        except ValueError:
            degenerate += 1
    if unmapped:
        print(f"warning: {sum(unmapped.values())} rows with labels outside "
              f"the model's label set were excluded: "
              f"{sorted(unmapped.items())}")
    if degenerate:
        print(f"warning: {degenerate} rows skipped (degenerate palm)")
    if not rows:
        _fail("no usable rows after label mapping and normalization "
              f"({skipped} malformed CSV rows, {sum(unmapped.values())} "
              f"unmapped, {degenerate} degenerate)")

    try:
        import onnxruntime as ort  # noqa: PLC0415 — lazy by contract
    except ImportError:
        _fail("onnxruntime is not installed. evaluate.py needs it — run it "
              "inside the research venv "
              "(ffmpegmakercustom/scripts/ml/.ml-venv/bin/python) or: "
              "pip install onnxruntime==1.20.0")

    try:
        session = ort.InferenceSession(args.model)
    except Exception as exc:  # noqa: BLE001 — surface the engine error
        _fail(f"onnxruntime could not load {args.model}: {exc}", 1)

    input_name = session.get_inputs()[0].name
    if input_name != hearth_ml.INPUT_NAME:
        _fail(f"model input is '{input_name}' but the contract requires "
              f"'{hearth_ml.INPUT_NAME}' — artifact is not contract-shaped; "
              "run verify_onnx.py", 1)

    probs_all = []
    y_true = []
    for start in range(0, len(rows), INFER_BATCH):
        chunk = rows[start:start + INFER_BATCH]
        feed = [feats for feats, _ in chunk]
        try:
            outputs = session.run(None, {input_name: feed})
        except Exception as exc:  # noqa: BLE001 — surface the engine error
            _fail(f"inference failed on rows {start}..{start + len(chunk)}: "
                  f"{exc}", 1)
        logits = outputs[0]
        probs_all.extend(hearth_ml.softmax(row) for row in logits)
        y_true.extend(label for _, label in chunk)

    n = len(y_true)
    num_classes = len(labels)
    if probs_all and len(probs_all[0]) != num_classes:
        _fail(f"model emits {len(probs_all[0])} classes but labels.json has "
              f"{num_classes} — artifact/labels mismatch; run verify_onnx.py",
              1)

    preds = [max(range(len(p)), key=p.__getitem__) for p in probs_all]
    confidences = [max(p) for p in probs_all]
    corrects = [1 if p == t else 0 for p, t in zip(preds, y_true)]
    top1 = sum(corrects) / n

    threshold_stats = []
    for t in thresholds:
        accepted = [i for i, c in enumerate(confidences) if c >= t]
        coverage = len(accepted) / n
        acc = (sum(corrects[i] for i in accepted) / len(accepted)
               if accepted else None)
        threshold_stats.append({
            "threshold": t,
            "accepted": len(accepted),
            "coverage": round(coverage, 6),
            "accuracy_among_accepted": None if acc is None else round(acc, 6),
        })

    ece = expected_calibration_error(confidences, corrects)

    matrix = [[0] * num_classes for _ in range(num_classes)]
    for true, pred in zip(y_true, preds):
        matrix[true][pred] += 1

    print(f"\nevaluation: {args.model}")
    print(f"rows={n} (skipped {skipped} malformed, "
          f"{sum(unmapped.values())} unmapped, {degenerate} degenerate)")
    print(f"top-1 accuracy: {top1:.4f}")
    print(f"ECE ({ECE_BINS} bins): {ece:.4f}")
    print("\nthreshold   coverage   accuracy-among-accepted")
    for stat in threshold_stats:
        acc = ("n/a" if stat["accuracy_among_accepted"] is None
               else f"{stat['accuracy_among_accepted']:.4f}")
        print(f"  {stat['threshold']:<8}   {stat['coverage']:.4f}     {acc}")

    print("\nconfusion matrix (rows = true, cols = predicted):")
    for line in render_confusion(labels, matrix):
        print("  " + line)

    pairs = worst_pairs(labels, matrix)
    if pairs:
        print("\ntop worst true->pred pairs:")
        for count, rate, tlabel, plabel in pairs:
            print(f"  {tlabel!r} -> {plabel!r}: {count} "
                  f"({100 * rate:.1f}% of true {tlabel!r})")
    else:
        print("\nno misclassifications")

    report = {
        "model": os.path.abspath(args.model),
        "labels_file": os.path.abspath(args.labels),
        "csv": os.path.abspath(args.csv),
        "rows": n,
        "skipped_malformed_rows": skipped,
        "unmapped_labels": unmapped,
        "degenerate_rows": degenerate,
        "top1_accuracy": round(top1, 6),
        "ece_10_bins": round(ece, 6),
        "thresholds": threshold_stats,
        "confusion_matrix": matrix,
        "labels": labels,
        "worst_pairs": [
            {"count": c, "rate": round(r, 6), "true": t, "pred": p}
            for c, r, t, p in pairs],
    }
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print(f"\nreport written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
