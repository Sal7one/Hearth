# 0.12.0 — Japanese OCR and drawn regions

Date: 19 September 2026. Version code 25. Test distribution files use v64 numbering.

Implemented: native Manga and Meiki adapters alongside Paddle, grouped OCR cards,
complete-package downloads/imports, pinned model/vocabulary sources, drawn-region
selection on captured/imported images, and retry of unchanged captured text.
See [adapter contracts and limitations](manga-ocr-adapters.md).

## Gates

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :common-jni:externalNativeBuildDebug :app:assemblePlayQa :app:assembleFossQa` — PASS.
- App unit tests: 201 per variant, across six variants; zero failures/errors.
- Common JNI unit tests: 74 per variant, across two variants; zero failures/errors.
- `bash common-jni/src/main/cpp/ocr/tests/run_ocr_tests.sh` — 14 checks PASS with ASan/UBSan.
- `bash common-jni/src/main/cpp/speech/tests/run_speech_tests.sh` — 50 checks PASS.
- `bash common-jni/src/main/cpp/common/tests/run_common_utils_tests.sh` — all five suites PASS.
- `python3 scripts/ocr/verify-manga-vocabulary.py` — publisher hash, packaged hash and 6,144-token order PASS.
- Every downloaded specialist weight's actual SHA-256/size matches OcrCatalog.
- `python3 scripts/verify-release.py` — both QA APKs PASS; expected native libraries,
  16 KiB alignment, asset provenance and flavor permissions verified.
- `git diff --check` — PASS.

Real C++ host inference with ORT 1.20.1/macOS ARM64 read a synthetic Japanese
horizontal sentence correctly through both adapters. Manga read the vertical
sentence completely; Meiki omitted its final punctuation. Meiki returned no text
for the blank fixture. Post-inference cancellation rejected subsequent inference.
Manga horizontal and Meiki vertical native smoke runs also completed under
ASan/UBSan without diagnostics. Fixtures/models/harness are in ignored research
storage; these are integration checks, not representative quality benchmarks.

The signed INT8 Manga encoder failed with an unsupported ConvInteger kernel;
the tested unsigned encoder is pinned instead. The decoder preserves the model's
extra start token in context and removes special tokens from displayed text.

## APKs

Both are release-optimized, debug-signed QA builds for owner testing, not production-signed releases.

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `hearth-v64-manga-cloud.apk` | 35,058,915 | `f7862cd292d873324dd4e2a81565ba4793e00d02e08f8f68f56d28c4a9ca877b` |
| `hearth-v64-manga-offline.apk` | 27,275,205 | `3e7bd628786ceb4940937fe3b1b1daf06c3cfacb6292ca2d410446b9b5df562d` |

LAN endpoints returned HTTP 200 with matching content lengths after packaging.
The first verifier invocation happened before Foss packaging completed and read
the previous Foss APK; verification was rerun successfully after both builds finished.

## Owner checks still required

1. Camera → Settings → Manga → Download & install. Verify both files install and
   remain in the chosen public download folder. Select Japanese and a configured
   translation target, import a manga page, draw around one bubble, and tap Read area.
2. Repeat with Meiki on horizontal and vertical Japanese text; inspect punctuation
   and column order rather than assuming complete manga layout support.
3. Select Paddle and use Draw text area on an Arabic/Russian/Latin captured image.
   Check that the correct language profile and existing translation/TTS are reused.
4. Check long-press/drag selection with letterboxing, Reset/Cancel/Whole image,
   large fonts, TalkBack alternatives, Stop during model loading and inference,
   translator failure/Retry, and navigation while a model is releasing.
5. Offline build: import all required files, confirm no network/download delegation,
   and use an already installed local translator.

No phone installation or device inference was performed in this session. Android
still uses its pinned ORT 1.20.0, so host inference does not establish handset
compatibility or sustained performance. Cross-app screen capture, automatic
scroll/page triggers and volume shortcuts remain separate planned work.
