# Android CPU and TV support

Hearth 0.23.9 (QA code 82) targets Android 9 / API 28 and later.
The universal APK is the easiest choice when a phone or TV's CPU is unknown.

| APK suffix | Process | Typical device |
| --- | --- | --- |
| armeabi-v7a | 32-bit ARM / NEON | ARM Android TVs and older phones |
| arm64-v8a | 64-bit ARM | Current phones and 64-bit TVs |
| x86 | 32-bit Intel / Android ABI baseline | Intel devices and emulators |
| x86_64 | 64-bit Intel / Android ABI baseline | Intel devices and emulators |
| universal | Device-selected | Includes all four architectures |

Every suffix has two flavors: cloud/local (`play`) and offline (`foss`).
Both retain the QA application ID and debug signing identity. Update in place to
retain saved models and settings. The offline build has no network permissions.
No model weights are embedded in these APKs.

32-bit support does not expand the address space of a 32-bit process. Large
models can still exceed available RAM/address space; start with smaller models
and use the benchmark on the actual device. No model family is removed.
CPU compatibility does not promise equal inference speed on all devices.

## Native build and provenance

All four architectures include the same common JNI features, Vosk, ONNX,
private speech plugins and private llama translation runtime. Vosk/ORT are
extracted from the existing SHA-pinned AARs. Speech and translation are rebuilt
from pinned sources with NDK 27.0.12077973.

```sh
python3 scripts/stage-vendor-runtimes.py
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
  bash scripts/speech/build-runtimes.sh all android "$abi"
  python3 scripts/speech/stage-runtimes.py "$abi"
  bash scripts/translation/build-runtime.sh android "$abi"
  python3 scripts/translation/stage-runtime.py "$abi"
done
./gradlew :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py --build-type qa
```

Keep staging sequential because it updates shared manifests/checksums. Each ABI
records its binary digest and a canonical digest of its source snapshot. A
changed recipe invalidates previously staged architectures until they are rebuilt
and staged. Verification checks all ten APKs, their exact native library sets,
ELF machine/class, dependencies, private exports, permissions and runtime hashes.
64-bit libraries retain 16 KiB load alignment. The pinned 32-bit Vosk libraries
use 4 KiB alignment; Android's 16 KiB native requirement concerns 64-bit devices.

ARMv7 uses portable GGML kernels instead of the optional AArch64-only FP16
llamafile kernel. x86 builds avoid mandatory SSE4.2/AVX/FMA/F16C; x86_64 follows
Android's SSE4.2 baseline without mandatory AVX. Common JNI uses 64-bit file
offsets without changing the public Kotlin/JNI handle contract.

## TV integration

The Main activity has a Leanback launcher entry and a 320 by 180 dp banner.
Touchscreen, microphone and camera remain optional. Wide home cards place artwork
beside their title and action so images do not hide the controls in landscape.
Existing D-pad/media controls remain available.

Android 14 meets the OS minimum. Haier MatrixTV EE was reported by the owner,
but its actual firmware/CPU and capture behavior have not been physically tested.
Some TV firmware or content can restrict overlays or playback capture; installing
the APK and launching it is a separate check from capturing another app's audio.

## Runtime checks

`AbiRuntimeDeviceTest` loads common JNI, Marian, translation and every speech
backend export without weights or providers. It asserts the actual process width.
The reusable script refuses physical-device serials, installs both QA flavors,
checks their selected ABI/startup, and runs the library test:

```sh
./gradlew :common-jni:assembleDebugAndroidTest
bash scripts/test-android-abi.sh emulator-5554 x86
bash scripts/test-android-abi.sh emulator-5554 x86 universal
```

The native ABI workflow uses disposable Linux/KVM emulators for x86/x86_64.
ARMv7 uses Google API 30's x86 image and its advertised ARM native bridge; this
checks ARMv7 libraries in a 32-bit Android process, not physical ARM performance.
ARM64 Android 14 TV installation, split/universal startup and native loading pass
on the Mac. Final evidence and any remaining gaps are in [review-0.23.9.md](review-0.23.9.md).

## Reproduce TV screenshots

An instrumentation-only fixture renders the production `CaptionOverlayWindow`
in a real Android overlay window over a labeled demo background. It captures
English/Arabic captions, Appearance settings, and Audio loudness/voice controls.
The samples are UI fixtures, not ASR/translation accuracy or TTS playback evidence.
The fixture starts no engines and is not packaged in distributed APKs.

```sh
./gradlew :app:assemblePlayDebug :app:assemblePlayDebugAndroidTest
bash scripts/capture-tv-overlay.sh emulator-5554 /tmp/hearth-tv
```

Use a disposable Android 14 or later TV emulator. Both the script and test refuse physical
devices; the test also checks TV mode. The capture permission is granted only
to the debug app on that selected emulator.

Primary platform references: [Android ABIs](https://developer.android.com/ndk/guides/abis),
[TV launcher requirements](https://developer.android.com/training/tv/get-started/create),
[16 KiB pages](https://developer.android.com/guide/practices/page-sizes),
[emulator acceleration](https://developer.android.com/studio/run/emulator-acceleration).
