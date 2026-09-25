# MediaPipe hand models → ONNX conversion

Converts Google MediaPipe `hand_landmarker.task` (BlazePalm detector + hand
landmark regressor, Apache-2.0 weights) into float32 ONNX models that run on
the app's vendored ONNX Runtime 1.20 — no LiteRT/TFLite runtime needed. The
same script works for any MediaPipe `.task` bundle that contains
`hand_detector.tflite` and `hand_landmarks_detector.tflite`, so users can
convert bundles from other MediaPipe releases the same way. Importing an
arbitrary ONNX hand model with this input/output contract stays possible.

## Usage

A virtualenv is provided in `scripts/sign/convert/.venv` (Python 3.12 —
onnxruntime 1.20.0 has no wheels for newer Pythons):

```sh
.venv/bin/python convert_hand_models.py \
    --task /path/to/hand_landmarker.task \
    --out  /path/to/output_dir
```

Options: `--opset` (default 13), `--seed` for the numeric smoke input
(default 0).

The script:

1. hashes the `.task`, unzips it and hashes both embedded `.tflite` files;
2. converts each to float32 ONNX (FP16-quantized TFLite weights are
   dequantized to float32 initializers);
3. verifies each graph with `onnx.checker` and runs it under onnxruntime
   with a seeded standard-normal input (asserts all outputs are finite and
   records min/max per output);
4. cross-checks against the original TFLite through LiteRT
   (`ai-edge-litert`), reporting max |onnx − tflite| per output tensor;
5. writes `hand_detector.onnx`, `hand_landmarks_detector.onnx`,
   `PROVENANCE.json` and `PROVENANCE.md` into `--out`.

Current outputs live in `research/sign/handmodels/` — see
`PROVENANCE.{json,md}` there for hashes, tensor signatures and numeric
results.

## Model signatures (onnxruntime output order)

`hand_detector.onnx` — input `input_1` float32 `[1,192,192,3]` (NHWC, ~`[0,1]`
normalized camera frames, MediaPipe convention):

| order | name        | shape          | meaning                        |
| ----- | ----------- | -------------- | ------------------------------ |
| 0     | `Identity`  | `[1,2016,18]`  | boxes: 4 coords + 14 kp × xy   |
| 1     | `Identity_1`| `[1,2016,1]`   | palm presence logits           |

`hand_landmarks_detector.onnx` — input `input_1` float32 `[1,224,224,3]`
(cropped rotated hand ROI):

| order | name         | shape     | meaning                                    |
| ----- | ------------ | --------- | ------------------------------------------ |
| 0     | `Identity`   | `[1,63]`  | 21 screen-space landmarks (x,y,z × 21)     |
| 1     | `Identity_1` | `[1,1]`   | hand flag (sigmoid → P(hand))              |
| 2     | `Identity_2` | `[1,1]`   | handedness (sigmoid → P(right/first class))|
| 3     | `Identity_3` | `[1,63]`  | 21 world landmarks (meters, origin at hand center) |

## Toolchain

- **Converter:** `tf2onnx` 1.17.0 (TFLite frontend; needs `tensorflow` 2.x
  installed for its graph utilities — ~600 MB, lives only in this venv).
  `tflite2onnx` 0.4.1 was tried first and crashes on these graphs: its
  NHWC→NCHW layout propagation applies a rank-4 permutation to the rank-3
  `[1,1,256]` PReLU tensors of the detector head, and it warns the
  FP16-quantized tensors are unsupported. tf2onnx handles both models cleanly.
- **Verify:** `onnx` 1.23.0, `onnxruntime` 1.20.0 (matches the vendored
  runtime), `numpy`, and `ai-edge-litert` 2.2.0 for the TFLite reference run
  (optional — the script still runs without it, skipping only the
  cross-check).

### Reproducibility

tf2onnx numbers its generated names from run-dependent iteration order, so
raw converter output is not byte-stable. The script re-serializes each graph
in a canonical, structure-derived form (merkle fingerprints over
initializer bytes, op types, attributes and input fingerprints; deterministic
topological ordering), making the ONNX bytes and sha256 identical across runs
for the same `.task`, tool versions and Python. External tensor names
(`input_1`, `Identity`, `Identity_1`, ...) are preserved exactly.

Observed numeric fidelity vs the original TFLite on a seeded noise input:
landmark model max abs diff ≤ 9.2e-5 (relative ~5e-7); detector max abs diff
7.8e-3 on logits of magnitude ~3.9e3 (relative ~2e-6) — the source weights
are FP16-quantized, and relative error stays at float32 rounding level.

## License

The MediaPipe hand models are licensed Apache-2.0, © Copyright 2023 The
MediaPipe Authors (see the model card at
<https://developers.google.com/mediapipe/solutions/vision/hand_landmarker>).
Converted weights carry the same upstream license and attribution; this
conversion script and its canonicalization code are part of this repository.
