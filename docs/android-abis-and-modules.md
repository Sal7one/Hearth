# Android ABIs, variants and module structure

## Variant vs ABI split

`release arm64-v8a` is **not a Gradle build variant**. Hearth has one `release`
build type; `splits.abi` produces several APK **outputs** from it:

```
app-play-arm64-v8a-release.apk
app-play-armeabi-v7a-release.apk
app-play-x86-release.apk
app-play-x86_64-release.apk
app-play-universal-release.apk
```

Android Studio's Build Variants pane selects flavor/build type, not ABI. The ABI
choice happens when you pick the APK, run a split-aware task, or use the IDE's
injected ABI property. The arm64 APK itself contains only `lib/arm64-v8a`.

## Active ABI matrix (0.28.0)

| Module | ABIs | Reason |
| --- | --- | --- |
| `:app` | arm64-v8a, armeabi-v7a, x86, x86_64 | Java/Compose app; split APKs |
| `:common-jni` | arm64-v8a, armeabi-v7a, x86, x86_64 | shared speech JNI |
| `:hearth-onnx-runtime` | arm64-v8a, armeabi-v7a, x86, x86_64 | shared ONNX Runtime |
| `:hearth-tts` | pure Java | SpeechEngine/ArticleReader contracts |
| `:hearth-tts-supertonic` | arm64-v8a, armeabi-v7a, x86, x86_64 | ONNX Runtime backend; no prebuilt family runtime |
| `:hearth-tts-magpie` | **arm64-v8a** | prebuilt Magpie runtime is ARM64-only |
| `:hearth-tts-moss` | **arm64-v8a** | prebuilt MOSS runtime is ARM64-only |
| `:hearth-tts-omnivoice` | **arm64-v8a** | prebuilt OmniVoice runtime is ARM64-only |
| `:hearth-tts-oute` | **arm64-v8a** | prebuilt Oute runtime is ARM64-only |
| `:hearth-tts-chatterbox` | **arm64-v8a** | prebuilt Chatterbox/ORT graph runtime is ARM64-only |
| `:hearth-tts-ming` | **arm64-v8a** | prebuilt Ming JNI is ARM64-only |

The x86/ARMv7 APKs still contain Supertonic, Vosk and the shared speech stack, but
**not** the ARM64-only TTS families. Earlier builds packaged their tiny JNI
wrappers without the family runtime on x86 — those could load but could never
synthesize; this is fixed by per-module `ndk.abiFilters` and the release verifier's
explicit ARM64-only set.

## Why there are so many Gradle modules

There is no owner approval recorded for this structure. The commits are authored
under the repository owner's git identity because the agents use that configured
identity; the module split was not a product decision documented in this session.

Timeline from `settings.gradle.kts` history:

| Commit | Added |
| --- | --- |
| `09de08f` | `:hearth-tts`, `:hearth-tts-supertonic` |
| `539e0c0` | `:hearth-tts-magpie` |
| `ecd1dc0` / `13429dd` | `:hearth-onnx-runtime`, `:hearth-tts-moss`, `:hearth-tts-oute`, `:hearth-tts-chatterbox` |
| `1a2f41e` (this session) | `:hearth-tts-ming` |

The `:hearth-tts-ming` module is mine. I followed the existing pattern instead of
challenging it; that was a mistake relative to the intended
`app + common-jni + core` shape.

The original reason for separate adapter projects was publishing one AAR per TTS
family for the standalone TTS SDK. The app does not need that topology: all
adapters compile into the same APK and share the same core contracts.

## Consolidation target

One Android library module, `:hearth-tts`, should own:

- `SpeechEngine`, `ArticleReader`, voice resolution and all adapter classes.
- One Android CMake project that builds the family JNI targets.
- The ARM64 prebuilt runtime `jniLibs` and the shared ONNX Runtime packaging.
- One test source set (the current per-module tests move in place).

The standalone SDK can still publish per-family AARs from one project using
publications/source sets; it does not need seven Gradle projects in the app build.
This is a structural refactor, not a small ABI change, so it should not be mixed
into the 0.28.0 APK fix. The follow-up should remove the six adapter entries from
`settings.gradle.kts` only after the merged `:hearth-tts` module builds, tests and
the release verifier pass.
