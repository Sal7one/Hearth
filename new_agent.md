# Hearth: Meta glasses handover for the next agent

Prepared 24 October 2026; refreshed when today's work was integrated into `main`.
Read this document and `AGENTS.md` before changing code.

## 1. Assignment and branch

The owner wants to start supporting **Meta glasses for live subtitles of people
speaking**, with optional translation, and will continue on a Windows machine
with a stronger NVIDIA GPU. The immediate request was to prepare this branch and
handover, not to claim a finished glasses integration.

- Repository: <https://github.com/Sal7one/Hearth>
- Working branch: `codex/meta-glasses-handoff`, synchronized with the integrated `main`.
- Released Android source: `cfb3d3c218b13a11fd3166c3c80efce2a180cd5d`, tag `v0.30.0-rc.15`.
- `main` includes the Android updates and this handover. The original handover branch
  started from RC11; use the updated remote branch, not an old local checkout.
- RC15 / code 111 is public, with flowing captions and exact model selection.
  RC16 / code 112 adds saved text colour/opacity controls and a shorter README.
  See `docs/handoff/overlay-caption-flow-2026-10-24.md` for the caption flow contract.
- No Meta SDK, glasses app, remote caption transport or new Windows runtime has
  been implemented. The glasses work remains a planning handover.
- The exact Meta frames, firmware, phone pairing, Windows GPU and driver are
  still unknown. Confirm these at the beginning; continue hardware-independent
  preparation while waiting.

For an existing Windows clone, preserve any local changes before switching:

```powershell
git fetch origin
git switch --track origin/codex/meta-glasses-handoff
git pull --ff-only
git status --short --branch
```

If the local branch already exists, use `git switch codex/meta-glasses-handoff`
and then `git pull --ff-only`. Preserve local work if Git reports divergence;
do not reset or force-push it.
For a fresh checkout:

```powershell
git clone --branch codex/meta-glasses-handoff https://github.com/Sal7one/Hearth.git
cd Hearth
```

Suggested opening prompt for the next agent:

> Read AGENTS.md and new_agent.md. Continue Hearth's Meta glasses live-caption
> work on this branch. Confirm my exact Meta glasses model and Windows NVIDIA
> hardware, prove actual packaged CUDA inference, and then implement a small
> caption receiver using the existing independent speech/translation pipeline.
> Preserve Android and the authenticated loopback API. No GitHub Actions or gh
> runs. Keep hardware-verified results separate from simulations and build flags.

## 2. What Hearth is

Hearth is an Android and Kotlin Multiplatform desktop speech/accessibility app.
It runs local models or independently selected cloud providers for:

1. Speech-to-text from microphones, supported device/system audio and files.
2. Optional text translation.
3. Optional text-to-speech, including an embeddable article-reading library.
4. Floating captions, typed translation and saved caption history.
5. Camera/image OCR and screen/manga translation.
6. Model discovery, direct downloads, imports, integrity checks and benchmarking.
7. Desktop external-app/OBS integration and a paired browser extension.

The owner particularly cares about Arabic, Saudi/Gulf speech, English and mixed
speech. Their phones include S25 Ultra and S22 Ultra. They prefer simple setup
bundles, saved preferences and genuinely advanced controls hidden under Advanced.
Hearth must remain flexible: speech, translation and voice are separate stages.
Glasses are another input/output adapter, not a replacement pipeline.

Android is the established application. Desktop has its own Compose UI, runtime
owners, persistence, microphone/media adapters and integration server. macOS
arm64 has real-model evidence; Windows support is wired but still needs actual
Windows/NVIDIA execution and installer acceptance.

## 3. Non-negotiable constraints

- **No GitHub Actions:** do not dispatch, inspect or await runs, including
  `gh run`. Use local builds. The handover commit uses `[skip ci]`; retain an
  appropriate skip marker when pushing work under this instruction.
- Preserve Android screens/services, saved routes, preferences, JNI ABI and model
  integrity. Share portable contracts without replacing working platform owners.
- Keep native Java/Kotlin bindings in `com.sal7one.common_jni`.
- Preserve joined cancellation, native handle ownership, bounded queues and
  exact-once final text. Surface actual failures; never return empty success.
- **One Android `:hearth-tts` module.** Per-family source directories still exist;
  they are not permission to recreate eight Gradle TTS modules. Read
  `settings.gradle.kts` and `docs/android-abis-and-modules.md`.
- Root Gradle resolves one `hearthActiveAbis` policy for app/common JNI/TTS/ONNX.
  Use `-PhearthAbis=...`; do not manually diverge module ABI settings. Local
  Android TTS is ARM64-only. Desktop `windows-x64` is a separate host target.
- Android **FOSS has no network permission**. Network clients and delegated
  downloads are gated by `ByokPolicy.FEATURE_BYOK`. A phone-to-PC/glasses network
  sender belongs behind the Play/BYOK policy; do not silently enable FOSS network.
- TVs must not run local models. Do not broaden scope into a media editor,
  FFmpeg, books, sign recognition or yt-dlp.
- Never send provider credentials to model/file hosts or a caption receiver.
  Do not commit keys, local.properties, signing stores, model weights, recordings,
  generated native bundles, installers, build outputs or research checkouts.
- The owner tests devices. Do not use adb or operate their phone/glasses unless
  explicitly asked. A connected device alone is not authorization to drive it.
- Preserve current QA app/signing identity when Android artifacts are requested.
  Release assets are individual files; the owner rejected combined release ZIPs.
- Reuse existing art/branding. Do not alter third-party or model licenses; qualify
  redistribution before bundling dependencies or voices into a commercial build.

## 4. First decision: which Meta glasses?

The owner said “Meta glasses”; **do not assume that means a display model**.

| Hardware | Consequence |
| --- | --- |
| Ray-Ban Meta Gen 1/2, without a display | Cannot show in-lens subtitles. Investigate SDK-supported microphone/audio use with captions on the phone or another display. Explain this before building a visual receiver for those frames. |
| Meta Ray-Ban Display | Candidate for visible captions. Current official documentation describes display web apps as well as a mobile Device Access Toolkit. Verify exact device/firmware, supported markets, permissions and current SDK API before selecting a route. |
| Meta Quest | Different platform and SDK. A later passthrough/spatial caption client is a separate target; it is not the first glasses deliverable. |

Official sources checked during this discussion on 24 October 2026:

- Meta developer entry point: <https://developers.meta.com/wearables/>
- Device/toolkit capabilities and availability:
  <https://developers.meta.com/wearables/faq/>
- Display web apps: <https://developers.meta.com/wearables/web-apps/>
- Official web tooling/examples:
  <https://github.com/facebook/meta-wearables-webapp>
- Display's existing captions:
  <https://www.ray-ban.com/usa/c/frequently-asked-questions-meta-ray-ban-display>

The display web-app documentation describes HTML/CSS/JavaScript and fetch/
WebSocket connectivity over Wi-Fi or a phone tether. That makes a thin caption
receiver worth prototyping; it does **not** prove that every browser feature,
background session, microphone route or PC connection works on the owner's
frames. The mobile toolkit and a display web app are distinct integration paths.
Pin the chosen SDK/tooling and record what was actually tested. Do not base a new
implementation on old community posts about historical SDK restrictions.

Meta already offers built-in captions/translation on Display. The commercial
hypotheses to test are better Arabic/Gulf and mixed-language behavior, local or
self-hosted inference, model/provider choice and readable customization. None is
an established quality advantage yet.

## 5. Read the code in this order

Paths below are relative to the repository root so they work on Windows.

| Area | Starting files | What to learn |
| --- | --- | --- |
| Portable contracts | `hearth-core/src/commonMain/kotlin/com/sal7one/hearth/core/PipelineContract.kt` | `PipelineConfig`, `PipelineInput.AudioStream`, `PcmFrame`, `SpeechSegment`, `CaptionSegment`, `PipelineSession`, `PipelineService`, `PipelineEngines`. |
| Pipeline policy | `hearth-core/src/commonMain/kotlin/com/sal7one/hearth/core/HearthPipeline.kt` | Stable-ID partial replacement, exact-once finals, optional downstream stages, admission limits and joined cancellation. |
| Desktop engines | `desktop-runtime/src/main/kotlin/com/sal7one/hearth/desktop/runtime/DesktopEngines.kt` | Actual native/cloud adapters and current recognition emission behavior. |
| Live endpointing | `desktop-runtime/src/main/kotlin/com/sal7one/hearth/desktop/runtime/DesktopSpeechWindows.kt` | VAD, pre-roll, window settings and silence endpointing. |
| Microphone capture | `desktop-runtime/src/main/kotlin/com/sal7one/hearth/desktop/runtime/DesktopAudio.kt`, `DesktopPcmCapture.kt` | Canonical audio production, bounded capture and stop/cancel lifecycle. |
| GPU truth | Same runtime directory: `DesktopCompute.kt`, `DesktopNativeLibraries.kt`, `DesktopGpuValidation.kt` | Compiled versus available backends, selected placement, manifest loading and actual inference receipts. |
| Desktop session owner | Locate `DesktopWorkspaceController.kt` under `desktopApp/src/desktopMain/` | Owns capture/session, starts the pipeline and attaches integration observation. |
| Saved setup | `desktop-data/src/main/kotlin/com/sal7one/hearth/desktop/data/DesktopSettingsStore.kt`, `DesktopCredentialStore.kt`; locate `DesktopConfigExt.kt` in desktopApp | Persisted selections, credential references and resolving missing compute preferences without overriding explicit choices. |
| Integration server | `desktop-integration/README.md`, `desktop-integration/src/main/kotlin/com/sal7one/hearth/integration/DesktopIntegrationServer.kt` | Existing REST/SSE interface, authentication, loopback checks and session ownership. |
| Existing browser receiver | Same integration directory: `OverlayPage.kt`, `IntegrationEvents.kt`, `IntegrationJson.kt` | Caption rendering, replay/resync, read-only capability and bounded serialization. |
| Android captions | `app/src/main/java/com/sal7one/transiber/caption/CaptionCaptureService.kt`, `CaptionEngineController.kt`, `CaptionOverlayController.kt` | Existing foreground service and separate Android controller; do not assume it already implements `PipelineService`. |
| Native build | `scripts/desktop/build-native.py`, `scripts/desktop/native/CMakeLists.txt`, `desktop-runtime/src/main/cpp/` | Supported stages/hosts, pins, GPU flags, JNI ownership and runtime staging. |

Useful guides: `docs/desktop.md`, `docs/desktop-cuda.md`,
`docs/desktop-engine-matrix.md`, `docs/desktop-engine-sharing.md`,
`docs/desktop-native-verification.md`, `docs/obs-setup.md`,
`docs/handoff/desktop-speech-performance-2026-10-24.md`.

## 6. What already works, and important limits

### Pipeline and latency

- Core `PcmFrame` is little-endian mono PCM16 at 16 kHz, nonempty/even-sized and
  at most 64,000 bytes. Platform producers must keep their queues bounded.
- `SpeechSegment`/`CaptionSegment` have stable IDs, timestamps and final flags.
  The contract supports revisions; it has no speaker identity field.
- `HearthPipeline` publishes original text before downstream translation. It
  translates finalized speech only. There is one active downstream item plus
  eight queued finals; overflow fails visibly instead of growing indefinitely.
- **Desktop recognition currently emits final utterance windows**, including
  for `AudioStream`. It is not token-streaming STT just because the input or
  descriptor uses the word streaming. Read `DesktopEngines.kt` before promising
  immediate partial captions.
- Live defaults are now 4-second maximum windows with an RMS endpoint gate,
  200 ms pre-roll and 600 ms trailing silence after sufficient speech. Explicit
  saved window settings remain. A fast network does not remove endpoint delay.
- Stable interim captions, actual incremental decoder support and deliberate
  low-latency presets are new work. Preserve the fallback for windowed models.
- Speaker diarization, recognizing a person's name, attaching words to faces and
  meeting summaries are additional capabilities. Keep them out of the first MVP.

### Existing desktop API is local, not a glasses network service

- Binds `127.0.0.1` only; checks peer, Host and Origin. Do not weaken these checks
  or change the bind address to `0.0.0.0` to make glasses connect.
- Transport is **REST plus Server-Sent Events, not WebSocket**.
- `/v1/*` requires a bearer token. The OBS URL instead has a separate read-only
  caption capability exchanged for an HttpOnly/SameSite cookie. Neither is a
  provider secret. Never distribute the writable API token to a display client.
- `observe(PipelineSession)` mirrors an existing desktop-owned session; closing
  observation does not cancel that session. Keep that ownership distinction.
- Live PCM endpoints are `POST /v1/streams`, sequential `PUT /v1/streams/{id}`,
  then `POST /v1/streams/{id}/finish`. They accept at most 6,400 bytes/200 ms per
  frame, eight queued frames and 120 seconds of input per session. A 429 frame
  was not accepted. These are not an unlimited long-running wearable protocol.
- There is no remote audio sequence/idempotency contract; a retry after a lost
  successful response can duplicate audio. Do not use naive retries over Wi-Fi.
- SSE has bounded replay, `Last-Event-ID` and explicit resync. Caption JSON
  lacks language metadata, speaker IDs and end-to-end capture latency.
- `OverlayPage.kt` is useful reference code with `textContent` and `dir=auto`;
  it is an OBS layout, not a ready-made 600-pixel glasses interface.

### Host coverage

- Windows microphone capture uses Java Sound. File decoding uses Media Foundation.
  Native system-loopback capture is currently Mac-only; do not assume Windows
  YouTube/system-audio capture exists. Conversations can start with a PC/phone
  microphone; glasses microphone input needs its own supported SDK adapter.
- Windows CUDA currently targets **Whisper and llama.cpp GGUF translation**.
  It does not accelerate every STT/TTS/translation model in the catalog.
- Windows `build-native.py --stage all` builds Whisper, GGUF translation,
  Supertonic and OCR. Qwen/sherpa, CT2, Nemotron/backend speech, Vosk, Ming and
  several TTS families remain restricted to macOS arm64 in this desktop builder.
  Do not advertise them as working Windows GPU options.
- Existing macOS Metal evidence is not Windows CUDA evidence. NVIDIA inference
  has not been validated on this Mac. The Windows machine is the next proof step.

## 7. Windows setup and real NVIDIA validation

Use **Developer PowerShell for Visual Studio**, on Windows x64. Install Git,
Python 3.11+, CMake 3.22+, an appropriate Visual Studio C++/Windows SDK toolchain,
a supported NVIDIA driver and an official CUDA 12/13 Toolkit. Record the exact
GPU, VRAM, driver, toolkit and compiler versions; do not assume the owner's card.

Compilation targets Java 17. The current `desktopApp/build.gradle.kts` requires
a **full JDK 21 installation**, including `jpackage`, for packaging; the app
bundles a Java 21 runtime, not the full development toolkit. Install both
toolchains or configure Gradle's toolchain discovery appropriately. Older
JDK-17-only packaging prose in
`desktopApp/README.md` and parts of `docs/desktop-cuda.md` is stale. Current code
and `docs/desktop.md` take precedence. Android Studio's JBR may omit jpackage.
MSI/EXE packaging also requires WiX 3 (`candle.exe` and `light.exe` available to
the packaging process). The default Windows native stages do not need Ming/Rust.

Start with the existing local commands; toolkit/JDK/model paths are examples:

```powershell
java -version
python --version
cmake --version
nvcc --version
nvidia-smi

python scripts/desktop/prepare-dependencies.py
python scripts/desktop/build-native.py --stage all --cuda `
  --cuda-toolkit 'C:/Program Files/NVIDIA GPU Computing Toolkit/CUDA/v12.8' `
  --llama-root build/desktop-dependencies/llama.cpp `
  --ort-root build/desktop-dependencies/onnxruntime-win-x64-1.20.1 --jobs 4
python scripts/desktop/stage-windows-crt.py
./scripts/desktop/build-media.ps1
./gradlew.bat -PhearthDesktopOnly=true :hearth-core:desktopTest :desktop-runtime:test :desktop-data:test :desktop-media:test :desktop-integration:test :desktopApp:desktopTest :desktopApp:createDistributable
python scripts/desktop/check-packaged.py
```

Check `$LASTEXITCODE` after each external command and stop on failure; PowerShell
does not generally stop a command sequence just because a native process failed.
If JDK 21 discovery fails, supply the installed full JDK explicitly, for example
`'-PhearthDesktopJavaHome=C:/Program Files/Java/jdk-21'` on the Gradle command.
Use `hearthDesktopOnly=true` to avoid needing an Android SDK for desktop work.
This does not replace Android regression checks before committing shared changes.

The native builder stages CUDA runtime dependencies with hashes, signature checks
and notices. CRT staging separately verifies the required Microsoft DLLs. Do not
copy arbitrary DLLs from PATH/System32, bundle `nvcuda.dll`, remove hash checks or
hardcode GPU support based on a toolkit's presence. Retain portable CUDA
architecture settings for distributed builds, rather than `native` only.

Supply local pinned models and an actual 0.5–30 second speech WAV. The validator
does not download, record, play audio or change app settings. Accepted fixture
hashes are deliberately narrow:

| Fixture | SHA-256 |
| --- | --- |
| Whisper tiny Q5_1 | `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7` |
| Whisper tiny.en Q5_1 | `c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b` |
| EuroLLM 1.7B Q4_K_M | `f22ab4b628f6e3cd12ec2e5aa6d1e460be2fa83f25cb6a8f5dd6d4d77b285b66` |

Pinned download references are in `docs/desktop-cuda.md`; keep weights outside
Git. Then run:

```powershell
python scripts/desktop/validate-gpu.py --backend cuda `
  --whisper 'D:/Models/ggml-tiny-q5_1.bin' `
  --translation 'D:/Models/EuroLLM-1.7B-Instruct.Q4_K_M.gguf' `
  --wav 'D:/Samples/speech.wav' --receipt build/nvidia-inference.json
```

This invokes the **packaged launcher and bundled JRE**. It tests CPU and CUDA
separately and requires actual device initialization, GPU model placement,
successful native inference counters, nonempty recognition and English-to-Arabic
translation. Keep the JSON and logs locally; report device identity, separate
load/single-inference timings, outputs and actual placement. First inference can
include kernel/graph initialization; collect repeated warm trials separately.
A CMake flag, `nvidia-smi` activity,
CPU fallback or a green packaging test is insufficient proof.

This validator is a first gate, not an Arabic STT quality test or full benchmark.
Next test multilingual speech, mixed Arabic/English, long-session stability and
cancel/restart with the selected production models. English-only `.en` Whisper
cannot be used to prove Arabic recognition. Model-free checks include:

```powershell
python -m unittest discover -s scripts/desktop -p 'test_stage_cuda_runtime.py' -v
python -m unittest discover -s scripts/desktop -p 'test_stage_windows_crt.py' -v
python -m unittest discover -s scripts/desktop -p 'test_build_native.py' -v
```

Build MSI/EXE only on Windows after the app image and GPU checks pass:

```powershell
./gradlew.bat -PhearthDesktopOnly=true :desktopApp:packageMsi :desktopApp:packageExe
```

Resolve packaging-tool requirements from actual local diagnostics. Do not call a
Windows installer verified before launching/checking its matching-host package.

## 8. Proposed implementation sequence

These are planned tasks, not already-landed features.

### A. Establish a working baseline

Confirm frames and Windows GPU, check the current Meta APIs, run the packaged
CUDA proof, and demonstrate a microphone-to-caption session on Windows. Establish
English and Arabic quality/timing separately. Keep real outputs as evidence.
Use Windows microphone input first if glasses audio access is not yet proven.

### B. Add a small receiver and a deliberate remote transport

Default processing should stay on the phone/PC; the glasses render captions and
handle connection state. Initial data flow:

```text
supported microphone -> existing Hearth STT -> optional translation
                    -> paired caption transport -> Meta display receiver
```

Keep portable caption contracts in `hearth-core` if both platforms consume them.
Use an adapter interface for publication/subscription so the implementation can
use a vendor companion channel or WebSocket/WSS without changing engine policy.
Reuse existing SSE behavior for a local simulator where useful. A new paired
LAN transport is separate work; keep the existing loopback server secure.

Define a versioned message envelope with session ID, sequence, utterance ID,
revision, original text, optional translation, source/target language, interim/
final status and timing. Optional future speaker labels must not delay captions.
Replace interim text by ID, deduplicate finals, bound queues/history, expire stale
captions, and implement explicit reconnect/resync/stop behavior. A reconnect must
not append an old sentence twice or display an expired conversation indefinitely.

Pair receivers explicitly with short-lived/read-only credentials, revocation and
a clear stopped state. Keep provider keys and write-capable tokens on the owner
host. Do not disable TLS verification or expose an unauthenticated LAN endpoint
to work around browser certificate or local-network restrictions. Verify the
actual glasses networking/origin/session behavior before committing to a route.

If audio is sent from a phone/glasses microphone to the PC, define a separate
bounded PCM transport with timestamps, frame identity, acknowledgement/retry
semantics and joined cancellation. Do not reuse the current 120-second upload
API unchanged for an hour-long conversation. Avoid sending audio at all when a
local PC microphone already provides the required input.

### C. Make it useful as subtitles

- Caption-only, translated-only and bilingual modes; Arabic RTL and mixed digits.
- Compact one/two-line UI suited to the actual display, readable contrast, font
  size/placement controls and a short bounded history. Preserve normal vision.
- Clear listening/loading/disconnected/translation-pending/error states. Do not
  silently show original text as a successful translation.
- Pause/resume, clear, stop and reconnect using controls the frames expose.
- Persist choices through one existing settings owner. The receiver should not
  make users choose engines/models again or reset source/target on each launch.
- Voice output off initially, to prevent feedback and avoid adding TTS latency.
- Show early partials only when the actual decoder produces them. Make a new
  low-latency mode explicit; do not globally rewrite file-transcription windows.

### D. Measure before claiming real-time support

Measure capture-to-first-text, finalized caption latency, translation lag, cold
model load, warm inference, queue depth, dropped/rejected input, memory and
long-session drift. Distinguish input audio duration from processing latency.
Use named test fixtures and record the selected language/engine/backend for each.

Test silence, short/long speech, background voices, code-switching, network loss,
sleep/reconnect, pause/resume, duplicate/out-of-order events, Arabic layout and
cancel while GPU work is active. A browser simulator proves only receiver logic;
actual frames must prove readability, microphone access and connection behavior.
Do not invent a latency achievement or claim recognition of who is speaking.

## 9. Recent release state and documentation drift

- Current Android candidate source is `0.30.0`, version code **112** (RC16):
  saved caption colours and independent text opacity, including minimal mode.
  The public [RC15](https://github.com/Sal7one/Hearth/releases/tag/v0.30.0-rc.15)
  APKs are code **111**, bound to `cfb3d3c218b13a11fd3166c3c80efce2a180cd5d`,
  with the existing QA signer. README now keeps brief guides and Android/desktop/TV galleries.
- RC12 adds the Android minimal overlay header; RC13 fixes translator readiness
  and selection; RC14 adds accessible timing controls, verified Jisr downloads
  and Phonon 2.5's 25 European languages. Phonon 2.5 does not recognize Arabic
  or Chinese and does not report language identification in Auto. Its source
  declaration is not a decoder hint. Read `docs/models/phonon-2.5.md` and
  `docs/handoff/phonon25-fast-captions-2026-10-24.md` before changing this path.
- The [Phonon model release](https://github.com/Sal7one/Hearth/releases/tag/v0.30.0-models.1)
  provides the 339 MB ONNX TAR and separate offline ZIP. The publisher's packed
  156.2 MB runtime is not integrated. English/Russian inference is verified on
  Mac CPU; Windows, GPU and phone performance are not established by these tests.
- Desktop installer version is independently **1.0.4**. Latest separately
  documented Mac desktop release:
  <https://github.com/Sal7one/Hearth/releases/tag/desktop-v1.0.4-rc.1>.
- `README.md` links the current Android release and separate desktop artifacts.
  The published Desktop 1.0.4 installer predates the latest Phonon 2.5/Jisr
  desktop code in `main`; rebuild from source to test those changes. The October
  22 handover and older desktop docs contain historical state; check current
  code and later handoffs before copying version/platform claims.
- RC11 addressed Android screen translation stability around moving tickers,
  manual exclusions, progressive translated regions, ML Kit pack readiness and
  Marian empty results. Keep those changes; glasses work does not justify
  rewriting the reader or downloader.
- Prior Mac Metal and CPU execution receipts are under `docs/research/` and the
  native verification/performance guides. They do not establish Windows GPU or
  current glasses execution. Owner device acceptance remains distinct.

## 10. Validation and handback requirements

`AGENTS.md` requires before committing:

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
```

Use `./gradlew.bat` on Windows. These full-project checks require the Android SDK
and the configured JDK/native dependencies; do not silently replace them with a
desktop-only command. Desktop changes additionally require:

```sh
./gradlew -PhearthDesktopOnly=true :hearth-core:desktopTest :desktop-runtime:test :desktop-data:test :desktop-media:test :desktop-integration:test :desktopApp:desktopTest
```

Build native helpers first for full host coverage. For native changes run the
speech/common host suites and `externalNativeBuildDebug` as required by AGENTS.
Before APK delivery build Play and FOSS QA and run
`python scripts/verify-release.py`; before installer delivery use
`python scripts/desktop/check-packaged.py` on the matching OS. Keep meaningful
protocol, lifecycle, language and receiver tests; do not mistake skipped
model-gated tests for real inference. Report unavailable checks explicitly.

For each handback record the commit, changed paths, commands/results, skipped
checks, actual hardware, runtime/model pins, sample language/text and observed
latency. Keep phone, Mac, Windows, GPU and glasses evidence separate. Provide
individual artifacts only when requested. The handover is included in `main`,
but it does not establish an implemented glasses integration.

Handover creation validation on macOS: the required
`./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin` passed (358
actionable tasks: 2 executed, 356 up-to-date). `git diff --check` passed. This
commit changes documentation only; it does not establish new Windows, NVIDIA,
Meta glasses or phone execution evidence. No GitHub Actions were used.
