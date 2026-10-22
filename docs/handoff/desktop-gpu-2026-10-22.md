# Desktop GPU checkpoint — 2026-10-22

Local checkpoint tag: `desktop-v1.0.0-rc.3`, on `codex/hearth-desktop-kmp`.
No GitHub Actions calls, pushes, workflow dispatches, release updates or Windows
hardware claims were made. Android platform/JNI sources and the single Android
`:hearth-tts` boundary remain intact.

## Result and scope

The Mac app image at `desktopApp/build/compose/binaries/main/app/Hearth.app`
contains rebuilt Metal-capable Whisper and GGUF translation adapters. Both ran
real inference through the actual packaged launcher and bundled JRE. The owner
app was restarted after validation; microphone/system-audio capture was not
started. Model fixtures remain ignored and are never packaged.

NVIDIA CUDA is implemented as an opt-in Windows x64 build for those two adapters.
The owner chose to run the Windows validation script locally. **Compilation and
actual NVIDIA inference remain unverified until that receipt arrives.** Follow
[the Windows build/run recipe](../desktop-cuda.md) and send back
`build/nvidia-inference.json` with build logs. No Windows artifacts were built on
this Mac.

| Engine | Mac | Windows CUDA path |
| --- | --- | --- |
| Whisper | CPU + real Metal inference verified | Implemented; owner hardware run pending |
| GGUF translation / llama.cpp | CPU + real Metal inference verified | Implemented; owner hardware run pending |
| Qwen, CTranslate2, other speech, local TTS | Existing CPU adapters | No new GPU claim |

Metal is now the macOS builder default for eligible stages; use `--no-metal` for
an explicit CPU build. CUDA requires `--cuda` on Windows x64. Fresh local engine
routes prefer an available GPU. Explicit saved CPU/GPU selections and per-stage
options survive. The owner's current Qwen/CTranslate2 choices remain CPU; select
Whisper/GGUF to use these GPU adapters.

## Why the old metadata was wrong

The previous builder stamped the whole bundle with its latest `--metal` argument,
even when rebuilding an unrelated CPU TTS stage. The shipped Whisper/translation
binaries actually returned false from `metalAvailable()` and their CMake caches
had Metal disabled.

The builder now probes a C export from each actual eligible binary in an isolated
process, records compiled backends against that binary's SHA-256, retains its
source receipt across unrelated builds, and invalidates evidence when bytes
change. Per-library `sources` pin the exact build inputs; the top-level source
receipt explicitly covers the most recent build. Unrelated upstream receipts
are retained. Bundle-wide backend fields derive from these probes. Runtime GPU
availability still requires a device; compile support alone is insufficient.

## Native/runtime contract

CPU=0, Metal=1, CUDA=2. Desktop options are `acceleration=cpu|metal|cuda` and
`gpuDevice=<nonnegative integer>` (default0, CPU ignores a saved GPU index).
Whisper protocol3 and translation protocol5 add `createWithBackend`; legacy
create methods remain. Android JNI adaptations are generated in ignored desktop
build files, without modifying shared Android sources.

Whisper requires selected-device backend initialization and positive actual model
weight allocation. Translation binds one selected device with split mode NONE,
then requires positive layer assignment and model allocation on that exact
device, excluding CPU/Host buffers. Both report `completedInference` only after
successful native inference. Explicit unavailable/failed GPU requests throw real
errors and cannot turn into CPU success. CUDA initialization includes actual
runtime error descriptions. Device inventory uses native enumeration.

CUDA packaging stages the dependency closure from an official installed Toolkit,
verifies NVIDIA signatures and hashes, includes the installed EULA/notices,
and excludes the system driver. The loader verifies the entire dependency list
before loading any DLL, then loads pinned Microsoft CRT and NVIDIA libraries by
absolute private paths. Normal/delay imports and dynamic cuBLASLt dependencies
are checked. Missing/unknown dependencies stop packaging. CUDA12/13 signature
and driver behavior still require the owner Windows run.

## Actual Mac evidence

[Packaged inference receipt](../research/desktop-metal-inference-2026-10-22.json):
Apple M4 Pro / macOS26.5, pinned Whisper tiny Q5_1 and EuroLLM1.7B Q4_K_M.
Whisper allocated31.57MB on Metal; translation offloaded25/25 layers and allocated
993.80MiB on MTL0. Both completed inference. CPU/Metal produced the same transcript
and Arabic translation, preserving the negative instruction and42 dollars.

The first Gradle smoke observed speech885→74ms and translation950→207ms inference.
The separate packaged run observed877→607ms and1041→593ms, including initial GPU
graph/pipeline work. **These are single-fixture observations, not benchmarks.**
First backend registration compiles embedded Metal shaders, even when its first
model request is CPU: initial packaged speech/translation loads included8.8s and
23.9s respectively. This startup cost remains; do not hide it or attribute it to
CPU inference. Later Metal model loads in that process were47ms and186ms.

## Verification

- Required `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin`,
  `:common-jni:externalNativeBuildDebug`, all six required desktop suites and
  `:desktopApp:createDistributable`: PASS (53s,384 tasks).
- Desktop suite totals: runtime91/0 failures/31 opt-in skips; data24/0/1 skip;
  media9/0; integration18/0; UI61/0/2 skips; core10/0.
- Common and speech host suites: PASS with LLVM22 ASan/UBSan.
- New GPU placement proof host test: PASS with LLVM22 ASan/UBSan; rejects zero,
  forged, wrong-device, CPU/Host-only and nonfinite GPU allocations.
- Python desktop build/packaging fixtures:22 PASS, including9 CUDA staging cases.
- Actual packaged launcher/JRE/native/media/crypto/loopback check: PASS.
- Actual packaged Metal GPU inference and independent CPU controls: PASS.
- Current hardware-free unavailable-CUDA, invalid-backend/device, protocol,
  persistence and loader guards: PASS. These do not establish CUDA execution.

Ignored logs and receipts are under `build/desktop-gpu-smoke/`, especially
`final-gate.log`, `build-whisper-final.log`, `build-translation-final.log`,
`common-host.log`, `speech-host.log`, `python-tests.log`, `packaged-check.log`,
`metal-packaged.log` and `metal-packaged.json`.
