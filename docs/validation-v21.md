# Hearth 0.9.0 / code 21 — camera OCR validation

Date: 2026-09-13. Parent repository inspected read-only; its working tree remained
unchanged. This is a functional integration check, not a broad OCR/translation benchmark.

## Automated gates

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin`: PASS.
  App: 179 tests per flavor/build type, one intentional opposite-flavor skip;
  common-jni: 67 tests per debug/release. Zero failures/errors in the passing run.
- Native Android debug build: PASS. Common host suite: all five programs PASS;
  speech: 50 checks; Vosk API loader suite: PASS; OCR geometry/CTC: 10 checks
  with AddressSanitizer/UndefinedBehaviorSanitizer; speech packaging: 5 tests.
- Both QA and both unsigned release APKs build. Artifact verification checks each
  flavor's permissions, native dependency closure, 16 KB alignment, unchanged
  vendor hashes and packaged OCR dictionary identities/provenance/notices.
- All four dictionary assets reproduced byte-for-byte from pinned PaddlePaddle
  publisher configurations using `scripts/ocr/verify-dictionaries.py`.
- New host regressions cover live text settling/reset, immediate capture, Arabic
  logical order and number-run spacing, model identities/languages, rejected import
  preserving installed data, and cancelled import staging cleanup.

The first added Arabic spacing assertion failed, exposing boundary spaces attached
to a Latin/number run. The decoder now separates boundary spaces before reversing
Arabic groups; the assertion passes. Real model tests also caught and fixed reuse
of recognition-output dimensions while mapping later detection boxes into pixels.

## Actual model and phone checks

Phone: Samsung SM-S908E (S22 Ultra), Android 16 / API 36, arm64. The tests use the
app's packaged ONNX Runtime 1.20.0 CPU path with two inference threads. Existing
caption, translation models and credentials were retained during APK upgrades.

| Reader | Two-line fixture result on Android | Displayed OCR time |
| --- | --- | --- |
| Latin | `Welcome to Hearth` / `Camera translation 123`, exact | 195 ms |
| Arabic | `مرحبا بكم` / `ترجمة الكاميرا`; trailing `123` omitted by reader | 211 ms |
| Chinese | `欢迎使用` / `相机翻译测试 123`, exact | 210 ms |
| East Slavic | `Добро пожаловать` / `Перевод камеры 123`, exact | 194 ms |

These are one observed inference per synthetic fixture, excluding initialization
and translation latency. Arabic and Chinese fixtures were rendered with CoreText
so shaping/glyphs were valid. Host runs used ONNX Runtime 1.30.0 as an additional
check; they were not used to infer Android compatibility. Actual phone runs above
establish that these pinned exports load with the shipped Android runtime.

- One **Download & install** action fetched the shared detector and Latin reader
  from the catalog, installed verified copies, and kept originals in
  `Downloads/Hearth/models`. Transfer setup was slow on this connection; no download
  speed claim is made. Setup now exposes active progress instead of an inert-looking
  button. Other readers were transferred as public ONNX files and imported through
  the app's file picker; all three became Installed after verification.
- Photo import worked before camera permission was granted. All four readers ran
  successfully; the play build produced on-device ML Kit Arabic/English translations.
  The lightweight translator paraphrased/merged fixture lines; these results do not
  establish specialist translation quality.
- Re-importing while a photo session was active worked after file-picker lifecycle
  stop/restart. Stop, Retake and changing model groups retained valid ownership.
- Camera permission was requested only after Open camera. Live preview/analyzer,
  capture while live, Retake, restart and leaving the page worked without a crash.
  The available camera scene had no readable text: live analysis showed about 108 ms,
  capture about 230 ms. This checks the capture/lifecycle path, not real sign quality.
- The foss QA APK upgraded in place, showed no cloud/download actions, and recognized
  the Russian fixture in original-text mode (194 ms). ML Kit is excluded from foss;
  switching from play may require choosing a different local translator or disabling
  translation. Settings explicitly explains this state.

The final play QA artifact was reinstalled after the foss check. Russian photo
recognition and local English translation were repeated successfully (211 ms OCR).

## Publication checks

- Gitleaks scanned all existing Git refs and the source-only staged snapshot with
  zero findings. No ignored files, model weights, APKs, credentials or SDK-local
  configuration are tracked. OCR dictionaries are intentionally packaged assets.
- Both QA and unsigned release flavors pass the final artifact verifier. The
  original media-suite working tree remains unchanged. No remote push is performed.

## Limits before promotion

- Test real signs/menus, tilted surfaces, varying illumination, long sessions,
  thermal behavior and additional devices. This release uses horizontal boxes,
  not perspective rectification or translated-text painting over camera frames.
- Arabic number omission is a confirmed model/recognition limitation on one fixture.
  Do not infer translation correctness when source OCR omitted a price or number.
- Paid cloud camera translation has not been checked with provider accounts in this
  wave. It reuses existing tested text transport/protocols, with explicit provider
  choice and source/target validation. No image-upload endpoint was introduced.
- TalkBack descriptions, selectable originals, copy buttons, error announcements,
  large-text wrapping and theme colors are implemented. A full human accessibility
  pass is still needed, especially under continuously changing captions.
- QA artifacts are debug-signed. Production artifacts remain unsigned; this work
  does not publish a GitHub repository or a store release.
