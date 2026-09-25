# Arabic Fingerspelling Classifier — Static Alphabet Campaign (2026-09-25)

Branch `sign-language`. Trainer: `research/sign/arsl/train_arsl.py` (self-contained, argparse).
Raw measured numbers: `research/sign/arsl/results.json`. Artifacts:
`research/sign/arsl/artifacts/{arsl_classifier.onnx, arsl_classifier_labels.json}`.

Environment: python from the prior campaign's venv — torch 2.13.0, onnx 1.22.0,
onnxruntime 1.20.0, mediapipe 0.10.14, numpy 2.5.2 (macOS arm64, CPU only).

## Honest capability statement

This release classifies **static Arabic-alphabet fingerspelling hand shapes**
(one hand, one frame, 28 canonical letters ا…ي). It is **not** Saudi Sign
Language (SASL) sentence or word recognition — see `research/sign/SAUDI-DATA.md`
for the Phase 2 path. Training provenance is genuinely Saudi-inclusive: the
joint-training rows come from ArSL2018, collected in **Al Khobar, Saudi Arabia**
(40+ signers), alongside the AASL RGB corpus; the alphabet itself is the
standard Arabic fingerspelling used across Arab deaf communities including KSA.

## Method

- **Input**: 21 MediaPipe hand landmarks x (x,y,z) = 63 floats, normalized
  exactly as the app does: wrist (landmark 0) subtracted from every point,
  all coordinates divided by palm size = 3D distance landmark 0 → landmark 9
  (middle-finger MCP). No other scaling.
- **Architecture**: BatchNorm MLP 63 → 128 → 64 → 28 (Linear + BatchNorm1d +
  ReLU + Dropout 0.3 per hidden block, raw logits out). 80,641-byte ONNX.
- **Training recipe (identical to prior campaign E1006)**: Adam lr 1e-3,
  batch 64, 50 epochs (fixed — the prior runner used no early stopping and the
  val split is the report split, so best-epoch selection on it would inflate
  the numbers; the trainer keeps this honest discipline), seed 42, mirror
  augmentation (negate x after normalization, p=0.5 per sample per epoch).
- **Splits**: random 85/15 with a fresh `random.Random(42)` per-sample draw
  over the cache order — **bit-exact reproduction of the prior campaign's
  split** (recomputed split hash `f78ce9750b90cc11` == the hash recorded in
  the E1006 record). AASL: 5,725 train / 1,030 val. Survivors: 2,695 train /
  473 holdout (same procedure). The split is random, **not
  signer-independent** (no signer labels exist in these sources) — validation
  numbers are image-level, not signer-level, claims.
- **Data**: AASL cache `arsl_static.pkl` 6,755 landmark sets (of 7,055 images,
  300 failed MediaPipe extraction); cross-dataset `arsl_pilot.pkl` 6,299;
  `arsl2018_survivors.pkl` 3,168 (of 47,447 scanned, 6.7% survived MediaPipe
  on 64×64 grayscale).

## Experiments (all numbers measured this run)

| run | recipe | val macro_f1 | arsl_pilot macro_f1 | survivors holdout macro_f1 | survivors full macro_f1 |
|---|---|---|---|---|---|
| baseline | AASL + mirror, wd 1e-4 (E1006 replica) | 0.9714 | 0.8376 | 0.6020 | 0.6057 (fully unseen) |
| wd_1e_3 | AASL + mirror, wd 1e-3 | 0.9679 | 0.8718 | 0.5990 | 0.6016 (fully unseen) |
| **joint** | AASL + mirror + survivors(85%, w=0.3), wd 1e-3 | **0.9686** | **0.9629** | **0.6782** | 0.7215 (85% seen in training) |

Recorded E1006 from the prior campaign (same recipe, same split): val 0.9639,
arsl_pilot 0.8747, survivors-full 0.6088. The replica differs only in torch
parameter-init RNG (the prior runner left init unseeded; this trainer seeds
`torch.manual_seed(42)` before model construction for determinism — verified
by a bit-identical re-run and byte-identical ONNX re-export). The observed
deltas (val +0.0075, pilot −0.0371, survivors-full −0.0031) are the honest
run-to-run noise band for this architecture; the joint candidate's pilot gain
(+0.088 to +0.125 over either AASL-only run) is far outside it.

Weight-decay sweep result: 1e-3 beats 1e-4 on cross-dataset pilot
(0.8718 vs 0.8376) at a small internal-val cost (−0.0035) — chosen for the
joint run. The joint run (survivor sample weight 0.3) is the overall winner:
it lifts the cross-dataset pilot macro_f1 to 0.9629 and the survivor holdout
to 0.6782 while staying within 0.0028 of the best internal val.

### Selection rule (stated before ranking)

1. Gate: internal val macro_f1 ≥ (best candidate's val) − 0.005.
2. Rank by held-out cross-dataset `arsl_pilot` macro_f1 (the only surface no
   candidate ever trained on that is large and fully independent).
3. Ties within 0.002 broken by survivors-holdout macro_f1, then val.

Gated set {joint 0.9686, wd_1e_3 0.9679, baseline 0.9714→gate 0.9664};
winner **joint** (pilot 0.9629 ≫ 0.8718 ≫ 0.8376).

## Threshold tables (accuracy-among-accepted / coverage / ECE-among-accepted)

### joint (winner)

| surface | thr 0.3 | thr 0.4 | thr 0.5 | thr 0.6 | ECE (all) |
|---|---|---|---|---|---|
| val_aasl (n=1030) | .9728 / .9981 / .0530 | .9727 / .9971 / .0524 | .9736 / .9942 / .0520 | .9820 / .9718 / .0518 | .0534 |
| arsl_pilot (n=6299) | .9682 / .9843 / .1674 | .9715 / .9581 / .1584 | .9800 / .9038 / .1450 | .9897 / .8041 / .1195 | .1715 |
| survivors holdout (n=473) | .7761 / .9725 / .0652 | .8050 / .9218 / .0633 | .8428 / .8605 / .0554 | .9028 / .7611 / .0408 | .0661 |

### wd_1e_3 (runner-up)

| surface | thr 0.3 | thr 0.4 | thr 0.5 | thr 0.6 | ECE (all) |
|---|---|---|---|---|---|
| val_aasl (n=1030) | .9718 / .9981 / .0470 | .9746 / .9932 / .0470 | .9765 / .9903 / .0468 | .9812 / .9786 / .0469 | .0474 |
| arsl_pilot (n=6299) | .8998 / .9868 / .1092 | .9121 / .9624 / .1103 | .9318 / .8724 / .0937 | .9485 / .7711 / .0727 | .1086 |
| survivors holdout (n=473) | .6953 / .9852 / .1547 | .7133 / .9514 / .1544 | .7332 / .9112 / .1530 | .7643 / .8520 / .1453 | .1555 |

Calibration note: the joint model is overconfident on arsl_pilot (ECE 0.1715)
— errors there are confidently wrong more often than on AASL. The 0.4 accept
gate recovers most of it (accepted accuracy .9715 at 95.8% coverage).

## Per-class recall (joint winner)

- **val_aasl**: worst ط .862, ظ .886, ج .893 — ف .974, ي 1.000. Full table in
  `results.json` (`candidates.joint.evals.val_aasl.per_class_recall`).
- **arsl_pilot**: worst ر .769, ص .849, ق .868, ح .873; ف .972, ي 1.000 —
  the historically catastrophic classes ف (.106–.157) and ب are fixed by joint
  training; ر remains the weakest pilot class.
- **survivors holdout** (n=473, per-class counts 1–41, so per-class numbers
  are coarse): worst ي .000, ح .000, ض .167, ق .412, ذ .417, **ف .450** —
  ف and ي remain the honest weak spots on ArSL2018-style (grayscale,
  Saudi-collected) input even after training on 85% of that set. The holdout
  zeros sit on tiny counts (ي: 41 samples all missed; ح: 7 samples).

### Top-5 confusion pairs (joint)

- val_aasl: ط→ظ 4, ج→ح 3, خ→ح 2, ذ→د 2, ظ→ط 2
- arsl_pilot: ر→د 43, ق→ذ 34, ح→ج 21, ص→ض 19, و→ج 18
- survivors holdout: ض→ص 9, ق→ه 7, ر→ب 5, ط→ظ 5, ي→ا 5

## Provenance and licenses

- **AASL (RGB Arabic Alphabets Sign Language)** — primary training corpus,
  7,055 local 256px JPEGs (28 letter folders), expert-validated RGB photos,
  200+ participants. Source: Kaggle `muhammadalbrham/rgb-arabic-alphabets-sign-language-dataset`,
  fetched via HF mirror `pain/AASL`. **License ambiguity — flag before any
  weights redistribution**: the Kaggle/HF statements say CC BY-SA 4.0, the
  companion paper (arXiv 2301.11932) says CC BY 4.0, and an earlier paper
  version reportedly said CC BY-NC-SA 4.0. Treated as CC BY-SA 4.0 with
  attribution (Al-Barham et al., "RGB Arabic Alphabets Sign Language
  Dataset"); a redistributable release needs the ambiguity resolved.
- **ArSL2018 / ArASL** — 64×64 grayscale, **collected in Al Khobar, Saudi
  Arabia** (iPhone 6S, 40+ participants). Official: Mendeley Data,
  DOI [10.17632/y7pckrw6z2.1](https://data.mendeley.com/datasets/y7pckrw6z2/1),
  companion paper Latif et al., Data in Brief 23 (2019) 103777
  (DOI 10.1016/j.dib.2019.103777), **CC BY 4.0**. Only 3,168 of 47,447
  letter images survived MediaPipe landmark extraction (6.7% — tiny
  grayscale hands); those survivors are the joint-training rows and the
  holdout. (Local folder currently holds 37,301 of the files; the cache
  survivor set is what this campaign measured.)
- **ArSL-31-Pilot** (cross-dataset eval only, never trained on) — Zenodo
  DOI [10.5281/zenodo.18363162](https://zenodo.org/records/18363162),
  CC BY 4.0, pre-extracted MediaPipe x/y landmarks (z padded), Moroccan
  authors (Ibn Tofail University, Kenitra). Used as the independence probe.

## Artifact

- `research/sign/arsl/artifacts/arsl_classifier.onnx` — **80,641 bytes**,
  sha256 `bc7c34b4cb2431acfe8f441a5c61f982d066e77b268e2882eb038cc274cd029b`
- `research/sign/arsl/artifacts/arsl_classifier_labels.json` — byte-identical
  copy of the canonical 28-label UTF-8 JSON (ا ب ت ث … و ي, same order as the
  model's output columns).
- Contract verified with onnxruntime 1.20.0: input `landmarks` [-1,63]
  float32, output `logits` [-1,28] float32 raw (negatives present, verified
  not softmax), opset 13, IR 10, graph ops {Gemm, Relu} (BatchNorm folded),
  single file with 0 external initializers, batch-1 and batch-5 inference OK,
  finite logits, deterministic across sessions, ≤ 2 MB.
- Export caveat, for the record: torch 2.13's dynamo exporter emits opset 18
  (+ external-data sidecar and a caught version-converter failure when asked
  for 13). The trainer re-pins the opset to 13 and inlines the weights —
  identical to the prior campaign's exporter workaround — and the shipped
  artifact is the re-pinned, self-contained file.

## Deployment recommendation

Ship the **joint** model with the runtime threshold at **0.4** (best
accuracy-vs-coverage balance: 97.2% accepted accuracy at 95.8% coverage on
the independent pilot surface; 80.5% / 92.2% on the Saudi-collected
survivor holdout). UI label: "Arabic alphabet fingerspelling (28 letters,
static single-hand poses) — includes Saudi-collected (Al Khobar) training
data; not word/sentence sign language." Known weak classes on real-camera
grayscale-style input: ي ح ض ف ق — surface ف/ي prominently in QA. Expected
next gains, in order: (1) multi-frame temporal smoothing/voting over the
live stream (already possible app-side without model changes), (2) a Saudi
device-capture pack through the real Hearth frontend to extend the survivor
distribution with modern phone cameras (Phase 2, see SAUDI-DATA.md), (3)
temporal models for word/sentence glosses.

## Reproduction

```bash
/Users/salehalanazi/ZCodeProject/ffmpegmakercustom/scripts/ml/.ml-venv/bin/python \
    research/sign/arsl/train_arsl.py            # all 3 experiments + export + self-check
```

Defaults point at the read-only prior repo's caches and labels; outputs stay
in `research/sign/arsl/`. Seed 42 everywhere (split, torch init/dropout,
mirror draws); re-runs are bit-deterministic (verified: identical metrics and
byte-identical ONNX on re-run).
