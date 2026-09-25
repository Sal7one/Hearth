# ASL Fingerspelling Classifier — Final Campaign Report

Campaign: Agent A, ASL static letters, branch `sign-language`
Date: 2026-09-25
Script: `research/sign/asl/train_asl.py` (self-contained)
Runtime: `ffmpegmakercustom/scripts/ml/.ml-venv/bin/python` — torch 2.13.0 (CPU),
onnx 1.22.0, onnxruntime 1.20.0, numpy 2.5.2, macOS arm64, 10 threads.
Full console log of the shipped run: `research/sign/asl/run_full.log`; machine-readable
record: `research/sign/asl/artifacts/metrics.json`.

## Method

1. **Data.** 52,476 rows from the read-only prior-repo CSV
   `ffmpegmakercustom/scripts/ml/datasets/asl_landmarks.csv`
   (columns `x0,y0,z0,...,x20,y20,z20,label`; header verified on load; observed raw
   ranges x [-0.0648, 1.1059], y [-0.0512, 1.2397], z [-1.1202, 0.3900], matching the
   dataset provenance file). 24 classes, per-class counts 996 (N) to 2767 (F).
2. **Normalization (the correction the campaign exists for).** The Hearth app
   normalizes MediaPipe landmarks before inference — wrist (landmark 0) subtracted
   from every point, then all coordinates divided by the 3D distance wrist ->
   middle-finger MCP (landmark 9). The training features get the exact same transform,
   re-implemented vectorized in float64 from the reference
   (`ffmpegmakercustom/scripts/ml/hearth_ml.py :: normalize_landmarks`). Cross-check
   on 200 random rows: **max |ours − reference| = 0.0** (bitwise-identical).
   0 degenerate rows (palm <= 1e-6) found, none dropped.
3. **Split.** Seeded random per-sample shuffle (`random.Random(42)`), first 15% held
   out for validation: **train 44,605 / val 7,871**. Val per-class counts range
   144 (N) to 405 (F).
4. **Three variants trained** (same architecture and optimizer; fresh torch seed 42
   per variant so all candidates share initialization):
   - **A — E2000-equivalent baseline:** dropout 0.3.
   - **B — E2001-equivalent:** dropout 0.1.
   - **C — mirror-augmented:** the no-aug head-to-head winner's dropout + horizontal
     mirroring. Mirroring is applied on RAW image coords (`x -> 1-x`, simulating a
     flipped camera frame), then Hearth normalization is applied to the mirrored
     landmarks — i.e. exactly what the runtime would feed the model. Mirrored copies
     are appended as extra training rows (44,605 x 2 = 89,210 rows); validation is
     never augmented.
5. **Selection.** Highest VALIDATION macro_f1, pre-declared tie-break: a simpler
   candidate within 0.0005 macro_f1 of the best wins. Simplicity order A < B < C.
   (Tie never triggered — B won outright.)
6. **Export.** Winner exported to ONNX under the frozen contract (below), then
   sanity-gated twice: once in-script and once in a fresh independent process
   (both with onnxruntime 1.20.0).

## Exact hyperparameters

| Parameter | Value |
|---|---|
| Architecture | MLP 63 -> Linear(128) -> BatchNorm1d -> ReLU -> Dropout -> Linear(64) -> BatchNorm1d -> ReLU -> Dropout -> Linear(24), raw logits |
| Optimizer | Adam, lr 1e-3, weight_decay 1e-4 |
| Loss | CrossEntropyLoss (no label smoothing, no class weights) |
| Batch size | 64 |
| Max epochs | 300 |
| Early stopping | patience 30 on val macro_f1 (evaluated every epoch, best weights restored) |
| Seeds | split seed 42; torch.manual_seed(42) re-issued before each variant |
| Device | CPU (deterministic; MPS deliberately not used) |
| Dropout | A: 0.3, B: 0.1, C: 0.1 (+ mirror augmentation) |

## Results (validation, n = 7,871)

| Variant | dropout | best epoch | epochs run | train time | val macro_f1 |
|---|---|---|---|---|---|
| A baseline (E2000-equiv.) | 0.3 | 31 | 62 | 66.9 s | 0.993283 |
| **B dropout-0.1 (E2001-equiv.) — CHOSEN** | 0.1 | 28 | 59 | 64.7 s | **0.995006** |
| C B-config + mirror augmentation | 0.1 | 51 | 82 | 190.8 s | 0.993665 |

Mirror augmentation (C) HURT validation macro_f1 here (-0.0013 vs B) while tripling
train time — the Kaggle-derived images are already close to mirror-balanced in
coverage of hand poses, and the mirrored rows push the optimum past the early-stop
point. C is rejected.

Top-1 accuracy of the chosen model: **0.996189** (30 errors / 7,871).

### Deployment gates (chosen model, softmax confidence over raw logits)

ECE is 10-bin expected calibration error computed over the accepted frames at each
threshold (the population the app actually shows the user).

| Threshold | Accuracy among accepted | Coverage | ECE (10-bin, accepted) |
|---|---|---|---|
| 0.3 | 0.996315 | 0.999873 | 0.00101 |
| **0.4 (Hearth runtime gate)** | **0.996315** | **0.999873** | **0.00101** |
| 0.5 | 0.996567 | 0.999238 | 0.00092 |
| 0.6 | 0.997199 | 0.997713 | 0.00087 |

Plain top-1 (no gate): 0.996189. The model is extremely well calibrated
(ECE ~0.001) and the 0.4 gate rejects only 1 frame in 7,871 — at Hearth's deployed
threshold the accepted-frame accuracy is 99.63% at 99.99% coverage.

### Confusion findings

5 worst confused pairs (true -> predicted, val counts): **N->M: 5, G->H: 2, M->N: 2,
A->B: 1, A->D: 1** (many further pairs tie at 1; the full matrix below is the
authoritative record). Total errors: 30. The dominant residual confusion is the
M/N axis — both are folded-thumb-over-palm letters differing in finger coverage —
plus G/H (index-pointing variants). 7 of 24 letters are perfect (zero val errors):
B, C, D, K, L, Q, R.

Full 24x24 confusion matrix (rows = true, cols = predicted, `.` = diagonal,
val n = 7,871):

```text
     A  B  C  D  E  F  G  H  I  K  L  M  N  O  P  Q  R  S  T  U  V  W  X  Y
  A  .  1  0  1  0  0  0  0  0  0  0  1  0  0  0  0  0  0  0  0  0  0  1  0
  B  0  .  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0
  C  0  0  .  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0
  D  0  0  0  .  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0
  E  0  1  0  0  .  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0
  F  0  0  0  0  0  .  0  0  0  0  0  0  0  1  0  0  0  0  0  0  0  0  0  0
  G  0  0  0  0  0  0  .  2  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0
  H  0  0  0  0  0  0  1  .  0  0  0  0  0  0  1  0  0  0  0  0  0  0  0  0
  I  0  0  0  0  0  0  0  0  .  0  0  0  1  0  0  0  0  0  0  0  0  0  0  0
  K  0  0  0  0  0  0  0  0  0  .  0  0  0  0  0  0  0  0  0  0  0  0  0  0
  L  0  0  0  0  0  0  0  0  0  0  .  0  0  0  0  0  0  0  0  0  0  0  0  0
  M  0  0  0  0  0  0  0  0  0  0  0  .  2  0  0  0  0  0  0  0  0  0  0  0
  N  0  0  0  0  0  0  0  0  1  0  0  5  .  0  0  0  0  0  0  0  0  0  0  0
  O  0  0  0  0  0  0  0  0  0  1  0  0  0  .  0  0  0  0  0  0  0  0  0  0
  P  0  0  0  0  0  0  0  1  0  0  0  0  0  0  .  1  0  0  0  0  0  0  0  0
  Q  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  .  0  0  0  0  0  0  0  0
  R  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  .  0  0  0  0  0  0  0
  S  0  0  0  0  1  0  0  0  0  0  0  0  0  0  0  0  0  .  0  0  0  0  0  0
  T  1  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  .  0  0  0  0  0
  U  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  1  1  0  .  0  0  0  0
  V  0  0  0  0  0  0  0  0  0  1  0  0  0  0  0  0  0  0  0  0  .  0  0  0
  W  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  1  0  0  0  0  .  0  0
  X  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  1  0  0  0  0  .  0
  Y  0  0  0  0  1  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  .
```

## Shipped artifacts

| File | Value |
|---|---|
| `research/sign/asl/artifacts/asl_classifier.onnx` | 76,357 bytes (<= 2 MB cap) |
| sha256 | `a63ef94dea583a3c50cc5645565a866206702e96f69b7073ec3d5e0f193a0622` |
| `research/sign/asl/artifacts/asl_classifier_labels.json` | `["A","B","C","D","E","F","G","H","I","K","L","M","N","O","P","Q","R","S","T","U","V","W","X","Y"]` (24, J/Z excluded) |
| `research/sign/asl/artifacts/metrics.json` | machine-readable campaign record |

Model weights are intentionally NOT staged for git (nothing was added or committed;
the files simply live in the working tree).

### ONNX contract verification (onnxruntime 1.20.0, two independent passes — PASS)

- Loads under ORT 1.20.0; `onnx.checker` full check passes; opset import is exactly
  `ai.onnx 13` (legacy exporter `dynamo=False` + opset-pinning safety net; no
  external `.data` file).
- Input `landmarks` `['batch', 63]` float32 rank 2; output `logits` `['batch', 24]`
  float32 rank 2; dynamic batch verified (`[1,63] -> [1,24]`, `[3,63] -> [3,24]`,
  rows batch-independent).
- All logits finite; softmax NOT embedded — probe logits span [-55.2, 24.7],
  far outside [0, 1]; `softmax(logits)` rows sum to 1.0.

## Limitations (honest)

- **Single-dataset RANDOM split — NOT signer-independent.** All 52,476 samples come
  from one image dataset with no signer labels, and 15% of those same images are the
  validation set. The 0.995 macro_f1 says nothing guaranteed about new signers, new
  cameras, or new lighting; expect real-world drop.
- **Source dataset provenance:** GitHub `emna-kaaniche2003/Sign-Language-Recognition-System`
  landmark CSV (`asl_mediapipe_keypoints_dataset.csv`), extracted with MediaPipe Hands
  from Kaggle `grassknoted/asl-alphabet` (200x200 photos, ~3,000/class; the mirror
  repo is MIT-licensed, the Kaggle page states no explicit license). Images where no
  hand was detected were skipped by the original extractor.
- **Static letters only.** J and Z are motion letters and are excluded by design;
  this model cannot fingerspell them.
- **Kaggle-image distribution, not webcam distribution.** Training inputs are
  MediaPipe landmarks of studio-style photos; phone-camera landmark geometry may
  differ (aspect ratio, depth noise). No on-device capture set has been evaluated —
  the >=90% accepted-frame gate has not been run on a phone.
- Mirror augmentation was rejected on validation evidence, so left/right hand
  robustness is untested; the underlying photo dataset appears to contain both
  orientations already.
- Macro_f1 differences of ~0.002 between variants are within split noise
  (~1 val sample per class flips ~0.0002 macro_f1); the ranking B > C > A is
  consistent with the prior campaign's corrected numbers (E2001 0.9957 vs
  E2000 0.9934) but exact values differ because the split RNG differs.

## Deployment recommendation

**Ship variant B (E2001-equivalent: dropout 0.1, no augmentation) as
`asl_classifier.onnx`.**

Why B:

1. It is the outright validation winner (macro_f1 0.995006 vs 0.993283 baseline and
   0.993665 mirror-augmented) — no tie-break needed — and reproduces the prior
   campaign's corrected conclusion that dropout 0.1 beats 0.3 on this dataset.
2. It matches the deployed E2000 architecture exactly (63-128-64-24 MLP with
   BatchNorm), differing only in dropout rate — zero integration risk, and it meets
   every element of the frozen ONNX contract (verified on ORT 1.20.0).
3. At Hearth's runtime gate of 0.4 it delivers 99.63% accuracy among accepted frames
   at 99.99% coverage with ECE ~0.001 — effectively no accuracy/coverage trade-off
   on this validation set.
4. It is the simplest of the competitive candidates: no augmentation pipeline to
   reproduce, fastest to retrain (65 s), smallest behavioral surface.

C (mirror augmentation) costs 3x train time for worse validation scores and is
rejected. Keep Hearth's 0.4 threshold as-is; raising it to 0.6 trades 0.11%
coverage for 0.09% accuracy, which is not worth the rejected frames on a
captioning UI. If signer-independent performance ever needs claiming, that requires
a signer-labeled dataset and a new campaign — not this artifact.
