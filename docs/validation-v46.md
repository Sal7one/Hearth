# Model library and benchmark validation · 2026-09-23

Reviewed the uncommitted model/downloader/benchmark/native wave against
`docs/BACKLOG.md`, `docs/utils-bible.md`, and the public model documentation.
Removed an unconnected OPUS-MT catalog and unused benchmark ZIP importer rather
than advertising or retaining paths with no production consumer. Corrected
aggregate benchmark timing labels, pinned Whisper matching, retry after failed
SHA-256, and download pause/progress state handling.

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin assemblePlayQa assembleFossQa` — PASS.
- `bash common-jni/src/main/cpp/speech/tests/run_speech_tests.sh` — 50 checks PASS.
- `bash common-jni/src/main/cpp/common/tests/run_common_utils_tests.sh` — six suites PASS.
- `python3 -m unittest discover -s scripts/benchmark -p 'test_*.py'` — four tests PASS.
- `./gradlew :common-jni:externalNativeBuildDebug` — PASS.
- Rebuilt and staged the pinned Android translation runtime after native source
  edits; `python3 scripts/verify-release.py` — Play/FOSS QA contents, permissions,
  native hashes, symbols and 16 KiB alignment PASS.
- Physical Samsung SM-S908E / Android 16: final Play QA APK installed and launched.
  A pinned Whisper Tiny Q5_1 artifact downloaded over unmetered Wi-Fi, verified,
  installed, selected, and benchmarked; the original stayed in
  `Downloads/Hearth/models`. The prior Cloud BYOK speech selection was restored.
  Forced-Russian Qwen3-ASR and Nemotron also completed their quick sets with
  separate accuracy and timing results. See
  [phone results](benchmark-phone-smoke-2026-09-23.md).

Still unverified: pause/resume after actual network interruption, a fresh
long-session Nemotron/Qwen run, real-time ASR plus translation under load, and
a multi-language human translation quality review. The six-sentence
scores are a smoke comparison, not a release-wide accuracy guarantee.
