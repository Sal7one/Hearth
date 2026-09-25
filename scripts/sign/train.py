#!/usr/bin/env python3
"""Train a Hearth sign classifier (ASL / ArSL / custom) and export ONNX.

Trains the reference MLP (63 -> hidden -> N, BatchNorm1d + ReLU + Dropout)
on landmark CSVs, applies the contract normalization BEFORE training, and
exports under the frozen contract (see hearth_ml.py):
model.onnx + labels.json + metrics.json into --out.

Requirements: torch + onnx (available in the research venv — this script
imports them lazily and exits with a clear message when missing).

Examples:
    # ASL, built-in 24 static letters:
    python3 train.py --dataset asl --csv landmarks.csv --out out/asl

    # ArSL, built-in 28 letters, mirror augmentation:
    python3 train.py --dataset arsl --csv landmarks.csv --mirror-aug --out out/arsl

    # Custom labels file:
    python3 train.py --dataset custom --csv landmarks.csv \
        --labels labels.json --out out/custom

    # Smoke test without a dataset (synthetic clusters):
    python3 train.py --dataset custom --labels labels.json \
        --synthetic 200 --epochs 20 --out /tmp/smoke
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hearth_ml  # noqa: E402  (single source of truth for the contract)

VAL_FRACTION = 0.15          # 85/15 train/val split
EARLY_STOP_PATIENCE = 20     # epochs without val macro_f1 improvement
BATCH_SIZE = 64
LEARNING_RATE = 1e-3
WEIGHT_DECAY = 1e-4
MIN_SAMPLES = 20
MIN_CLASSES = 2


def _fail(message: str, code: int = 2) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(code)


def _import_torch():
    try:
        import torch  # noqa: PLC0415 — lazy by contract
    except ImportError:
        _fail(
            "PyTorch is not installed. train.py needs torch + onnx — run it "
            "inside the research venv "
            "(ffmpegmakercustom/scripts/ml/.ml-venv/bin/python) or: "
            "pip install torch onnx")
    return torch


def synthesize_rows(labels, rows: int, seed: int):
    """Seeded synthetic landmark clusters for pipeline smoke tests.

    NOT real training data: each class gets a random hand-like prototype
    in raw [0,1] image space plus gaussian jitter. Returns
    (features, labels) lists of RAW (unnormalized) coordinates.
    """
    rng = random.Random(seed)
    prototypes = []
    for _ in labels:
        wrist = [rng.uniform(0.35, 0.65), rng.uniform(0.7, 0.9),
                 rng.uniform(-0.05, 0.05)]
        spread = rng.uniform(0.06, 0.14)
        points = []
        for k in range(hearth_ml.NUM_LANDMARKS):
            points.extend([
                wrist[0] + rng.uniform(-spread, spread),
                wrist[1] - k * rng.uniform(0.005, 0.02),
                wrist[2] + rng.uniform(-0.03, 0.03),
            ])
        prototypes.append(points)

    per_class = max(2, -(-rows // len(labels)))  # ceil, >= 2 per class
    features, out_labels = [], []
    for proto, label in zip(prototypes, labels):
        for _ in range(per_class):
            jittered = [min(1.0, max(0.0, v + rng.gauss(0, 0.01)))
                        for v in proto]
            features.append(jittered)
            out_labels.append(label)
    order = list(range(len(features)))
    rng.shuffle(order)
    return ([features[i] for i in order], [out_labels[i] for i in order])


def macro_f1(y_true, y_pred, num_classes: int):
    """Macro-averaged F1 in pure python (labels are 0..num_classes-1)."""
    tp = [0] * num_classes
    fp = [0] * num_classes
    fn = [0] * num_classes
    for true, pred in zip(y_true, y_pred):
        if true == pred:
            tp[true] += 1
        else:
            fp[pred] += 1
            fn[true] += 1
    f1s = []
    present = {v for v in y_true} | {v for v in y_pred}
    for c in range(num_classes):
        if c not in present:
            continue
        denom = 2 * tp[c] + fp[c] + fn[c]
        f1s.append(2 * tp[c] / denom if denom else 0.0)
    return (sum(f1s) / len(f1s)) if f1s else 0.0


def per_class_recall(y_true, y_pred, num_classes: int):
    recalls = []
    for c in range(num_classes):
        total = sum(1 for v in y_true if v == c)
        hits = sum(1 for t, p in zip(y_true, y_pred) if t == c and p == c)
        recalls.append(hits / total if total else None)
    return recalls


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", choices=["asl", "arsl", "custom"],
                        default="custom",
                        help="label set: asl (24 static letters), arsl (28), "
                             "or custom (--labels required)")
    parser.add_argument("--csv", help="landmark CSV: 63 feature columns + "
                        "label LAST (see hearth_ml.load_landmarks_csv)")
    parser.add_argument("--labels", help="labels JSON (required for "
                        "--dataset custom)")
    parser.add_argument("--out", required=True, metavar="DIR",
                        help="output directory (model.onnx, labels.json, "
                             "metrics.json)")
    parser.add_argument("--hidden", default="128,64", metavar="H1,H2",
                        help="hidden layer widths (default 128,64)")
    parser.add_argument("--dropout", type=float, default=0.3,
                        help="dropout probability (default 0.3)")
    parser.add_argument("--mirror-aug", action="store_true",
                        help="augment the TRAIN split with horizontally "
                             "mirrored copies (raw [0,1] image coordinates)")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--epochs", type=int, default=300)
    parser.add_argument("--device", default="cpu",
                        help="torch device (default cpu)")
    parser.add_argument("--synthetic", type=int, default=None, metavar="ROWS",
                        help="train on ROWS synthetic rows instead of a CSV "
                             "(smoke tests only — not real data)")
    args = parser.parse_args(argv)

    # ── resolve the label set ────────────────────────────────────────────
    if args.dataset == "custom":
        if not args.labels:
            _fail("--dataset custom requires --labels PATH")
        try:
            labels = hearth_ml.load_labels(args.labels)
        except (OSError, ValueError) as exc:
            _fail(f"cannot load labels: {exc}")
    else:
        if args.labels:
            _fail(f"--labels is only valid with --dataset custom "
                  f"(--dataset {args.dataset} uses the built-in list)")
        labels = hearth_ml.DATASET_LABELS[args.dataset]
    num_classes = len(labels)
    label_index = {label: i for i, label in enumerate(labels)}
    print(f"dataset={args.dataset} classes={num_classes} seed={args.seed}")

    # ── load raw features ───────────────────────────────────────────────
    if args.synthetic is not None:
        if args.csv:
            _fail("--synthetic and --csv are mutually exclusive")
        if args.synthetic < MIN_SAMPLES:
            _fail(f"--synthetic needs >= {MIN_SAMPLES} rows")
        raw_features, raw_labels = synthesize_rows(labels, args.synthetic,
                                                   args.seed)
        skipped = 0
        print(f"loaded {len(raw_features)} SYNTHETIC rows (smoke data only)")
    else:
        if not args.csv:
            _fail("either --csv PATH or --synthetic ROWS is required")
        try:
            raw_features, raw_labels, skipped = hearth_ml.load_landmarks_csv(
                args.csv)
        except OSError as exc:
            _fail(f"cannot read CSV: {exc}")
        print(f"loaded {len(raw_features)} rows from {args.csv} "
              f"({skipped} malformed rows skipped)")

    # map string labels to indices; unknown labels (e.g. motion letters J/Z
    # in an ASL CSV) are dropped with a loud count
    unknown = sorted({l for l in raw_labels if l not in label_index})
    pairs = [(f, label_index[l]) for f, l in zip(raw_features, raw_labels)
             if l in label_index]
    if unknown:
        print(f"note: dropped rows with labels outside the target set: "
              f"{unknown}")
    if len(pairs) < MIN_SAMPLES:
        _fail(f"only {len(pairs)} usable rows (< {MIN_SAMPLES})")
    present_classes = {y for _, y in pairs}
    if len(present_classes) < MIN_CLASSES:
        _fail(f"only {len(present_classes)} classes represented "
              f"(< {MIN_CLASSES})")

    # ── deterministic 85/15 split on RAW features ───────────────────────
    random.seed(args.seed)
    order = list(range(len(pairs)))
    random.shuffle(order)
    val_count = max(1, int(len(pairs) * VAL_FRACTION))
    val_idx = set(order[:val_count])
    train_raw = [pairs[i] for i in order[val_count:]]
    val_raw = [pairs[i] for i in sorted(val_idx)]
    print(f"split: {len(train_raw)} train / {len(val_raw)} val rows")

    # ── contract normalization BEFORE training ──────────────────────────
    def normalize_all(rows_in):
        feats, ys, degenerate = [], [], 0
        for feats_raw, y in rows_in:
            try:
                feats.append(hearth_ml.normalize_landmarks(feats_raw))
            except ValueError:
                degenerate += 1
            else:
                ys.append(y)
        return feats, ys, degenerate

    x_train, y_train, bad_train = normalize_all(train_raw)
    x_val, y_val, bad_val = normalize_all(val_raw)
    if bad_train or bad_val:
        print(f"warning: skipped {bad_train} train / {bad_val} val rows "
              "with degenerate palms (wrist == middle MCP)")
    if len(x_train) < 2 or not x_val:
        _fail("not enough usable rows after normalization")

    if args.mirror_aug:
        mirrored = []
        m_labels = []
        for feats_raw, y in train_raw:
            try:
                mirrored.append(hearth_ml.normalize_landmarks(
                    hearth_ml.mirror_landmarks(feats_raw)))
                m_labels.append(y)
            except ValueError:
                continue  # degenerate palm: already counted above
        x_train += mirrored
        y_train += m_labels
        print(f"mirror augmentation: +{len(mirrored)} train rows "
              f"(total {len(x_train)})")

    # ── torch training ──────────────────────────────────────────────────
    torch = _import_torch()
    random.seed(args.seed)
    torch.manual_seed(args.seed)
    try:
        import numpy as np  # noqa: F401 — seed numpy when present
        np.random.seed(args.seed)
    except ImportError:
        pass

    widths = []
    for part in args.hidden.split(","):
        try:
            widths.append(int(part.strip()))
        except ValueError:
            _fail(f"--hidden must be comma-separated ints, got {args.hidden!r}")
    if not widths or any(w <= 0 for w in widths):
        _fail(f"--hidden must be positive ints, got {args.hidden!r}")

    device = torch.device(args.device)
    model = hearth_ml.build_reference_mlp(num_classes, tuple(widths),
                                          args.dropout).to(device)

    x_train_t = torch.tensor(x_train, dtype=torch.float32, device=device)
    y_train_t = torch.tensor(y_train, dtype=torch.long, device=device)
    x_val_t = torch.tensor(x_val, dtype=torch.float32, device=device)
    y_val_t = torch.tensor(y_val, dtype=torch.long, device=device)

    optimizer = torch.optim.Adam(model.parameters(), lr=LEARNING_RATE,
                                 weight_decay=WEIGHT_DECAY)
    criterion = torch.nn.CrossEntropyLoss()
    loader_gen = torch.Generator().manual_seed(args.seed)
    batch_size = min(BATCH_SIZE, len(x_train_t))
    if batch_size < 2:
        _fail("train split too small for BatchNorm (need >= 2 rows)")

    def evaluate():
        model.eval()
        with torch.no_grad():
            preds = model(x_val_t).argmax(dim=1).tolist()
        return preds

    best_f1 = -1.0
    best_state = None
    best_epoch = 0
    stale = 0
    epochs_run = 0
    started = time.time()
    for epoch in range(args.epochs):
        epochs_run = epoch + 1
        model.train()
        perm = torch.randperm(len(x_train_t), generator=loader_gen)
        total_loss = 0.0
        batches = 0
        for start in range(0, len(perm), batch_size):
            idx = perm[start:start + batch_size]
            if len(idx) < 2:
                continue  # BatchNorm1d needs >= 2 samples
            optimizer.zero_grad()
            loss = criterion(model(x_train_t[idx]), y_train_t[idx])
            loss.backward()
            optimizer.step()
            total_loss += loss.item()
            batches += 1
        preds = evaluate()
        f1 = macro_f1(y_val, preds, num_classes)
        marker = ""
        if f1 > best_f1:
            best_f1 = f1
            best_state = {k: v.detach().clone()
                          for k, v in model.state_dict().items()}
            best_epoch = epochs_run
            stale = 0
            marker = " *"
        else:
            stale += 1
        print(f"epoch {epochs_run:3d}/{args.epochs} loss "
              f"{total_loss / max(1, batches):.4f} val_macro_f1 {f1:.4f}"
              f"{marker}")
        if stale >= EARLY_STOP_PATIENCE:
            print(f"early stop: no val macro_f1 improvement in "
                  f"{EARLY_STOP_PATIENCE} epochs (best {best_f1:.4f} @ "
                  f"epoch {best_epoch})")
            break

    if best_state is not None:
        model.load_state_dict(best_state)
    train_seconds = time.time() - started

    final_preds = evaluate()
    val_f1 = macro_f1(y_val, final_preds, num_classes)
    val_acc = sum(1 for t, p in zip(y_val, final_preds) if t == p) / len(y_val)
    recalls = per_class_recall(y_val, final_preds, num_classes)

    # ── export under the frozen contract ────────────────────────────────
    os.makedirs(args.out, exist_ok=True)
    model_path = os.path.join(args.out, "model.onnx")
    labels_path = os.path.join(args.out, "labels.json")
    metrics_path = os.path.join(args.out, "metrics.json")

    try:
        size = hearth_ml.export_onnx(model, model_path)
    except ImportError as exc:
        _fail(str(exc))
    hearth_ml.save_labels(labels_path, labels)

    metrics = {
        "val_macro_f1": round(val_f1, 6),
        "val_accuracy": round(val_acc, 6),
        "per_class_recall": {label: (None if r is None else round(r, 6))
                             for label, r in zip(labels, recalls)},
        "seed": args.seed,
        "epochs_run": epochs_run,
        "train_seconds": round(train_seconds, 3),
    }
    with open(metrics_path, "w", encoding="utf-8") as fh:
        json.dump(metrics, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    print(f"\nexported {model_path} ({size} bytes, "
          f"{size / 1024:.0f} KB)")
    print(f"labels   {labels_path}")
    print(f"metrics  {metrics_path}")
    print(f"val_macro_f1={val_f1:.4f} val_accuracy={val_acc:.4f} "
          f"best_epoch={best_epoch} epochs_run={epochs_run} "
          f"train_seconds={train_seconds:.1f}")
    print("\nnext: verify the artifact —")
    print(f"  python3 verify_onnx.py --model {model_path} "
          f"--labels {labels_path} --require-runtime")
    return 0


if __name__ == "__main__":
    sys.exit(main())
