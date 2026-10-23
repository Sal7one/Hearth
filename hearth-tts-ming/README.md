# hearth-tts-ming (Android, experimental)

Android-only adapter over the shared Ming-3.0-tts-0.5B JNI. This module is **not** used by
desktop-runtime; desktop has its own wrapper in `desktop-runtime/src/main/kotlin/com/sal7one/common_jni/MingNative.kt`.
This module never touches the desktop Ming files.

## Staged native libraries

The Rust JNI is built by `scripts/desktop/build-ming-android.sh` (NDK r27, Rust 1.99,
`aarch64-linux-android`, 16 KB page alignment). Both outputs are staged here:

| Packaged name | Source artifact | SHA-256 | Use |
| --- | --- | --- | --- |
| `arm64-v8a/libming_jni.so` | `build/ming-android/arm64-v8a/libming_jni-dotprod.so` | `3c45361e…2f0178` | preferred when `/proc/cpuinfo` advertises `asimddp` |
| `arm64-v8a/libming_jni_scalar.so` | `build/ming-android/arm64-v8a/libming_jni-scalar.so` | `2703bb05…380744` | scalar fallback, and always used when detection is inconclusive |

`build.gradle.kts` pins both hashes in `verifyMingAndroidLibs` before every build. Model weights
are **not** in this module or the APK; the 850 MB bundle is imported or downloaded by the app.

The dotprod file keeps the canonical `libming_jni.so` name, so the wrapper's
`System.loadLibrary("ming_jni")` is the normal path; scalar devices load `ming_jni_scalar`.

## Contract

- One-shot JNI: load bundle → generate whole utterance → write one 44.1 kHz mono PCM16 WAV.
  No streaming, no native cancel, no partial output.
- English and Chinese only (`language: None` in the shipped runtime). The five English presets
  and two Chinese presets are allowlisted locally because the native runtime silently accepts
  unknown voice ids.
- 300 characters per request, CPU only, at most four Rayon threads (preset 0, seed 1895).
- The engine runs the blocking JNI on its own worker thread and never on the caller/main thread.

## Install layout: flat files, linked parts/

The app's model library installs the **17 flat catalog files**:

```text
files/voice-models/ming-local/manifest.json
files/voice-models/ming-local/tokenizer.json
files/voice-models/ming-local/part-000.bin … part-014.bin
```

`MingModels.verify` hashes those flat files, then creates the runtime's
`parts/000.bin … parts/014.bin` layout as hard links (absolute symlinks if hard links are
unsupported). A bundle that contains only `parts/` is not an app install layout and is rejected;
[`MingLocalDeviceTest`](../app/src/androidTest/java/com/sal7one/transiber/voice/MingLocalDeviceTest.kt)
pushes the flat 17-file bundle for the same reason. The app install path
(`VoiceCatalog.mingAssets` → `VoiceModels`) writes flat files.

## Device evidence (22 October 2026)

Measured on a Samsung SM-G781B, Android 13, arm64-v8a, 8 GB RAM, CPU advertising `asimddp`:

| Path | Threads | Audio / wall | RTF | Peak process memory |
| --- | ---: | --- | ---: | ---: |
| `app_process` + dotprod JNI, preset 0 | 4 | 3.600 s / 9.77 s | 2.120 | 1,045,028 kB |
| `app_process` + dotprod JNI, preset 0 | 8 | 3.600 s / 35.4 s | 9.264 | — |
| `app_process` + dotprod JNI, fast preset | 8 | — | 8.349 | — |
| `app_process` + scalar JNI, preset 0 | 8 | 3.600 s / 125 s | 34.097 | — |

The 4-thread dotprod run also reported first audio at 1.608 s and bundle load at 1.953 s. More
threads and the scalar build are strictly worse on this device, so Android caps the pool at four
and selects scalar only when the CPU does not advertise `asimddp`. These numbers cover the JNI
path; UI/decoding overhead is not measured.

An in-app instrumentation run in the `playDebug` arm64 APK (`System.loadLibrary`, JNI, SELinux)
also passed through `MingSpeechEngine` → `ArticleReader`; logcat reported
`MING_ANDROID_APP frames=88200 peak=0.46887207` and a valid 44.1 kHz mono WAV. That is one device,
not a general performance or memory claim. The engine remains feature-flagged off by default.
