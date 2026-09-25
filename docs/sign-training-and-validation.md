# Sign model training and validation

How the fingerspelling classifiers are trained and how their numbers must be
read. The companion [sign language](sign-language.md) doc defines the feature
and deployment contract; this doc defines dataset lineage, metrics and honesty
rules. The `research/sign/` REPORT and PROVENANCE files are the authoritative
records — this doc never overrides their numbers and does not repeat them,
because records are re-run and updated as training improves.

## Dataset provenance

| Dataset | What it is | How it enters training |
| --- | --- | --- |
| Kaggle `grassknoted/asl-alphabet` | User-uploaded ASL alphabet images (no formal license published) | Landmark CSVs extracted once with the reference MediaPipe hand graph; the CSV lineage, extraction commands and J/Z exclusion are recorded in `docs/sign/asl-training-report.md` |
| AASL | Arabic alphabet RGB images, collected in Saudi Arabia | Training images only; license ambiguity below |
| ArSL2018 ([DOI 10.17632/y7pckrw6z2.1](https://doi.org/10.17632/y7pckrw6z2.1)) | Static Arabic alphabet images, Al Khobar, CC BY 4.0 | Only "survivors" are used — frames where the hand pipeline detects a hand and yields 63 valid landmark floats; survivor counts and drops are in `docs/sign/arsl-training-report.md` |

- AASL license ambiguity: the source page lists CC BY-SA while the dataset's
  own documentation indicates CC BY-NC-SA. The stricter BY-NC-SA terms apply
  here: training use only, no redistribution of images, CSVs or derived test
  data. Kaggle's set is treated as all-rights-reserved for the same reason.
- Both Arabic datasets were collected in Saudi Arabia. The honest label is
  "Arabic fingerspelling"; results may not generalize to signers from other
  regions, and this is not Saudi sentence-level sign language.

## Normalization contract

Classifier input is 63 float32 values: 21 MediaPipe landmarks × (x, y, z).

1. **Wrist origin** — subtract landmark 0 (wrist) from all points.
2. **Palm scale** — divide by the wrist-to-middle-MCP (landmark 9) distance.

The exact reference implementation is `scripts/sign/hearth_ml.py`; the native
side mirrors it in the sign classifier preprocessing. Training and on-device
normalization must stay bit-comparable — a silent mismatch degrades accuracy
without failing any load check, which is why `scripts/sign/verify_onnx.py`
gates artifacts against the frozen contract and the fidelity harness pins the
landmark pipeline itself.

## Threshold-gated metrics

The classifier emits a softmax distribution per frame; predictions below the
confidence threshold τ are rejected (no letter shown). All reported metrics are
defined on a held-out set with a fixed, stated τ:

- **Coverage** — fraction of samples accepted (not rejected) at τ.
- **Accuracy-among-accepted** — correct predictions divided by accepted
  predictions.
- **ECE** — expected calibration error over the accepted predictions, binned
  by confidence (the binning is recorded with the result).

Rules for quoting them:

- Never quote accuracy-among-accepted without coverage. Raising τ buys accuracy
  by rejecting more inputs; a naked accuracy number is meaningless.
- Report ECE alongside, so confidence can be judged as a probability rather
  than a score.
- The 3-frame consensus in the app further suppresses flicker; dataset metrics
  are per-frame and do not include it. Do not present dataset metrics as
  measured phone accuracy.

## Honesty rules

- **A random split is not signer-independent.** Random splits place the same
  signer in both train and test sets, so scores partly measure signer
  memorization. Only signer-independent splits (held-out signers) are evidence
  for how the model treats a new user. Every REPORT must state which split its
  numbers use, and signer-independent results are expected to be lower — that
  is the honest number.
- Dataset-level numbers come from curated images: clean backgrounds, deliberate
  poses, fixed framing. Phone reality adds motion blur, lighting, distance and
  handedness variation.
- No number is quoted that cannot be reproduced from the commands recorded in
  the relevant REPORT file. If a REPORT has not been written yet, there is no
  number to cite — do not invent one.
- Both Arabic datasets are Saudi-collected; keep the "Arabic fingerspelling"
  label and the regional-bias caveat attached to every Arabic result.

## Where the records live

- `docs/sign/asl-training-report.md` — ASL training record: CSV lineage, splits,
  per-alphabet metrics, τ.
- `docs/sign/arsl-training-report.md` — Arabic training record: AASL/ArSL2018
  lineage, survivor counts, splits, metrics, τ.
- `docs/sign/hand-models-provenance.md` / `PROVENANCE.json` — hand-model
  conversion provenance and hashes (checked by `scripts/verify-release.py`
  when the converted files are present).
- `docs/sign/fidelity-report.md` — landmark-pipeline fidelity against the
  upstream MediaPipe graph; `fidelity/golden.json` is the tracked regression
  fixture (weights and caches under `research/sign/` are git-ignored).

These files may still be in progress while the pipeline lands; reference them
by path rather than restating their contents.
