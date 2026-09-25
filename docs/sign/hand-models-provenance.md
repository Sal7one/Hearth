# Hand model ONNX conversion provenance

Generated (UTC): 2026-09-25T20:48:02+00:00

## Source

- Bundle: `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`
- Local `.task` SHA-256: `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1` (7,819,105 bytes)
- A fresh download of the URL above hashed identical to the local `.task` at conversion time.
- hand_landmarker.task is a ZIP archive containing hand_detector.tflite and hand_landmarks_detector.tflite (verified byte-identical to the copies in the legacy repo's app/src/main/assets/hand_models/).

## Conversion

- Tool: tf2onnx (TFLite frontend) via tf2onnx.convert.from_tflite 1.17.0 (fallback rationale: tflite2onnx 0.4.1 fails on these graphs (rank-4 NHWC->NCHW permutation applied to rank-3 PReLU tensors in the detector head); tf2onnx's TFLite frontend is used instead.)
- ONNX package 1.23.0, onnxruntime 1.20.0, Python 3.12.14 on macOS-26.3.1-arm64-arm-64bit
- Opset 13, dtype float32 (FP16-quantized TFLite weights dequantized to float32 initializers)
- Reproducibility: The converted graph is canonicalized (structure-derived merkle names, deterministic topological ordering); with the same .task, tool versions and Python the serialized ONNX bytes and sha256 are identical across runs.

## License

- Apache-2.0 — Copyright 2023 The MediaPipe Authors
- Converted weights retain the upstream Apache-2.0 license and attribution.

## hand_detector.onnx

- Source: `hand_detector.tflite` (sha256 `945f713bc23570bd4ed60f848c401dc8eaf95713183d43ba14cf12e467d27a7d`)
- ONNX sha256 `0923eb04fef6c9cc4c8a3094c990d215341aebb2140eaf022c8cac0370681415`, 4,589,016 bytes, IR 7, opsets {'ai.onnx': 13, 'ai.onnx.ml': 2}
- Inputs:
  - `input_1` FLOAT [1, 192, 192, 3]
- Outputs (order as returned by onnxruntime):
  - `Identity` FLOAT [1, 2016, 18] (smoke min=-3535.238770 max=3943.585938)
  - `Identity_1` FLOAT [1, 2016, 1] (smoke min=-3194.289307 max=178.174515)
- TFLite cross-check (same seeded input, ai-edge-litert 2.2.0):
  - `Identity`: max|onnx-tflite| = 7.812e-03 (rel 1.981e-06)
  - `Identity_1`: max|onnx-tflite| = 3.906e-03 (rel 1.223e-06)
- Source TFLite weights are FP16-quantized; ONNX dequantizes them to float32. Absolute diffs on the detector grow with logit magnitude (out-of-distribution noise input); relative diffs stay at float32 rounding level (~1e-6).

## hand_landmarks_detector.onnx

- Source: `hand_landmarks_detector.tflite` (sha256 `6acda74af3fbf40e68265c20c7394b2bad81a16a481dcd79ad7a081887c3d6b9`)
- ONNX sha256 `4e81514a9141f52e1fb3b387a242c7dc33cb2f2d26c996a28c3d42bf8d012040`, 10,902,245 bytes, IR 7, opsets {'ai.onnx': 13, 'ai.onnx.ml': 2}
- Inputs:
  - `input_1` FLOAT [1, 224, 224, 3]
- Outputs (order as returned by onnxruntime):
  - `Identity` FLOAT [1, 63] (smoke min=-11.778067 max=183.000946)
  - `Identity_1` FLOAT [1, 1] (smoke min=0.001718 max=0.001718)
  - `Identity_2` FLOAT [1, 1] (smoke min=0.618474 max=0.618474)
  - `Identity_3` FLOAT [1, 63] (smoke min=-0.088317 max=0.076644)
- TFLite cross-check (same seeded input, ai-edge-litert 2.2.0):
  - `Identity`: max|onnx-tflite| = 9.155e-05 (rel 5.003e-07)
  - `Identity_1`: max|onnx-tflite| = 4.971e-08 (rel 4.971e-08)
  - `Identity_2`: max|onnx-tflite| = 4.172e-07 (rel 4.172e-07)
  - `Identity_3`: max|onnx-tflite| = 2.235e-07 (rel 2.235e-07)
- Source TFLite weights are FP16-quantized; ONNX dequantizes them to float32. Absolute diffs on the detector grow with logit magnitude (out-of-distribution noise input); relative diffs stay at float32 rounding level (~1e-6).

