# Desktop native verification

What has actually been executed, on which host, and what still needs a machine I
do not have. Keep this file honest: do not add a row you have not run.

## Verified on macOS arm64 (Apple Silicon, JDK 17, NDK 27.0.12077973)

| Check | Command | Result |
| --- | --- | --- |
| Android native, 4 ABIs | `./gradlew :common-jni:externalNativeBuildDebug` | `libcommon_jni.so` for arm64-v8a, armeabi-v7a, x86, x86_64 |
| Native bundle packaged | `./gradlew -PhearthDesktopOnly=true :desktop-runtime:jar` | 12 `native/macos-arm64/` entries, libraries stored as `*.payload` |
| Bundle loads + hashes | `:desktop-runtime:test --tests "*DesktopNativeContractTest*"` | 2 tests, 0 skipped |
| Handle ownership | `:desktop-runtime:test --tests "*DesktopNativeOwnershipTest*"` | 4 tests, 0 skipped |
| Whisper alone | `:desktop-runtime:test --tests "*DesktopWhisperRealSmokeTest*"` | transcript exact, `Metal GPU; threads=2` |
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
- The desktop window has been launched (process alive, no exception, data directory
  created) but not visually inspected on a display.
