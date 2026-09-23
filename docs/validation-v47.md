# 0.22.5 QA validation

Validated 2026-09-23 on branch `codex/model-phase`.

- Native `HearthSpeechBackend` adds an experimental Omnilingual CTC 300M v2
  INT8 adapter without changing its versioned C ABI. The same C++ backend
  decoded English, Arabic, Russian and Chinese host clips on macOS and built
  as an arm64 Android runtime with 16 KiB alignment.
- The Samsung SM-S908E (Android 16) installed the pinned archive through the
  Play app and ran all four built-in speech quick sets. See
  [per-language results](native-speech-omnilingual-2026-09-23.md). These are
  short replay tests, not sustained live caption delay or bilingual adequacy.
- Optional Russian→English→Arabic and Chinese→English→Arabic Marian ONNX routes
  ran in the S22 translation benchmark. Warm six-sentence passes were 4.20 s
  and 3.78 s. Their automatic chrF++ scores were 35.17% and 39.02%; neither
  route is the default.
- The benchmark now keeps WER/CER for speech only and reports CER only for
  Chinese speech, because FLEURS Chinese references insert spaces between
  characters. Results remain saved locally and export through the document
  picker. Old exported results keep their original fields.
- The [independent NDK/audio review prompt](ndk-audio-review-prompt.md) covers
  both this standalone repository and the original FFmpeg media-suite repo.

Gate: `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:common-jni:externalNativeBuildDebug :app:assemblePlayQa :app:assembleFossQa`,
native speech host suite (53 checks), package tests (6),
`python3 scripts/verify-release.py`, and Gitleaks history scan. The final
Gradle/artifact verification results are recorded by the release run; no paid
Soniox/Scribe requests were made. S25 and live 30-minute memory/thermal tests
remain unverified.
