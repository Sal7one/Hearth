# Contributing to Hearth

Hearth is maintained by **Sal7one**. Contributions should improve live captions,
speech translation, model setup or accessibility. Start with [AGENTS.md](AGENTS.md)
for project rules and [README.md](README.md#build) for the Android toolchain.

## Make a focused change

1. Describe the user-visible problem and a way to reproduce it.
2. Keep changes behind the existing capture, speech and translation interfaces.
3. Include meaningful tests for changed behavior and explain any device checks in
   your pull request. Distinguish host checks from actual phone or cloud testing.

For new models or providers, follow the [model contribution guide](docs/model-contributing.md).
Declare the exact runtime, artifact, supported languages and language-selection
behavior. The [model source list](docs/model-sources.md),
[language guide](docs/language-pickers.md) and [utility rules](docs/utils-bible.md)
explain the existing extension points. Downloadable weights have separate licenses.

Preserve the offline flavor's lack of network access, native package compatibility,
verified model imports and original error messages. Never commit credentials,
signing keys, `local.properties`, model weights or generated builds.

## Check before submitting

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
```

For native or model-packaging changes, also run the relevant host checks:

```sh
bash common-jni/src/main/cpp/speech/tests/run_speech_tests.sh
bash common-jni/src/main/cpp/vosk/tests/run_vosk_api_tests.sh
python3 scripts/speech/test_package.py
./gradlew :common-jni:externalNativeBuildDebug
```

Before distributing APKs, build and verify both flavors:

```sh
./gradlew :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
```

QA APKs use debug signing. A passing build does not establish model quality or
performance on every phone. Include app version, device and model details when
reporting results; do not attach private recordings or provider keys.
