# scripts/sign — sign-classifier ML tooling

Training, evaluation and artifact-gating tools for the on-device sign
classifiers (ASL: 24 static letters, ArSL: 28 letters). These are plain
python3 scripts; the app itself only ever consumes the exported ONNX
artifacts.

Related research artifacts (datasets, extraction reports, run notes)
live under `research/sign/` — e.g. `research/sign/handmodels/`. Real
training runs should be reproduced from the CSVs documented there.

## Tool map

| file | purpose | needs |
|---|---|---|
| `hearth_ml.py` | Single source of truth: frozen contract constants, landmark normalization, label-set constants (`STATIC_ASL_LABELS`, `CANONICAL_ARSL_LABELS`), labels JSON helpers, tolerant 64-column landmark CSV loader, mirror augmentation helper, reference MLP builder + contract ONNX exporter | bare python3 (torch/onnx only lazily, for build/export) |
| `train.py` | Train the reference MLP on a landmark CSV (or synthetic smoke data), export `model.onnx` + `labels.json` + `metrics.json` | torch + onnx (research venv) |
| `evaluate.py` | Run an exported model on a holdout CSV: top-1, per-threshold coverage/accuracy, ECE(10), confusion grid, worst pairs; writes `evaluate_report.json` | onnxruntime (research venv) |
| `verify_onnx.py` | The artifact gate — 7 contract checks, PASS/FAIL/SKIP table, exit 0/nonzero | bare python3 (onnxruntime optional; `--require-runtime` to enforce) |
| `test_hearth_ml.py`, `test_verify_onnx.py` | Contract tests | bare python3 — the verifier fixtures are hand-serialized ONNX protobuf, no `onnx` package needed |

Sibling tool: `scripts/sign/convert/` (owned separately) converts
external pretrained checkpoints into this contract.

## The frozen deployment contract

Every artifact these tools produce or accept must satisfy:

- input tensor `"landmarks"`, shape `[batch, 63]`, `float32`;
- output tensor `"logits"`, shape `[batch, N]`, `float32`, **raw scores
  — no softmax baked into the graph**, dynamic batch axis;
- `ai.onnx` opset **≤ 13**;
- file size **≤ 2 MiB** (`MAX_MODEL_BYTES`);
- labels sidecar JSON: array of `N` unique, non-empty UTF-8 strings
  (`labels.json`, written with `ensure_ascii=False`);
- features: MediaPipe 21 hand landmarks × (x, y, z), normalized by
  `hearth_ml.normalize_landmarks` — translate the wrist (landmark 0) to
  the origin, then divide every coordinate by the palm size (3D
  distance landmark 0 → landmark 9, the middle-finger MCP). A
  degenerate palm (≤ 1e-6) is a hard error, never silently skipped.

Label sets: ASL is the 24 static letters A–Y without J/Z (motion
letters); ArSL is the canonical 28-letter order ا..ي, byte-identical to
the deployed `arsl_classifier_labels.json` (plain letters only — no
hamza forms, no tatweel, no lam-alef ligatures).

## Reproduction commands

```bash
# tests — CI machines, bare system python3, no deps:
python3 -m unittest discover -s scripts/sign -p 'test_*.py'

# train (inside the research venv, which has torch + onnx):
VENV=/Users/salehalanazi/ZCodeProject/ffmpegmakercustom/scripts/ml/.ml-venv/bin/python
$VENV train.py --dataset asl  --csv <landmarks.csv> --out out/asl
$VENV train.py --dataset arsl --csv <landmarks.csv> --mirror-aug --out out/arsl
$VENV train.py --dataset custom --csv <landmarks.csv> \
    --labels <labels.json> --out out/custom

# evaluate a holdout split:
$VENV evaluate.py --model out/asl/model.onnx --labels out/asl/labels.json \
    --csv <holdout.csv> --thresholds 0.3,0.4,0.5,0.6

# gate the artifact before it goes anywhere near the app:
python3 verify_onnx.py --model out/asl/model.onnx \
    --labels out/asl/labels.json --require-runtime

# pipeline smoke test without a dataset:
$VENV train.py --dataset custom --labels <labels.json> \
    --synthetic 200 --epochs 20 --out /tmp/sign-smoke
```

Source CSVs and the real run reports behind shipped models are
recorded under `research/sign/`; point `--csv` at those exports to
reproduce a training run. Useful knobs: `--hidden 128,64`, `--dropout`,
`--seed`, `--epochs`, `--device`. Training always normalizes features
with the contract normalization before fitting, uses an 85/15
train/val split, Adam (wd 1e-4), and early-stops after 20 epochs
without val macro-F1 improvement; `metrics.json` records
`val_macro_f1`, `val_accuracy`, `per_class_recall`, `seed`,
`epochs_run`, `train_seconds`.

## CI note

`test_hearth_ml.py` and `test_verify_onnx.py` run on system python3
with **no** numpy/torch/onnx/onnxruntime: the normalization math is
checked against hand-computed fixtures, and the ONNX fixtures are
serialized by hand as raw protobuf bytes so the verifier's structural
logic is fully exercised without the `onnx` package. Runtime-dependent
tests (onnxruntime inference, end-to-end softmax rejection, torch
architecture, numpy round-trips) skip cleanly when those packages are
absent and activate when the suite runs inside the research venv.

`verify_onnx.py` follows the same rule on the CLI: with onnxruntime
missing it reports the runtime rows as `SKIP` and still exits 0 for a
structurally valid model; pass `--require-runtime` for final sign-off
so a missing runtime is itself a failure.
