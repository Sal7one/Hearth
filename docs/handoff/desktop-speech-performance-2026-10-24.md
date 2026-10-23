# Desktop speech performance — 24 October 2026

Source branch: `codex/desktop-speech-performance`. Installer: macOS arm64
`1.0.4`; Android remains `0.30.0` / code `104`. All validation was local;
no GitHub Actions were dispatched, inspected or awaited. No owner device,
microphone or live desktop UI was driven.

## Causes and changes

- The owner's saved Whisper Large-v3-turbo Q8 route contained a hash but no
  acceleration option, so runtime defaulted to CPU. `DesktopConfigExt.kt` fills
  only absent compute choices from actual available device capabilities.
  `Main.kt` applies and saves that resolution on startup, saves and imports;
  remembered/current engine selection also resolves an absent choice. Explicit
  CPU/GPU settings, model pins, paths, languages and tuning remain intact.
- `DesktopSpeechWindows.kt` makes every live engine's unset window 4 seconds,
  matching Standard in settings. Qwen/Nemotron previously used 15 seconds.
  Explicit 1–30-second windows and the longer 15/20-second file defaults remain.
  A 20 ms RMS gate retains 200 ms pre-roll and endpoints after 600 ms trailing
  silence once 250 ms of voice has accumulated. Skipped silence remains in
  source timestamps. Files retain every sample. The 12 s idle + 1 s speech +
  600 ms pause regression sends 1.8 s rather than 13.6 s into inference.
- Desktop Whisper protocol 4 adds `transcribeLive`: one greedy decode with no
  temperature retries, 96 tokens per four seconds (scaled/capped for longer
  explicit windows), and live-session Auto language reuse after nonblank speech.
  Exhausting that budget without EOT raises a visible error. The existing file
  method retains fallback decoding and per-window Auto detection.
- `DesktopNativeLibraries.kt` extracts/checks each requested adapter and its
  verified dependency closure instead of repeatedly scanning the whole bundle.
  Every required SHA remains checked. CUDA receipt/license files, DLL closure,
  driver exclusion and platform checks remain mandatory. Later backend
  `libraryPath` requests extract their own closure.
- The desktop sherpa build uses a disposable source mirror to apply Android's
  existing detected-language retention patch. The original pinned checkout stays
  clean; changed anchors fail the build, and the manifest records prepared-source
  hashes. `QwenSpeechModel.kt` maps the 30 supported language names/codes to
  translation source codes. Real Auto recognition now returns `en` downstream.
- Packaging uses Java 21 while compiled/shared bytecode and Android stay on Java
  17. The actual installer bundles checksum-verified Corretto 21.0.13 from the
  official October release. `hearthDesktopJavaHome` remains an explicit override.
  The desktop workflow declares both toolchains; it was not run.
- Speech settings show Nemotron's actual fixed four GGML workers and disable its
  ineffective thread slider. Saved tuning is preserved. This does not claim the
  whole process is restricted to four threads. Imported custom window durations
  also display their actual duration rather than the nearest preset's duration.

## Measurements and limits

All use the same existing 4.65 s English WAV on this Apple M4 Pro. See the
[machine-readable evidence](../research/desktop-speech-performance-2026-10-24.json)
for model/WAV/native hashes, transcripts, test context and rejected experiments.

- Exact installed Turbo Q8 whole-clip native baseline: CPU 31.17/34.12 s;
  Metal 2.89/2.38 s, same transcript and 873.55 MB real GPU model allocation.
- New resident Turbo live Metal four-second decode: 2.56 s first, 1.32 s cached.
  Java 17 production replay, including revalidation/startup but excluding audio
  acquisition, totals 57.41 s CPU / 7.44 s Metal for two windows. These numbers
  are not real microphone/stream latency and precede the Java 21 packaging gain.
- Qwen warm native: 0.573–0.595 s; Nemotron about 0.61 s. Their native CPU
  adapters already run faster than this clip's duration. Native GPU support was
  not invented for either engine.
- Fresh JVM/profile Nemotron startup, same Java 17: 16.50 → 9.86 s with the
  loader fix. OS file cache was warm and owner apps stayed running.
- Same 755.9 MB SHA-256, warm cache: Java 17 about 3.33 s; shipped Corretto 21
  about 0.30–0.39 s, identical digest. Java 17 reported hardware SHA disabled;
  the packaged Java 21 runtime enables it on this Mac.
- ONNX idle-worker sleeping was tested, then rejected: repeated isolated and
  concurrent trials did not establish a consistent latency benefit. The shipped
  native build retains upstream thread policy. Desktop ORT stays pinned at
  1.20.1; Android Qwen uses 1.27.1. Replacing the shared desktop ORT requires
  validation across OCR and all voice families, not a blind pin bump.

Forced live window boundaries can split words and repeat text: this fixture
repeats “East” at the four-second cut. No general recognition-quality improvement
is claimed. File behavior is preserved. Cold model verification/loading still
takes time; the app retains a single native speech model for each active session.
Translation/TTS remain independent bounded downstream stages and final text
ownership/cancellation semantics remain unchanged.

## Validation

- Required `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin`: PASS.
- Required six desktop suites: 338 cases, zero failures/errors, 39 deliberate
  opt-in/fixture skips. Focused real Qwen/Nemotron and Whisper tests separately
  passed 8 and 4 cases. Loader/CUDA/language focused tests passed 10 cases.
- Desktop Python build/dependency suites: 23 PASS.
- Common and speech C++ ASan/UBSan host suites: PASS.
- `:common-jni:externalNativeBuildDebug`: PASS; Android sources/ABI inputs unchanged.
- Native media helper build/CTest: PASS.
- DMG CRC verification, mounted application's strict deep codesign, packaged
  launcher/native/media/audio/crypto/authenticated-loopback self-check: PASS.
- Actual mounted installer CPU/Metal speech plus English→Arabic GGUF translation:
  PASS, real GPU weights, completed inference, all 25 translation layers offloaded.
  The package reports Java 21.0.13, version 1.0.4, minimum macOS 13.3 and the
  existing `com.sal7one.hearth.desktop` identity. It is ad hoc signed.

Installer reproduction (local verified full Java 21 JDK required):

```sh
./gradlew -PhearthDesktopOnly=true -PhearthDesktopJavaHome=/path/to/current/jdk21 \
  :hearth-core:desktopTest :desktop-runtime:test :desktop-data:test \
  :desktop-media:test :desktop-integration:test :desktopApp:desktopTest \
  :desktopApp:packageDmg
python3 scripts/desktop/check-packaged.py
python3 scripts/desktop/validate-gpu.py --backend metal \
  --whisper /path/to/ggml-tiny-q5_1.bin \
  --translation /path/to/EuroLLM-1.7B-Instruct.Q4_K_M.gguf \
  --wav /path/to/hello.wav --receipt /path/to/metal.json
```

Raw logs and receipts are in ignored `build/desktop-speech-performance` and
`/tmp/hearth-stt-*.log`. The owner still checks live YouTube/system audio and
caption feel. Windows/NVIDIA hardware validation remains the owner's separate
local-script run; no Windows installer or NVIDIA inference was verified here.

