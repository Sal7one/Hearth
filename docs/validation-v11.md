# 0.5.0 / versionCode 11 — translation pipeline and model choices

Date: 2026-09-12. Standalone repository, following b2a9c8f.

## Changes that reach the user

- Nemotron requests a decoder endpoint after four seconds of decoded speech with
  text, retaining loaded weights. Continuous speech now produces bounded final
  segments for the existing final-only translator instead of waiting indefinitely.
- Translation prepares before the first final. Weight-loading time is excluded
  from queued-caption expiry; preparing/ready/translating states are visible.
  The bounded queue, age limit, source validation and generation checks remain.
- Existing HY-MT1.5 and Hy-MT2 Q4/Q6/Q8 remain supported. A separate 2-bit SEQ file
  failed with the pinned runtime and is not offered as a working download.
- ML Kit translation: optional play-only SDK, explicit Wi-Fi language-pack
  download/remove, local inference, missing-pack errors, source/target coverage and
  Google privacy/attribution text. No SDK or native translation pack library in foss.
- TranslateGemma 4B Q4_K_M: pinned artifact, explicit gemma3 architecture, separate
  family prompt/turn tokens and initial 35-code adapter coverage. Larger optional
  translator, not the smaller/faster default.
- Soniox stt-rt-v5: encrypted BYOK, original/translation runs with independent
  revisions/finality, source hints and integrated one-way translation.
- ElevenLabs scribe_v2_realtime: encrypted BYOK, VAD commits and partial captions.
  Its local translation bridge requires an explicit spoken-language selection.
- Moonshine v2 Tiny/Base English: existing sherpa library, verified ZIP schema,
  fixed-English picker and utterance-windowed decoding. No universal ONNX claim.
- Model chips wrap onto another row; selecting ML Kit collapses GGUF controls.
  Cloud translation target choices no longer inherit a remembered local translator.

## Automated checks

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
  :app:assemblePlayQa :app:assembleFossQa`: PASS. Final run 268 tasks,
  39 executed / 229 up-to-date. Test XML totals: app 762 executions across six
  build variants (127 tests each); common-jni 126 executions across two variants
  (63 each), no failures/errors/skips. These are not 888 distinct tests.
- Speech native host suite: 50 checks PASS. Common native utilities: ALL PASS.
- `:common-jni:externalNativeBuildDebug`: PASS in the native verification run.
- Python speech-package suite: five tests PASS, including missing Moonshine assets.
- Rebuilt/staged Nemotron, Qwen/Moonshine and translation Android libraries with
  pinned provenance and SHA-256 records. Translation native host build also PASS.
- `scripts/verify-release.py`: both APKs PASS runtime hashes/dependencies, arm64
  inventory, 16 KB load alignment and permissions. Play alone contains
  `libtranslate_jni.so`; foss has no Internet permission.
- Focused regressions: translator preparation/queue age, forced Russian routing,
  direct cloud translation precedence, fixed-English source, Soniox interim/final
  grouping, Scribe timestamp deduplication/raw errors and package required roles.

## Actual inference and UI checks

Authorized phone: Samsung SM-S908E, Android 16, arm64, QA package. This differs from
an earlier SM-S938B crash report. Tests used the app’s normal import/settings UI,
MediaProjection device audio and a browser playing short controlled test audio.

1. Existing HY-MT1.5 Q4 + Nemotron, explicitly Russian → Arabic: Russian final
   segments arrived during continuous speech and Arabic output appeared in the
   overlay. This directly confirms the older HY artifact still works on this path.
2. Moonshine Tiny: imported the 44,442,781-byte prepared ZIP through Models,
   selected fixed English, started overlay capture and correctly transcribed the
   publisher’s short “Ask not what your country can do for you…” sample. No native
   recognizer startup crash. The three model chips wrap normally in the rebuilt UI.
3. ML Kit: explicitly downloaded Russian and Arabic packs on Wi-Fi; both appeared
   as installed. Nemotron forced Russian → Arabic produced overlay translation,
   including “الطقس الجيد أريد العودة إلى المنزل”. The SDK’s English pivot was
   observable. This was a short functional check, not an accuracy/latency benchmark.
4. TranslateGemma: pinned Q4 artifact loaded in the host native adapter and returned
   “الطقس جميل اليوم. أريد العودة إلى المنزل.” for the Russian test sentence.
   Phone joint Nemotron+TranslateGemma inference was not tested.
5. The separate HY 2-bit artifact failed GGUF tensor-offset validation in the pinned
   host runtime. Publisher metadata specifies SEQ and a forthcoming llama.cpp kernel.
   Existing HY Q4/Q6/Q8 files were not removed or reclassified as incompatible.

## Explicit limits and follow-up

Soniox and Scribe are protocol/compile-tested, not tested with live provider accounts.
Moonshine Base is an accepted compatible profile, not device-verified here. Only
Tiny English and the short Russian → Arabic checks above were exercised; no claim
of all-pair accuracy, sustained thermal performance, sub-second end-to-end latency
or offline-after-download device testing is made. ML Kit is a smaller option with
casual-translation quality limits. Gemma 4/LiteRT-LM, Gemini transcription and
alternative Nemotron sherpa/QNN runtimes remain unimplemented optional tasks.

The contribution guide records working ASR, translator and cloud extension routes.
No model weights, keys, captured audio or generated build caches are committed.
QA APKs are debug-signed test artifacts, not production store signing.

Artifacts: `real-time-transiber-v11-cloud.apk` and
`real-time-transiber-v11-offline.apk` in the owner’s `/tmp/apk-serve` folder.
