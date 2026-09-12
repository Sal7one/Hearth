# Real time transiber

An Android app for live captions over other apps, local speech recognition, and
bring-your-own-key cloud transcription and translation. Extracted from Hearth's
caption system into a separate, focused project.

## Development backlog

See the [release checklist](docs/BACKLOG.md) for six focused improvements to
bubble controls, setup, downloads and smaller local translation.

## Features

- Movable, resizable caption bubble with retained text, prior lines, selectable
  history, pause/resume, hide/show, recenter and stop notification controls.
- Capture another app's playback audio or the microphone. Android asks for consent
  each session. Apps can block playback capture; protected streams may be silent.
- Local Whisper, Vosk, Qwen3-ASR and Nemotron runtimes, with verified model imports.
- Optional fully local translation after Qwen/Nemotron, using HY-MT1.5 or Hy-MT2
  in three quantizations each. Explicit CC languages and translation directions;
  bounded background translation, live toggle, and per-line timing.
  See [local translation setup](docs/local-translation.md).
- Cloud STT and real-time Arabic/English translation with your own provider keys.
  Speech-only model filtering, provider configuration, and encrypted key storage.
- Direct HTTPS file downloads through Android DownloadManager, progress, cancellation,
  and in-app installation of translation models. New downloads use
  `Downloads/Real time transiber/models` or `files` on Android 10+. This does not include a video-site extractor.
- A local-only `foss` variant with **no network permission**, and a network-enabled
  `play` variant. The flavor name does not require Google Play or Play Services.

## Build

Android 9+ (API 28); **arm64 only**. Playback capture requires Android 10+.
Use JDK 17, Android SDK 36, NDK 27.0.12077973 and SDK CMake 3.22.1. Set ANDROID_HOME
or create an ignored `local.properties` with `sdk.dir=/your/android/sdk`.
The Gradle wrapper, required Whisper source subset, and speech runtime binaries are
included. No Hearth checkout or local Maven repository is needed.

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
bash common-jni/src/main/cpp/speech/tests/run_speech_tests.sh
bash common-jni/src/main/cpp/vosk/tests/run_vosk_api_tests.sh
python3 scripts/speech/test_package.py
./gradlew :common-jni:externalNativeBuildDebug :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
```

APKs: `app/build/outputs/apk/{play,foss}/qa/`. QA builds use the standard Android
**debug signing key** and application ID `com.sal7one.transiber.qa`. They are test
artifacts, not a stable signing identity for store releases. For a production
release, configure your own signing key outside Git and build `assemblePlayRelease`.
Never commit keys or credentials. CI builds and uploads both QA APKs.

## Start captions

1. On the home screen, choose Device audio or Microphone, original captions or
   translation, and your language. Your last setup is remembered.
2. Tap **Start captions**. Android asks for any required permissions or device-audio
   consent; the app does not ask you to choose the audio source again.
3. While running, **Show captions** recovers the bubble and **Stop** ends capture.
   Bubble settings separate Appearance from CC & translation.

**Setup** contains Models, Downloads and Cloud connection. Models separates speech
recognition from translation, with details and larger variants collapsed. Connect
or change your existing cloud provider/key under Cloud connection. Advanced setup
retains the detailed engine controls and live cloud presets.

Local Qwen and Nemotron recognize speech. The optional local text-model bridge
translates their output with a separately imported HY-MT model. Whisper can
translate to English. Local Marian translation requires a compatible installed
language-pair bundle. Recognition accuracy, language coverage, capture eligibility
and latency depend on the model, device and source. This app does not recognize
sign language. See [model setup](docs/models.md) and [device checks](docs/device-checks.md).

## Privacy and project scope

See [PRIVACY.md](PRIVACY.md). This app has a separate Android identity: Hearth's
keys, model files and settings are not transferred automatically. Re-enter keys and
import the same model packages. Uninstalling deletes app-private data and installed
models. New public Downloads files remain; export older app-stored downloads or
Android 9 downloads first if you need to keep them.

The repo has fresh history and excludes Hearth's editor, FFmpeg, books, camera,
image processing, yt-dlp, chat and unrelated screens. Shared utilities and speech
JNI APIs retain their original packages where required for native binding compatibility.

## License

First-party code: Apache-2.0. Bundled third-party code and runtime libraries retain
their own terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Model weights are separately licensed downloads, not bundled app assets.
