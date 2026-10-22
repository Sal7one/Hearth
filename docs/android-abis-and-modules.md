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
| `:hearth-tts` | **arm64-v8a** | core + all TTS adapters, one CMake project, one test set |
| `:hearth-onnx-runtime` | arm64-v8a, armeabi-v7a, x86, x86_64 | shared ONNX Runtime used by speech and TTS |

The x86/ARMv7 APKs still contain Vosk and the shared speech stack, but **not**
TTS: all TTS adapters, including Supertonic and Ming, are ARM64-only. Earlier
builds packaged tiny TTS JNI wrappers without their family runtimes on x86 —
those could load but could never synthesize. The merged module now follows
Android Studio's injected active ABI: selecting x86 emits no TTS native build;
selecting arm64-v8a builds the TTS CMake targets. The release verifier enforces
the explicit ARM64-only set.

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
| `1a2f41e` (this session) | `:hearth-tts-ming` (now folded into `:hearth-tts`) |

The `:hearth-tts-ming` module was mine. It is now folded into `:hearth-tts`; all
seven adapter projects and their `settings.gradle.kts` entries are gone. I followed
the existing pattern instead of challenging it; that was a mistake relative to the
intended `app + common-jni + core` shape.

The original reason for separate adapter projects was publishing one AAR per TTS
family for the standalone TTS SDK. The app does not need that topology: all
adapters compile into the same APK and share the same core contracts.

## Completed consolidation

`:hearth-tts` is now the single Android TTS Gradle module. It owns:

- `SpeechEngine`, `ArticleReader`, voice resolution and all adapter classes.
- One Android CMake project that builds every family JNI target.
- The ARM64 prebuilt runtime `jniLibs` and the merged test source set.

`:hearth-onnx-runtime` remains the one shared ONNX Runtime module. The old
`hearth-tts-*` source directories remain as source folders referenced by
`hearth-tts` source sets; their Gradle build files are removed, so they are not
separate modules. A future cosmetic move can fold the directories physically.
