# Hearth handoff validation record

Validation recorded on 2026-10-20 for branch `codex/s25-models-tts-provider`, based on `v0.25.4` (`6c15890b284b6c74074d13d7d45d1311d0cf6035`). This record describes the handoff and the accompanying foundation changes. It does not certify the thirteen new adapters that remain unimplemented, new-model accuracy, or S25 performance.

## Implemented and prepared boundaries

- **Implemented in this branch:** small `:hearth-tts` API, `:hearth-tts-supertonic` adapter, production `VoicePlayer` consumer, downloaded-model TV policy/UI, EuroLLM/Riva catalog/prompt integration and protocol-3 native translation binaries.
- **Prepared:** all five new STT families, QuickMT/SMaLL/Jisr, and all five new TTS families. Their cards contain exact source/component identities and blockers; they are not advertised as executable integrations.
- **Not performed:** new model-weight downloads or inference, phone/TV device interaction, owner listening tests, release APK assembly, APK delivery verification, Maven publication or GitHub release publication.

## Passed checks

| Check | Result |
| --- | --- |
| `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :hearth-tts-supertonic:compileDebugKotlin :common-jni:externalNativeBuildDebug :hearth-tts:assembleRelease --console=plain` | **BUILD SUCCESSFUL**, 318 tasks, 1m 33s |
| App unit tests for Play Debug, QA and Release | **621 cases each**, 0 failures/errors; 2 expected skips per variant |
| App unit tests for FOSS Debug, QA and Release | **621 cases each**, 0 failures/errors; 9 expected skips per variant |
| `common-jni` Debug and Release unit tests | **132 cases each**, 0 failures/errors/skips |
| TTS SDK Debug and Release unit tests | **9 cases each**, 0 failures/errors/skips |
| Native speech host suite with ASan/UBSan | **98** speech foundation, **168** Whisper, **27** TranscribePsy backend, **14** TranscribePsy GPU checks passed |
| Native common utilities host suite with ASan/UBSan | All **19 test executables** passed |
| Native voice bounds host suite with ASan/UBSan | Passed input/style/rate/step/duration bounds and chunk geometry checks |
| `ctest --test-dir build/translation-runtime/host --output-on-failure` | **3/3** tests passed |
| Root Python script tests | **30/30** passed, including the six handoff validator/renderer regressions |
| Speech package/source Python tests | **10/10** passed |
| macOS translation benchmark helper tests | **2/2** passed |
| Handoff metadata validator | **15 records / 68 default-profile components** passed pinned URL, size, digest-format, path, completeness and readiness checks |
| Formal JSON Schema validation | All three inventories passed `model-card.schema.json` |
| Local Markdown links | All handoff links resolved |
| `git diff --check` | Passed |

App tests include five Robolectric TV boundary cases. They exercise stale GGUF/Marian/ML Kit snapshots, model enqueue/retry, installation and registry import before file/network side effects. FOSS checks retain the earlier offline gate when it precedes the TV gate. These host tests do not replace owner TV UI tests.

## Reusable core size and packaging scope

The generated `hearth-tts-release.aar` is **39,703 bytes** in this build. This measures the core AAR only. It excludes its Kotlin/coroutines dependencies, model weights, optional runtime AARs and application playback code. The current Supertonic adapter depends on broad `common-jni` and therefore is **not yet a small standalone native TTS distribution**. Follow task R and TTS-1 to isolate that runtime before publication.

The core has no native library, model files, network permission or automatic downloader. The source model manifests remain documentation; they do not grant arbitrary executable loading or download-host selection.

## Bugs found and fixed during validation

The first SDK compile exposed an invalid channel cancellation call. Subsequent cancellation tests exposed a real deadlock: a producer blocked on a full PCM channel while collection cleanup waited for that producer to finish. `ArticleReader` now owns a bounded channel and cancels it **before** waiting for worker completion. Native destruction still runs afterward on the owning executor. Regression coverage also verifies copied PCM when an adapter reuses its borrowed sample array, external cancellation, and close while the queue is full.

The first full app run found three missing Chinese translations for the TV policy. English, Arabic and Chinese resources now match and the localization test passes.

The default Xcode-beta Apple Clang 17 ASan runtime on this machine spun before `main`, inside sanitizer shadow-memory initialization. A sampled stack established this as toolchain startup, not a test assertion or application hang. Native host suites then passed unchanged with **Homebrew Clang 22.1.8** at `/opt/homebrew/opt/llvm/bin/clang++`, retaining both address and undefined-behavior sanitizers. Do not disable sanitizers or edit application code to work around that old runtime.

## Reproduction

Run commands from the repository root. Set local toolchain paths for your machine; do not commit them into `local.properties` or project files.

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
./gradlew :hearth-tts:testDebugUnitTest :hearth-tts-supertonic:compileDebugKotlin
./gradlew :common-jni:externalNativeBuildDebug :hearth-tts:assembleRelease
CXX=/opt/homebrew/opt/llvm/bin/clang++ bash common-jni/src/main/cpp/speech/tests/run_speech_tests.sh
CXX=/opt/homebrew/opt/llvm/bin/clang++ bash common-jni/src/main/cpp/common/tests/run_common_utils_tests.sh
CXX=/opt/homebrew/opt/llvm/bin/clang++ bash common-jni/src/main/cpp/voice/tests/run_voice_tests.sh
ctest --test-dir build/translation-runtime/host --output-on-failure
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s scripts/speech -p 'test_*.py'
python3 -m unittest discover -s scripts/benchmark -p test_macos_translation.py
python3 scripts/validate-model-handoff.py --render
git diff --check
```

The ignored local Gradle log is `build/handoff-gradle-validation.log`; XML reports are under each module's `build/test-results/`. Those generated reports and AARs do not belong in Git. The specialist plans contain native rebuild recipes needed before replaying CTest on a fresh checkout.

Future APK delivery still requires both QA assemblies and `python3 scripts/verify-release.py`, signing/provenance checks and the owner's GitHub draft workflow. No new release or installable APK is implied by this handoff.
