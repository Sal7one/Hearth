# Hearth

An Android app for live captions over other apps, local speech recognition, and
bring-your-own-key cloud transcription and translation. Extracted from Hearth's
caption system into a separate, focused project.

## Development backlog

See the [model contribution guide](docs/model-contributing.md) and [release checklist](docs/BACKLOG.md) for current release tasks, native integration conventions and model contributions.

## Features

- Movable, resizable caption bubble with retained text, prior lines, selectable
  history, pause/resume, hide/show, recenter and stop notification controls.
- Capture another app's playback audio or the microphone. Android asks for consent
  each session. Apps can block playback capture; protected streams may be silent.
- Local Whisper, Vosk, Qwen3-ASR, Nemotron and English Moonshine runtimes, with verified model imports.
- Optional local translation using ML Kit language packs (play only), TranslateGemma
  4B, or the existing HY-MT1.5/Hy-MT2 quantizations. Explicit CC languages and translation directions;
  bounded background translation, live toggle, and per-line timing.
  See [local translation setup](docs/local-translation.md).
- Cloud STT and real-time Arabic/English translation with your own provider keys.
  Soniox v5 adds original and translated text; Scribe v2 adds another STT choice.
  Speech-only model filtering, provider configuration, and encrypted key storage.
- Direct HTTPS file downloads with foreground progress, cancellation, selectable public folder,
  and automatic installation of catalogued speech/translation models. New downloads use
  `Downloads/Hearth/models` or `files` on Android 10+. This does not include a video-site extractor.
- A local-only `foss` variant with **no network permission**, and a network-enabled
  `play` variant. ML Kit is an optional Google SDK in the play flavor; foss excludes it.

- Two-way **Conversation** with a quick language swap, microphone or typed turns, originals and translations, local history and optional offline TTS. **Face to face**, directly at the top of Home and on the conversation screen, gives each person a half-screen and large Speak button, with the upper half rotated. Translation can use an installed model or Google Cloud, Microsoft Azure, DeepL or LibreTranslate. See [conversation scope](docs/conversation-mode-plan.md) and [translation connections](docs/conversation-cloud-translation.md).
- **Local benchmark** compares installed models on the same WAV or corrected text, separates loading from inference and saves/export reports on-device. See [method and limitations](docs/local-benchmark.md), [speech phone results](docs/validation-v17.md), and the [eight-option local translation comparison](docs/local-translation-benchmark-2026-09-13.md).
- Persistent System, Light and Dark appearance in Setup.

## Build

Android 9+ (API 28); **arm64 only**. Playback capture requires Android 10+.
Use JDK 17, Android SDK 36, NDK 27.0.12077973 and SDK CMake 3.22.1. Set ANDROID_HOME
or create an ignored `local.properties` with `sdk.dir=/your/android/sdk`.
The Gradle wrapper, required Whisper source subset, and speech runtime binaries are
included. No Hearth checkout or local Maven repository is needed.

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
bash common-jni/src/main/cpp/common/tests/run_common_utils_tests.sh
bash common-jni/src/main/cpp/speech/tests/run_speech_tests.sh
bash common-jni/src/main/cpp/vosk/tests/run_vosk_api_tests.sh
python3 scripts/speech/test_package.py
./gradlew :common-jni:externalNativeBuildDebug :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
```

APKs: `app/build/outputs/apk/{play,foss}/qa/`. QA builds use the standard Android
**debug signing key** and application ID `com.sal7one.transiber.qa`. They are test
artifacts, not a stable signing identity for store releases. For a production
release, build the unsigned release variant and sign it with your own key outside
Git; see the [release and signing guide](docs/releasing.md).
Never commit keys or credentials. CI builds and uploads both QA APKs.
See [changes](CHANGELOG.md), [security reporting](SECURITY.md), and the
[publication audit](docs/publication-audit.md).

## Start captions

1. On the home screen, choose Device audio or Microphone, original captions or
   translation, and your language. Your last setup is remembered.
2. Tap **Start captions**. Android asks for any required permissions or device-audio
   consent; the app does not ask you to choose the audio source again.
3. While running, **Show captions** recovers the bubble and **Stop** ends capture.
   Bubble settings separate Appearance from CC & translation.

**Setup** contains Models, Downloads and Cloud connection. Models groups Speech,
Translation and Cloud, with source links, installed models and matching import actions.
Speech and translation family chips open the matching setup directly; the active
model is labeled separately. Translation sizes are grouped by family. See [all sources and installation paths](docs/model-sources.md). Connect
or change your existing cloud provider/key under Cloud connection. Advanced setup
retains the detailed engine controls and live cloud presets.

Local Qwen and Nemotron recognize speech. The optional local text-model bridge
translates their output with a separately selected translator. Whisper can
translate to English. Local Marian translation requires a compatible installed
language-pair bundle. Recognition accuracy, language coverage, capture eligibility
and latency depend on the model, device and source. This app does not recognize
sign language. See [model setup](docs/models.md), [language pickers and extension guide](docs/language-pickers.md), and [device checks](docs/device-checks.md).

## Privacy and project scope

See [PRIVACY.md](PRIVACY.md). This standalone app has a separate Android identity from the original media-suite Hearth. Existing standalone Transiber installations upgrade in place to the Hearth name, retaining saved keys, models and settings. Data from the media-suite app is not transferred automatically. Uninstalling deletes app-private data and installed
models. New public Downloads files remain; export older app-stored downloads or
Android 9 downloads first if you need to keep them.

The repo has fresh history and excludes Hearth's editor, FFmpeg, books, camera,
image processing, yt-dlp, chat and unrelated screens. Shared utilities and speech
JNI APIs retain their original packages where required for native binding compatibility.

## License

First-party code: Apache-2.0. Bundled third-party code and runtime libraries retain
their own terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Model weights are separately licensed downloads, not bundled app assets.

### Model downloads

In Models, **Download & install** downloads catalogued Moonshine Tiny/Base,
Qwen3-ASR 0.6B, Nemotron and translation GGUFs, then verifies and installs them
on the phone. No computer, export or re-import is required. Use the installed
model when ready; a background completion does not replace a running session's engine.

Original downloads live in **Downloads/Hearth/models** (direct URLs
in `files`). **Downloads → Choose folder** selects another writable folder for
future downloads, including on Android 9. Installed engines keep a separate,
verified app-owned copy. Downloads can be deleted without uninstalling models.
ML Kit packs remain managed by Google's SDK. The offline APK has no downloader.

Custom model packaging is an extension/developer path documented in
[model contributing](docs/model-contributing.md), not a prerequisite for catalog downloads.
