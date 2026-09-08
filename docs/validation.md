# Extraction validation — 2026-09-08

- Fresh standalone Gradle build without a Hearth project dependency or local Maven repository.
- `./gradlew test`: 106 app tests in each of six flavor/build variants; 46 common-jni
  tests in each of debug and release. Zero failures/errors.
- `:app:compilePlayQaKotlin` and `:app:compileFossQaKotlin`: passed.
- `:common-jni:externalNativeBuildDebug`: passed; release native output built with QA APKs.
- `run_speech_tests.sh`: 36 native checks, AddressSanitizer + UndefinedBehaviorSanitizer, passed.
- `run_common_utils_tests.sh`: all common utility host suites passed.
- `scripts/speech/test_package.py`: four packaging tests passed.
- Both QA APKs built with R8. `scripts/verify-release.py` verifies library inventory,
  dynamic dependencies, 16 KB alignment, prebuilt SHA-256s, package name and permissions.
- Native APK contents: speech JNI, Qwen, Nemotron, Vosk, ONNX Runtime, C++ shared runtime,
  plus two AndroidX helper libraries. No FFmpeg/OpenCV/LiteRT libraries.
- Cloud QA: about 23.0 MB. Offline QA: about 22.5 MB. Model weights are separate.
- No API-key/private-key token patterns found in the selected first-party source.
  No stored credentials, local.properties, signing stores or parent Git history included.
- Original Hearth working tree remains unchanged.

The copied upstream source retains upstream formatting. QA uses Android debug signing;
production signing and a stable release identity are separate. Device testing remains
pending; see device-checks.md. No paid API calls or Android device automation were used.
