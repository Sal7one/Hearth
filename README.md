# Real time transiber

An Android app for live captions over other apps, local speech recognition, and
bring-your-own-key cloud transcription and translation. Extracted from Hearth's
caption system into a separate, focused project.

## Development backlog

See the [prioritized backlog](docs/BACKLOG.md) for the mobile translation work,
bubble/settings redesign, download improvements, subtasks and acceptance checks.

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
  open and export. This does not include a video-site extractor.
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

1. Open **Models** and import local weights, or choose **Cloud** in Captions and
   save your provider key in the cloud settings section.
2. For translated streams, choose the live Arabic/English cloud translation preset.
   For same-language CC, select a local recognizer or a cloud transcription mode.
3. Grant overlay, microphone and notification permissions. Start and choose device
   audio or microphone. Use the notification to pause, show, recenter or stop.
4. Open history in the bubble to read previous lines; captions continue while you read.

Local Qwen and Nemotron recognize speech. The optional local text-model bridge
translates their output with a separately imported HY-MT model. Whisper can
translate to English. Local Marian translation requires a compatible installed
language-pair bundle. Recognition accuracy, language coverage, capture eligibility
and latency depend on the model, device and source. This app does not recognize
sign language. See [model setup](docs/models.md) and [device checks](docs/device-checks.md).

## Privacy and project scope

See [PRIVACY.md](PRIVACY.md). This app has a separate Android identity: Hearth's
keys, model files and settings are not transferred automatically. Re-enter keys and
import the same model packages. Uninstalling deletes app-private data and downloads;
export downloads first if you need to keep them.

The repo has fresh history and excludes Hearth's editor, FFmpeg, books, camera,
image processing, yt-dlp, chat and unrelated screens. Shared utilities and speech
JNI APIs retain their original packages where required for native binding compatibility.

## License

First-party code: Apache-2.0. Bundled third-party code and runtime libraries retain
their own terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Model weights are separately licensed downloads, not bundled app assets.
