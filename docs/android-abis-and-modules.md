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

## Active ABI matrix (0.28.1)

| Module | ABIs | Reason |
| --- | --- | --- |
| `:app` | arm64-v8a, armeabi-v7a, x86, x86_64 | Java/Compose app; split APKs |
| `:common-jni` | arm64-v8a, armeabi-v7a, x86, x86_64 | shared speech JNI |
| `:hearth-onnx-runtime` | arm64-v8a, armeabi-v7a, x86, x86_64 | shared ONNX Runtime |
| `:hearth-tts` | **arm64-v8a** | core + all TTS adapters, one CMake project, one test set |

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


## Android Studio active ABI

One resolved ABI list in the root build script controls packaging and CMake:

1. Explicit `-PhearthAbis=arm64-v8a` (comma-separated for multiple ABIs).
2. Legacy `-Phearth.abis=...`.
3. Android Studio's injected `android.injected.build.abi` device hint.
4. All four release ABIs when none is supplied.

An explicit release/CLI choice sets the policy. A conflicting IDE hint fails with
instructions to remove that hint for CLI/release builds, or select the same ABIs
and sync Gradle. AGP independently uses the hint to suppress native tasks, so
accepting a conflict could otherwise produce missing native libraries. Empty
explicit values, unknown names such as `arm64`, or partly valid lists fail instead
of silently enabling every architecture. A blank IDE hint means no selected
device. Use the canonical Android name `arm64-v8a`.

The app, common JNI and ONNX use that list. TTS intersects it with ARM64; on other
architectures its CMake project and JNI source directories are disabled. The TTS
CMake entry point additionally rejects unsupported Android ABIs, including stale
IDE requests. Keep the single `:hearth-tts` module.

**Gradle Sync is required after changing build configuration.** Independent
per-module ABI edits and cleaning caches are not the solution. CMake's ABI filters
are explicit, and pre-build/native tasks depend on `verifyAndroidAbiConfiguration`.
Its report is `build/reports/android/abi-configuration.json`.

The audit found that library `ndk.abiFilters` alone left all prebuilt ABIs in an
ARM64 common-JNI AAR. Root packaging filters now remove unselected native folders
from AARs as well as APKs. An ARM64 → x86 → ARM64 sequence was built **without
cleaning** and the resulting common-JNI/TTS/ONNX archive bytes and ELF identities
were checked at every step. The x86 TTS archive retains its Java API and contains
no native libraries; the return to ARM64 restores its actual JNI runtimes.

```sh
# Use the same ABI for every Android module; do not edit each module separately.
./gradlew -PhearthAbis=arm64-v8a :app:assemblePlayQa :app:assembleFossQa

# Reproduce configuration, native task-graph and actual AAR switch checks.
python3 scripts/verify-android-abi-config.py --build-switches

# A full release build must explicitly restore all supported app ABIs afterward.
./gradlew -PhearthAbis=arm64-v8a,armeabi-v7a,x86,x86_64 \
  :app:assemblePlayQa :app:assembleFossQa \
  :hearth-tts:assembleRelease :hearth-onnx-runtime:assembleRelease
python3 scripts/verify-release.py
python3 scripts/verify-tts-aars.py
```

The switch-check script restores the full **configuration**; its last archive build
is ARM64. Rebuild the full release artifacts with the final command before using
the normal all-ABI release/AAR verifiers.

## Completed consolidation

`:hearth-tts` is now the single Android TTS Gradle module. It owns:

- `SpeechEngine`, `ArticleReader`, voice resolution and all adapter classes.
- One Android CMake project that builds every family JNI target.
- The ARM64 prebuilt runtime `jniLibs` and the merged test source set.

`:hearth-onnx-runtime` remains the one shared ONNX Runtime module. The old
`hearth-tts-*` source directories remain as source folders referenced by
`hearth-tts` source sets; their Gradle build files are removed, so they are not
separate modules. A future cosmetic move can fold the directories physically.
