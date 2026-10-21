# Desktop native verification

What has actually been executed, on which host, and what still needs a machine I
do not have. Keep this file honest: do not add a row you have not run.

## Verified on macOS arm64 (Apple Silicon, JDK 17, NDK 27.0.12077973)

| Check | Command | Result |
| --- | --- | --- |
| Android native, 4 ABIs | `./gradlew :common-jni:externalNativeBuildDebug` | `libcommon_jni.so` for arm64-v8a, armeabi-v7a, x86, x86_64 |
| Native bundle packaged | `./gradlew -PhearthDesktopOnly=true :desktop-runtime:jar` | 25 `native/macos-arm64/` entries, libraries stored as `*.payload` |
| Bundle loads + hashes | `:desktop-runtime:test --tests "*DesktopNativeContractTest*"` | 2 tests, 0 skipped; includes Qwen readiness/protocol |
| Handle ownership | `:desktop-runtime:test --tests "*DesktopNativeOwnershipTest*"` | 6 tests, 1 skipped |
| Whisper alone | `:desktop-runtime:test --tests "*DesktopWhisperRealSmokeTest*"` | transcript exact, `Metal GPU; threads=2` |
| Qwen3-ASR alone | `:desktop-runtime:test --tests "*DesktopQwenRealSmokeTest*"` | transcript exact, 406 ms decode for 4.65 s audio (RTF 0.081), model load 2.0 s |
| Qwen3-ASR through the desktop engine | same test | `COMPLETED`, one final segment, 5550 ms including model verification/load |
| CTranslate2 QuickMT EN→AR | `:desktop-runtime:test --tests "*DesktopCt2RealSmokeTest*"` | `يفتح المتحف في الساعة التاسعة صباحًا.` (111 ms native, 4771 ms engine path) |
| TranscribePsy 250M EU | `:desktop-runtime:test --tests "*DesktopTranscribePsyRealSmokeTest*"` | `The museum opens at 9 in the morning. Please bring your ticket to the East Entrance.` (75 ms warm decode, 4561 ms engine path) |
| Nemotron 3.5 ASR | `:desktop-runtime:test --tests "*DesktopNemotronRealSmokeTest*"` | `museum opens at nine in the morning please bring your ticket to the east entrance` (573 ms decode, 6784 ms engine path) |
| Moonshine tiny.en (shared sherpa bridge) | `:desktop-runtime:test --tests "*DesktopMoonshineRealSmokeTest*"` | `The museum opens at 9 in the morning. Please bring your ticket to the East Entrance.` (32 ms decode, 3999 ms engine path) |
| MOSS-TTS Nano 100M | `:desktop-runtime:test --tests "*DesktopMossRealSmokeTest*"` | "Hello from Hearth." / Ava, 48 kHz WAV, peak 0.516, RMS 0.0996, 99% non-zero; direct JNI 61,440 samples |
| Omnilingual CTC 300M | `:desktop-runtime:test --tests "*DesktopSherpaFamiliesRealSmokeTest*"` | `the museum opens at nine in the morning please bring your ticket to the east entrance` (220 ms warm) |
| Phonon 2 | same test | `The museum opens at nine in the morning. Please bring your ticket to the East Entrance.` (117 ms warm) |
| Vosk English | `:desktop-runtime:test --tests "*DesktopVoskRealSmokeTest*"` | exact lower-case transcript, 586 ms decode |
| Vosk six languages | `:desktop-runtime:test --tests "*DesktopVoskLanguagesSmokeTest*"` | six non-empty transcripts; Spanish quality/repeat stability unverified |
| Ming-3.0 TTS local | `:desktop-runtime:test --tests "*DesktopMingLocalRealSmokeTest*"` | 44.1 kHz mono, 61,740 frames, 87% non-zero, peak 14,658; English only |
| Ming catalog install | `:desktopApp:desktopTest --tests "*DesktopMingLocalInstallTest*"` | real 850 MB download, 17 files verified, flat→parts, 61,740 frames |
| Speech + voice | `:desktop-runtime:test --tests "*DesktopSpeechVoiceRealSmokeTest*"` | `COMPLETED segments=1 audio=1` |
| Speech + translation + voice | `:desktop-runtime:test --tests "*DesktopFullPipelineRealSmokeTest*"` | `COMPLETED ms=6182 segments=1 audio=1` |

### Reproducing the end-to-end run

Fixtures are never committed. Fetch the two pinned models and synthesise audio:

```bash
mkdir -p build/smoke
curl -sL -o build/smoke/ggml-tiny-q5_1.bin \
  "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny-q5_1.bin"
curl -sL -o build/smoke/milmmt-46-1b-q4.gguf \
  "https://huggingface.co/mradermacher/MiLMMT-46-1B-v1.0-GGUF/resolve/34df5efbe6592773ec168cc7b307728c08623472/MiLMMT-46-1B-v1.0.Q4_K_M.gguf"
say -o build/smoke/hello.wav --data-format=LEI16@16000 \
  "The museum opens at nine in the morning. Please bring your ticket to the east entrance."

shasum -a 256 build/smoke/ggml-tiny-q5_1.bin build/smoke/milmmt-46-1b-q4.gguf
# 818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7  (32,152,673 bytes)
# 74d38ba75108d455326e9deeaf9ab01bb266dfa665eae9c4aa84e84485d4fdf9  (806,057,408 bytes)
```

Both hashes match `docs/model-catalog-audit-2026-10-19.json`. Then:

```bash
HEARTH_FULL_SMOKE=1 \
HEARTH_SMOKE_WHISPER="$PWD/build/smoke/ggml-tiny-q5_1.bin" \
HEARTH_SMOKE_TRANSLATION="$PWD/build/smoke/milmmt-46-1b-q4.gguf" \
HEARTH_SMOKE_WAV="$PWD/build/smoke/hello.wav" \
./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopFullPipelineRealSmokeTest*"
```

Expected: `status=COMPLETED`, one caption segment, one audio artifact, and an
Arabic translation of the English line. A Mac without an installed English system
voice fails the test with that reason rather than silently skipping the stage.

### Reproducing the Ming local smoke

```bash
HEARTH_BUILD_JOBS=8 python3 scripts/desktop/build-native.py --stage ming
HEARTH_MING_LOCAL_SMOKE=1 HEARTH_MING_LOCAL_BUNDLE=<17-file bundle> \
  ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopMingLocalRealSmokeTest*"
```

The `ming` stage builds the pinned Rust JNI with rustc 1.99 and stages
`libming_jni.dylib` + `Ming-NOTICES.txt`. The runtime verifies the bundle's BLAKE3
parts; the catalog verifies per-file SHA-256. This is experimental and desktop-only;
Android is build-verified, not runtime-verified.

### Reproducing the Vosk smoke

```bash
HEARTH_VOSK_INSTALL_SMOKE=1 \
  ./gradlew -PhearthDesktopOnly=true :desktop-data:test --tests "*DesktopVoskModelInstallTest*"
HEARTH_VOSK_SMOKE=1 HEARTH_SMOKE_VOSK=<extracted English model> HEARTH_SMOKE_WAV="$PWD/build/smoke/hello.wav" \
  ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopVoskRealSmokeTest*"
```

The adapter is built from the shared `VoskEngine`, dlopens the pinned v0.3.42
runtime by absolute sibling path, verifies the expanded tree digest and exports the
same HearthSpeechBackend v2 ABI.

### Reproducing the Omnilingual/Phonon smoke

```bash
HEARTH_SHERPA_FAMILIES_SMOKE=1 \
HEARTH_SMOKE_OMNILINGUAL=<extracted omnilingual root> \
HEARTH_SMOKE_PHONON=<extracted phonon-2 root> \
HEARTH_SMOKE_WAV="$PWD/build/smoke/hello.wav" \
./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopSherpaFamiliesRealSmokeTest*"
```

Both reuse the Qwen dylib and the shared bridge; the only differences are the entry
symbol and the component paths. Phonon's joiner is passed in the `conv_frontend` slot.

### Reproducing the MOSS smoke

```bash
# put the 14 pinned MOSS files in build/agents/tts/models (fetch-moss-test-assets.py)
HEARTH_MOSS_SMOKE=1 HEARTH_SMOKE_MOSS="$PWD/build/agents/tts/models" \
  ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopMossRealSmokeTest*"
```

The `moss` stage compiles `hearth-tts-moss/src/main/cpp/moss.cpp`, the desktop
`moss_jni.cpp` direct-link adapter and the pinned SentencePiece source against the
already-shipped ONNX Runtime 1.20.1. The runtime is one dylib; no model weights are
committed.

### Reproducing the Moonshine smoke

```bash
# download and extract sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2
HEARTH_MOONSHINE_SMOKE=1 \
HEARTH_SMOKE_MOONSHINE=<extracted sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27> \
HEARTH_SMOKE_WAV="$PWD/build/smoke/hello.wav" \
./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopMoonshineRealSmokeTest*"
```

Moonshine uses the same Qwen sherpa dylib and the same `hearth_speech_backend_v1`
bridge; the only additions are the `hearth_moonshine_backend_v1` export, an explicit
entry symbol, and encoder/decoder paths. No new engine code.

### Reproducing the Nemotron smoke

```bash
HEARTH_BUILD_JOBS=8 python3 scripts/desktop/build-native.py --stage backend --speech-backends transcribepsy,nemotron
# put the pinned nemotron-3.5-asr-streaming-0.6b.q8_0.gguf in build/smoke/nemotron/
HEARTH_NEMOTRON_SMOKE=1 \
HEARTH_SMOKE_NEMOTRON="$PWD/build/smoke/nemotron/nemotron-3.5-asr-streaming-0.6b.q8_0.gguf" \
HEARTH_SMOKE_WAV="$PWD/build/smoke/hello.wav" \
./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopNemotronRealSmokeTest*"
```

The host recipe builds NeMo-Speech.cpp and SentencePiece at the pinned revisions
with the macOS 13.0 deployment target; ggml and SentencePiece are static inside the
backend dylib.

### Reproducing the TranscribePsy smoke

```bash
HEARTH_BUILD_JOBS=8 python3 scripts/desktop/build-native.py --stage backend --speech-backends transcribepsy
# put the pinned transcribepsy-250m-eu-sub.gguf in build/smoke/transcribepsy/
HEARTH_TRANSCRIBEPSY_SMOKE=1 \
HEARTH_SMOKE_TRANSCRIBEPSY="$PWD/build/smoke/transcribepsy/transcribepsy-250m-eu-sub.gguf" \
HEARTH_SMOKE_WAV="$PWD/build/smoke/hello.wav" \
./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopTranscribePsyRealSmokeTest*"
```

The host speech recipe is pinned to the reviewed parakeet revision and now emits
macOS 13.0 dylibs. The bridge dlopens the backend by its hash-verified absolute path;
the backend keeps parakeet and ggml static inside.

### Reproducing the CTranslate2 smoke

```bash
HEARTH_BUILD_JOBS=8 python3 scripts/desktop/build-native.py --stage ct2
mkdir -p build/smoke/ct2
# fetch the six quickmt-en-ar files pinned in Ct2Catalog.kt into one directory, then:
HEARTH_CT2_SMOKE=1 HEARTH_SMOKE_CT2_DIR="$PWD/build/smoke/ct2/quickmt-en-ar" \
  ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopCt2RealSmokeTest*"
```

The stage builds CTranslate2 4.8.3 + SentencePiece 0.2.0 + the Rust byte-BPE
statically (only system frameworks remain dynamic) and records the source receipt
backend_versions in the bundle manifest. The smoke never downloads or uses the network.

### Reproducing the Qwen3-ASR smoke

The static sherpa-onnx build fetches its dependencies at build time from URL_HASH-pinned
archives; nothing is downloaded at runtime. The model is never committed.

```bash
python3 scripts/desktop/prepare-dependencies.py
python3 scripts/desktop/build-native.py --stage onnx \
  --sherpa-root research/sherpa-onnx \
  --ort-root build/desktop-dependencies/onnxruntime-osx-arm64-1.20.1

mkdir -p build/smoke
curl -sL -o build/smoke/qwen3-asr.tar.bz2 \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"
tar -xjf build/smoke/qwen3-asr.tar.bz2 -C build/smoke
say -o build/smoke/qwen-hello.wav --data-format=LEI16@16000 \
  "The museum opens at nine in the morning. Please bring your ticket to the east entrance."

HEARTH_QWEN_SMOKE=1 \
HEARTH_SMOKE_QWEN_DIR="$PWD/build/smoke/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25" \
HEARTH_SMOKE_WAV="$PWD/build/smoke/qwen-hello.wav" \
./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopQwenRealSmokeTest*"
```

Expected: `QWEN_SMOKE language=en text=The museum opens at nine in the morning. Please bring your ticket to the east entrance.`
and a `QWEN_ENGINE_SMOKE ... segments=1` line. The test verifies all six model-file pins,
loads the packaged dylib, and never downloads or plays audio.

## Windows: not executed

No Windows host was available, so nothing below is a claim that Windows runs.

What exists and was checked:

- `scripts/desktop/build-native.py` (MSVC), `build-media.ps1`, `stage-windows-crt.py`,
  `prepare-dependencies.py` all byte-compile.
- `python -m unittest discover -s scripts/desktop -p 'test_*.py'` — 7 tests OK,
  including signed x64 CRT selection and signature-host isolation.
- `.github/workflows/desktop.yml` has a `windows-latest` job that builds the native
  runtimes, stages the app-local Microsoft runtime, packages installers and runs
  `scripts/desktop/check-packaged.py`.

Known risk from the previous handover: the Windows job failed at
`Stage verified app-local Microsoft runtime`. Its unit tests pass here, so the
failure is runner-specific — most likely what the GitHub image ships rather than
the logic. It cannot be reproduced from macOS.

**To settle Windows:** push the branch and let `desktop.yml` run, or run the same
commands on a Windows machine. Until then, treat Windows as build-wired and
unit-tested, not working.

## Limits worth stating

- Model weights are never committed; `build/smoke/` is local scratch.
- The opt-in smoke tests skip themselves unless their environment variable is set,
  so CI and the normal gates stay fast.
- Qwen3-ASR is built and verified for macOS arm64 only; the Windows bundle omits the
  `onnx` stage and its catalog card is filtered out there. Auto language recognition
  returns no language label on desktop (the Android build applies a sherpa patch that
  the pinned desktop build does not); explicit source languages are reliable.
- The desktop window has been launched (process alive, no exception, data directory
  created) but not visually inspected on a display.
