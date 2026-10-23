# Hearth on Windows and macOS

The desktop application uses Kotlin Multiplatform and Compose Multiplatform.
Android keeps its existing screens, service controllers, product flavors and JNI
ABI. Shared translation chunking is now consumed by Android and desktop. Desktop
adds its own runtime owners, audio and window adapters instead of putting desktop
conditions through the Android application.

## What runs

| Area | Desktop implementation |
| --- | --- |
| Inputs | Mac system audio through ScreenCaptureKit, typed text, a chosen microphone/virtual audio device, PCM WAV and native-system-supported audio/video files. |
| Local recognition | Pinned whisper.cpp with CPU/Metal and an opt-in Windows CUDA build; Qwen3-ASR, Moonshine/Omnilingual/Phonon (sherpa-onnx), Vosk (Kaldi), TranscribePsy (parakeet/ggml) and Nemotron 3.5 (NeMo ggml) use CPU on macOS arm64. Live recognition uses utterance windows; it is not a token-streaming decoder. |
| Local translation | Hearth's existing llama.cpp translation model/prompt protocol plus pinned CTranslate2 QuickMT/SMaLL-100 packages, with pinned EuroLLM/Riva catalog entries and explicit verified custom model paths. |
| Local voice | Supertonic 3 (F1–F5/M1–M5), MOSS-TTS Nano, Ming-3.0 local (experimental, English/Chinese), OmniVoice (24 kHz), Magpie (22.05 kHz) and Chatterbox V3 (24 kHz, experimental). |
| Cloud stages | Independently configured OpenAI-compatible STT, chat translation and WAV TTS endpoints; local compatible servers may omit credentials. Provider keys never enter the model downloader. |
| System voice | Installed macOS/Windows voices, enumerated by language; no automatic voice installation or assumption that every OS has Arabic voices. |
| Captions | A movable, always-on-top desktop caption window and an OBS Browser Source. Translation and voice remain optional independent stages. |
| Files/results | Original/translated text export, generated-WAV playback/export and authenticated integration output downloads. |
| Models | Search/filter, exact sizes/provenance/licenses, SHA-256-verified installs/imports, explicit Use and revalidation on launch. The catalog has **71 pinned cards** (models remain downloads; no weights are committed). |
| Settings | DataStore preferences, portable JSON setups, macOS Keychain and Windows current-user DPAPI for provider secrets. |
| Integrations | Authenticated loopback HTTP jobs, live bounded PCM input, SSE results/cancellation and read-only OBS caption access. |

The native desktop ports are Whisper, Qwen3-ASR, TranscribePsy, Nemotron 3.5, Vosk, Moonshine ×10, Omnilingual, Phonon, llama.cpp and CTranslate2 translation, and local voice engines Supertonic, MOSS, Ming, OmniVoice, Magpie and Chatterbox. Ming is also exposed on Android behind an off-by-default flag.
Engine sharing rules (one C ABI, one bridge, one dylib per SDK family) are in
[docs/desktop-engine-sharing.md](desktop-engine-sharing.md).
Android's other ASR/TTS families, camera OCR, MediaProjection capture, TV pairing
and Android-specific screens have not been presented as desktop implementations.
On macOS 13.3+, choose **Mac audio** in Captions to caption streams/videos through
ScreenCaptureKit, or choose Microphone/virtual input. The first live Start may
require Screen & System Audio Recording permission. No screen/video output or
microphone is attached by the system-audio helper. The owner must still validate
live capture and protected-content behavior; the noncapturing probe proves only
helper/framework packaging. An OBS native plugin remains separate; the Browser
Source and external-app PCM API work without one.

The floating toolbar uses the Hearth icon; click it to bring the main window back
to Overlay settings. Diagnostic `--overlay-preview` windows are explicitly marked
as sample captions and use focused-window shortcuts only, so they cannot take
the real application's system shortcut registrations. Close diagnostic previews
and restart an older running app after replacing its packaged build.

Mac builds now compile Metal by default for Whisper and GGUF translation;
`--no-metal` creates an explicit CPU build. Both engines expose CPU/Metal choices
only when supported by their actual native adapter and device. Fresh routes and
older/imported setups without a compute choice prefer an available GPU; explicit
saved CPU/GPU choices remain intact. The resolved choice is saved before a session starts. Qwen3-ASR,
CTranslate2 and local TTS adapters currently use CPU. Qwen3-ASR is macOS arm64 only.

Live speech uses the displayed four-second Standard window for every engine,
or the explicitly selected window. A 600 ms trailing pause can finish a shorter
utterance. Only 200 ms of leading silence is retained, with caption timestamps
still referring to the original audio. File transcription retains its longer
15/20-second defaults and all audio samples. Whisper live sessions use one greedy
decode with a bounded token budget and remember the first detected language;
file Auto mode continues detecting each window. A live token-limit failure is
reported instead of publishing a truncated final. Qwen Auto results retain the
detected language and normalize it to a source code for translation.

Installers bundle Java 21 while shared bytecode and Android remain on Java 17.
On the tested Apple M4 Pro, Java 21 enabled hardware SHA hashing and verified the
same 756 MB model file in about 0.5 seconds versus 3.3 seconds on Java 17, with
identical hashes. Native extraction also verifies only each requested library and
its dependencies, avoiding repeated full-bundle scans during startup.

The [October 22 hardware receipt](research/desktop-metal-inference-2026-10-22.json)
records real Whisper and English→Arabic GGUF inference on an Apple M4 Pro,
positive GPU model allocations, all 25 translation layers offloaded, completed
inference counters and CPU/GPU outputs. The packaged launcher can repeat this
with `scripts/desktop/validate-gpu.py --backend metal` and explicit model/WAV paths.
These single-fixture timings do not establish broad quality or performance.

The [24 October performance handover](handoff/desktop-speech-performance-2026-10-24.md)
records the restored Metal default, live buffering and startup fixes, actual
Turbo/Qwen/Nemotron timings, and their remaining latency/quality limits.

In **Models**, the hardware menu combines with stage, search and installed filters.
Choose **GPU available here** for the current computer, **All GPU paths** for the
implemented adapters, or **Apple Metal**, **NVIDIA CUDA** and **CPU only** separately.
A path for another OS does not claim availability on this device. Hardware filters
persist in DataStore and do not change pipeline selections. Android's full speech
and translation libraries similarly offer Vulkan, Adreno OpenCL and CPU-only
filters; Android GPU labels require a compatible ARM64 device/driver and do not
claim that the selected model has already passed device inference.

An opt-in [Windows NVIDIA CUDA build and validation path](desktop-cuda.md) now
covers these same two adapters, explicit device indices and verified app-local
CUDA redistributables. **NVIDIA hardware execution is pending the owner's local
Windows run.** Mac results and mocked Windows loader tests do not establish it.
An explicit GPU request fails if initialization, model placement or inference
fails; it cannot silently become CPU success. Bundle metadata records the actual
compiled native export per binary and checksum, including across partial builds.

A separate [Supertonic smoke receipt](research/desktop-supertonic-smoke-2026-10-21.json)
records real Arabic and English F1/M1 synthesis through the shared pipeline with
one resident CPU engine. All four cases produced non-silent 44.1 kHz PCM WAVs.
No audio was played, so pronunciation and preferred voices still need owner
listening evaluation. Opt in with `HEARTH_SUPERTONIC_SMOKE_ROOT` pointing to a
complete pinned Supertonic directory when running `:desktop-runtime:test`;
`HEARTH_SUPERTONIC_SMOKE_RECEIPT` optionally saves a fresh receipt. The test never
downloads models or calls a provider.

When combining cloud recognition with local EuroLLM/Riva translation, choose an
explicit source language. The generic cloud recognition route does not currently
propagate detected language; local Whisper does, and Qwen3-ASR does when an explicit
source language is selected (its Auto mode leaves the detected label unknown).

Native video/audio decoding uses AVFoundation on macOS and Media Foundation on
Windows. It reads the first audio track and produces bounded PCM for the speech
pipeline; there is no video editor or FFmpeg dependency. OS codec support varies,
and unsupported input fails with the actual decoder error. Local file decoding
is bounded to one hour; canonical WAV can stream up to two hours. HTTP transport
limits are documented in the integration module.

## Owner test flow

1. Download/import a speech model and a translation model, then choose **Use** for
   each. Switching engines retains their previous settings. Downloads have separate
   queues from imports and remain visible while navigating.
2. Pick the same saved source/output languages from Home, Captions, Translate or
   Settings. QuickMT English→Arabic needs explicit English input and Arabic output;
   the opposite direction needs its matching checkpoint. Output cannot be Auto.
3. On Home, **Input: Mac audio** opens Captions to inspect/change the source.
   **Start live captions** starts the selected source and shows the floating overlay.
   For typed text, use Translate; it has its own editable language pair backed by
   the same saved configuration and does not start an audio capture.
4. On macOS, grant the requested recording permission yourself if prompted. Start
   a YouTube/stream playback and test live captions. **Finish** drains speech normally;
   Stop cancels. Native permission/capture errors remain visible. Changes to active
   engine settings apply to the next session.

See [owner-test repairs and evidence](handoff/desktop-owner-feedback-2026-10-22.md).

## Floating captions

Drag the caption text or the grip to move the window. Drag an edge or corner to
resize it. Hover to reveal the compact toolbar; move away for subtitles only.
The **⋯** button opens a small control window with **Toolbar size** (75–150%),
**Area height** (90–720 logical points), and optional hover-area shading. The
control window remains usable when the caption window is very short. The same
choices are on the Overlay page, alongside display, position, width, font,
line limit, alignment, background opacity and subtitle style.

Position, size, display and appearance use the existing desktop DataStore.
Changing width preserves the dragged window's center; changing height preserves
its bottom edge. Disconnected-display positions are clamped to a current work
area when the overlay opens. **Reset position** unlocks and recenters the window
at the bottom while retaining caption appearance and language preferences.

| Action | macOS | Windows |
| --- | --- | --- |
| Show/hide | Command+Option+O | Ctrl+Alt+O |
| Click-through/unlock | Command+Option+L | Ctrl+Alt+L |
| Larger/smaller text | Command+Option+Up/Down | Ctrl+Alt+Up/Down |
| Reset position | Command+Option+R | Ctrl+Alt+R |
| Show/hide original line | Command+Option+T | Ctrl+Alt+T |
| Cycle style | Command+Option+S | Ctrl+Alt+S |

When the caption window has focus, arrows move it, Shift increases the movement
step, Alt+arrows resize it, and Escape hides it. System hotkeys can be disabled
on the Overlay page; the focused-app fallback remains registered. Hotkey
registration conflicts and native window errors are shown instead of claiming
success. Click-through can also be disabled from the main Overlay page.
The OS bridge registers these specific chords; it installs no keyboard hook.

[Implementation, checks and remaining platform verification](handoff/desktop-overlay-2026-10-23.md).

## Build and run

Use JDK 17 for compilation and JDK 21 for installer packaging, Python 3,
CMake 3.22 or newer, and Rust 1.99 (Ming). `hearthDesktopOnly=true`
avoids requiring an Android SDK for a desktop build. Without that property, the
ordinary Android project and all its checks remain enabled.

```sh
python3 scripts/desktop/prepare-dependencies.py
python3 scripts/desktop/build-native.py --stage all
bash scripts/desktop/build-media.sh
./gradlew -PhearthDesktopOnly=true :desktopApp:run
```

`--stage all` now provisions NeMo/llama.cpp and builds Magpie on macOS arm64.
Chatterbox and Magpie are experimental, not live-readers. The final acceptance
matrix is in [the final handoff](handoff/desktop-final-2026-10-22.md).

On Windows, run `python` and `scripts/desktop/build-media.ps1` instead of the Bash
media script. The official host SDK directory is
`build/desktop-dependencies/onnxruntime-win-x64-1.20.1`. The C++ build needs Visual
Studio C++ tools and a Windows SDK. Intel Macs use the `osx-x86_64` SDK. Native
builds are pinned to the same Whisper/llama/sherpa-onnx sources as Android and
the same ONNX Runtime 1.20.1 host SDK. Preparation checks exact SDK archive hashes and the extracted content.
It downloads build runtimes/source only, never model weights.

After the Windows JNI build, run `python scripts/desktop/stage-windows-crt.py`
before Gradle. This required packaging step discovers a complete release Visual
Studio installation with `vswhere`, takes only the four x64 VC143/VC145 runtime
DLLs from its approved `VC/Redist/MSVC` directory, and verifies Microsoft's
Authenticode signatures and their PE dependency closure. It records their hashes,
versions, signature certificates and redistribution terms in the native manifest
and packaged notices, together with locally installed license files when present.
It never copies DLLs from `PATH` or `System32`, or downloads a redistributable.
Redistribution requires a valid Visual Studio license and follows Microsoft's
[redistribution terms](https://learn.microsoft.com/en-us/cpp/windows/redistributing-visual-cpp-files).
The native media helper and Hearth's JNI adapters use the static MSVC runtime;
the app-local DLLs serve the official ONNX Runtime dependency on clean Windows
10/11 systems. CI runs this step before the actual packaged-launcher self-check.

```sh
./gradlew -PhearthDesktopOnly=true :hearth-core:desktopTest \
  :desktop-runtime:test :desktop-data:test :desktop-media:test \
  :desktop-integration:test :desktopApp:desktopTest
./gradlew -PhearthDesktopOnly=true :desktopApp:packageDmg        # macOS host
./gradlew -PhearthDesktopOnly=true :desktopApp:packageMsi :desktopApp:packageExe  # Windows host
```

Validate the actual packaged launcher with `python3 scripts/desktop/check-packaged.py`.
The `--self-check <report-file>` entry opens no window/microphone/model; it checks
packaged native adapters, audio/crypto services and authenticated loopback HTTP.

The current desktop installer version is `1.0.4`, independent of Android's version;
macOS installer versions require a nonzero major. Use
`-PhearthDesktopVersion=MAJOR.MINOR.PATCH` to change it. Builds target Windows x64,
macOS Apple Silicon and macOS Intel, but this branch's native artifacts and real
proofs are **macOS arm64 only**; Intel/Windows are build-wired, not staged/proven.
macOS minimum deployment is 13.3 (the pinned ONNX Runtime requirement). Signing and
notarization identities are not checked into the repository; local/CI installers
are development builds; the verified Apple Silicon app is ad hoc signed and not
notarized. Matching
host CI builds and tests each architecture; configuring a Windows installer on a
Mac does not cross-build it.

macOS and Windows packages use the existing Hearth artwork through explicit
ICNS/ICO assets in `desktopApp/packaging/icons`. Regenerate them on macOS with
`python3 scripts/desktop/generate-icons.py` (Apple's `sips`/`iconutil`; no new
artwork or runtime dependency). The macOS packaged check verifies that the icon
referenced by Info.plist matches the Hearth asset, preventing a default Java icon.

The native libraries and required licenses are packaged inside the runtime JAR,
extracted to an application-private hash-addressed cache and verified before
loading. Opaque `.payload` resource names preserve original native bytes through
macOS JAR processing; extraction restores the original filenames and verifies
the original manifest hashes. Models are separate. An explicit `-Dhearth.native.dir=...` can point to a
locally built native bundle. `HEARTH_DATA_DIRECTORY` selects a separate desktop
profile for development; otherwise settings/models use Application Support on
macOS or LocalAppData on Windows. Provider keys use credential references; an
`env:VARIABLE_NAME` reference resolves an explicit environment variable.

## Shared execution and ownership

`hearth-core` targets Android and desktop JVM. Its `PipelineService`,
`PipelineRequest`, `PipelineSession` and `PipelineEngines` contracts expose
independent speech, translation and voice selections. A session snapshots its
configuration. One heavy session runs and at most four are admitted; queued jobs
can be cancelled without touching another owner's native handle.

Recognition publishes original segments immediately. One ordered downstream
worker translates/synthesizes finalized segments, with at most eight pending
finals. File input may wait for downstream capacity; live capture fails visibly
when that bound is exhausted. Capture has its own bounded PCM queue and no
implicit Flow prefetch queue. Cancellation joins capture, inference, provider
transport and child workers before reporting completion. Final IDs are processed
once, and a final rewritten by an engine is an error. Partial text never silently
becomes completed transcription.

| Module | Responsibility |
| --- | --- |
| `hearth-core` | Portable stage/configuration contracts, bounded orchestration, ordered events and Unicode translation splitting. |
| `desktop-runtime` | Owned native/cloud/system engines, model integrity, PCM file/capture/playback adapters and actual errors. Shared Android-free article SDK sources are compiled directly. |
| `desktop-media` | Platform-native media decoder and macOS Security.framework helper; no Android dependency. Secrets pass only through pipes, never command-line arguments. |
| `desktop-data` | DataStore, credential references/OS storage, portable setup validation, derived model catalog and isolated pinned downloader. |
| `desktop-integration` | Optional loopback HTTP/SSE/PCM/OBS interface over the same pipeline; owns only sessions it creates. |
| `desktopApp` | Desktop workspace, model/provider setup, exports, floating overlay and host installers. |

A runtime adapter must release or cache its own handles deliberately. Cached
translation models stay resident across chunks; cancelling/failing invalidates the
relevant cached owner. Closing desktop services joins jobs before closing native
caches. Native wrappers retain `com.sal7one.common_jni` and isolate the Whisper, llama GGML,
sherpa-onnx and speech-backend symbol spaces in separate libraries. The TranscribePsy
and Nemotron backends share one lease-based bridge and are loaded from hash-verified dylibs. Android sources and ABIs are not
rewritten to accomplish the desktop ports.

## Models and setups

`scripts/desktop/generate-catalog.py` derives desktop pins from the existing
Android Whisper/Supertonic catalogs, reviewed translation cards, the audited
Qwen3-ASR release archive and engine-owned Qwen file pins, and the reviewed
multi-component CTranslate2 catalog (`Ct2Catalog.kt`). Run it after
changing those sources; `--check` rejects drift. A download must finish size/hash
verification before it can be selected. Installing does not activate a stage.
A missing, changed or incompatible import remains a visible setup problem.

Exported desktop JSON uses `hearth.desktop.setup` schema 1. It contains model IDs,
languages and safe engine options. It excludes provider secrets, credential
references and machine-specific model paths. Imported routes need models/keys
resolved on the receiving machine before running. Provider URLs cannot embed
credentials, query strings or fragments. Android's existing portable setup format
remains unchanged; a cross-format converter is not implied.

For OBS and API endpoints, payloads, frame bounds, authentication, read-only URLs
and cancellation behavior, see [desktop integration](../desktop-integration/README.md).
