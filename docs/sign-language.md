# Sign-language fingerspelling

The sign overlay spells letters from your camera in real time. Each video frame
runs through a native hand-landmark pipeline (palm detection, then 21 3D hand
landmarks), a per-frame letter classifier scores the normalized landmarks, and a
3-frame consensus rule appends a letter only after the same letter wins three
consecutive classified frames. Recognized letters accumulate as ordinary text in
a floating overlay, with a recognition history, pause/resume and clear. The
accumulated text is normal app text: copy it, translate it with the installed
local or cloud translator, or read it aloud through the shared voices, exactly
like typed text.

This is an owner-approved scope expansion, like camera OCR and typed-translation
TTS. It is deliberately built as fingerspelling first, with honest limits
documented below — not as a claim of general sign-language understanding.

## What it can and cannot do

- ASL: the 24 static alphabet letters. **J and Z are excluded** because they are
  motion letters that a per-frame static classifier cannot see.
- Arabic: 28 letters, trained on the AASL and ArSL2018 datasets. Both were
  collected in Saudi Arabia, so the honest label is **Arabic fingerspelling** —
  not Saudi sentence-level sign language, and not a claim about every Arabic
  signer.
- This is **not continuous or sentence-level sign language**. Words that are
  normally signed rather than fingerspelled, transitions between letters, and
  full sentences are out of scope for this phase. See the Phase-2 roadmap below.
- One signer in frame, hand(s) visible and roughly facing the camera. Poor
  lighting, motion blur, partial occlusion and extreme angles reduce landmark
  quality and classification confidence. Low-confidence frames are rejected
  rather than guessed; expect some correct signs to be skipped (see
  [training and validation](sign-training-and-validation.md) for the
  coverage/accuracy trade-off).
- Everything runs on the phone. No frame, landmark or letter leaves the device.

## Use

1. Open Models → Sign and choose an alphabet. In play, **Download & install**
   fetches the catalogued files with verified sizes and SHA-256 hashes, with
   progress, cancellation and retries in Downloads; originals stay in
   `Downloads/Hearth/models`. In foss, import the same files instead. This is
   the same verified install path as the speech models. No models are bundled
   with the app or repository.
2. Each alphabet needs four files: the two hand models (palm detector and hand
   landmarks), the classifier ONNX, and its labels JSON.
3. Open the sign overlay, grant camera permission, and hold your hand in frame.
   A letter appears after it wins the 3-frame consensus; keep the hand still
   and change letters deliberately. **Pause** suspends recognition without
   releasing the camera, **Clear** wipes the accumulated text, and history
   shows what was spelled so far.
4. Leaving the overlay stops inference and releases the models, following the
   same lifecycle as the camera OCR path.

## Model sources and the BYO-models contract

### Hand models (converted MediaPipe weights)

The hand stage uses Google MediaPipe hand landmarker models, converted to ONNX
with tflite2onnx. Exact pinned source artifacts, tensor names, per-model shapes,
conversion commands and SHA-256 hashes are recorded in
`docs/sign/hand-models-provenance.json` (human-readable twin:
`PROVENANCE.md`) — that file is the authoritative contract, and
`scripts/verify-release.py` re-validates hashes whenever the converted files are
present. The short version:

| Component | Input | Output |
| --- | --- | --- |
| Palm detector | `[1,192,192,3]` float32 RGB frame | Palm scores and box regressions over 2016 SSD anchors (4 layers, strides 8/16/16/16) plus 7 palm keypoints |
| Hand landmarks | `[1,224,224,3]` float32 rotated palm crop (2.6× palm size, wrist→keypoint axis vertical, per MediaPipe `hand_landmark_cpu.pbtxt`) | 21 normalized landmarks (x, y in image space, z crop-relative) with a hand score |

Preprocessing numerics — input value range, channel order and z scaling — are
pinned against the real upstream graph by the fidelity harness in
`research/sign/fidelity/` (results in its `REPORT.md`; `golden.json` is the
tracked regression fixture). Do not change preprocessing constants without
re-running it.

### Classifier models (bring your own)

The classifier contract is frozen; `scripts/sign/hearth_ml.py` is the source of
truth for training/export and `scripts/sign/verify_onnx.py` gates every
artifact, catalogued or imported, against it:

- Input tensor **`landmarks`**: `[batch, 63]` float32 — 21 MediaPipe landmarks
  as x/y/z, wrist-origin and palm-scale normalized (see the normalization
  contract in [training and validation](sign-training-and-validation.md)).
- Output tensor **`logits`**: `[batch, N]` float32 raw logits. Softmax is
  applied on device; do not bake it into the graph.
- ONNX opset **13 or lower**, rank-2 tensors only, **at most 128 classes** and
  **at most 2 MB** per file.
- A labels JSON maps class indices to displayed letters and ships next to the
  model, not inside it.

Arbitrary ONNX networks that do not match the contract are rejected at import,
not silently run. Matching files are installed and verified exactly like
catalog downloads.

## No Google runtimes, on purpose

The sign path deliberately avoids LiteRT, TFLite and ML Kit. The MediaPipe hand
models are converted to ONNX once, and inference runs on the same vendored ONNX
Runtime as Marian translation and the Paddle/Manga OCR readers — one runtime to
harden, audit and verify hashes for, and no Google runtime libraries in the
foss flavor. Converting weights does not change their license: the hand models
remain © 2023 The MediaPipe Authors, Apache-2.0, and tflite2onnx (Apache-2.0)
is only used as a build-time tool. See [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md)
for the full attribution, including the training-only dataset terms.

## Implementation notes

- Native pipeline: `common-jni/src/main/cpp/sign/` — `hand_geometry.cpp`
  (pure-C++ geometry, no OpenCV), `hand_landmarks.cpp` (SSD anchor decode +
  landmark crop), `sign_classifier.cpp` (ONNX Runtime session, softmax,
  bounded tensors) and `sign_jni.cpp` (JNI surface, `com.sal7one.common_jni`
  ownership rules apply).
- Kotlin bindings: `common-jni/src/main/java/com/sal7one/common_jni/sign/`
  (`SignNative`, `SignPipeline`); the app-side overlay, catalog and import UI
  live in the app sign package, with the download catalog in
  `app/.../models/SignDownloads.kt`.
- Host tests: `common-jni/src/main/cpp/sign/tests/run_sign_tests.sh`
  (address/UB sanitizers) runs in CI next to the other native suites; Python
  tooling tests under `scripts/sign/` run with unittest.
- The camera feed follows the camera-OCR pattern: latest-frame analysis only,
  bounded native work, stop releases the models, and leaving the app ends
  inference.

## Phase 2 — continuous sign language

Fingerspelling is per-frame: a static classifier scores one pose at a time.
Continuous or sentence-level recognition is a different problem — it needs
temporal sequence models (CTC or attention/transformer decoders over landmark
or frame sequences), segmentation of continuous signing, signer-independent
validation at a much larger scale, and corpora that actually contain sentences.
That is why it is Phase 2, not a label on Phase-1 data. The Arabic/Saudi
dataset landscape we are tracking:

| Dataset | Contents | Fit |
| --- | --- | --- |
| [Isharah](https://github.com/snalyami/Isharah_CSLR) | 30k+ continuous videos with word-level glosses and Arabic sentences | Most direct Phase-2 path: continuous word/sentence recognition |
| [KAU-CSSL](https://arxiv.org/abs/2509.03467) | Continuous Saudi sign language, medical domain | Domain-specific continuous corpus (hospital vocabulary) |
| KArSL | 75,300 Kinect RGB-D videos, 502 words | Representation mismatch for a static phone alphabet (RGB-D capture); candidate for temporal-model research |
| KSU-ArSL (IEEE DataPort) | 16,000 RGB videos, 80 words | Word-level vocabulary; Phase-2 candidate once sequence models land |
| [ArSL2018](https://doi.org/10.17632/y7pckrw6z2.1) (Al Khobar) | Static alphabet images | Already used here for Phase-1 Arabic fingerspelling |

Phase-2 work would reuse this phase's hand-landmark pipeline and normalization
contract unchanged; what changes is the model above them.

For how the classifiers were trained, split and scored — and how to read the
numbers honestly — see [training and validation](sign-training-and-validation.md).
Training records live under `research/sign/` (`asl/REPORT.md`, `arsl/REPORT.md`,
`handmodels/PROVENANCE.md`, `fidelity/REPORT.md`); those files are the
authoritative record for dataset lineage and metrics.
