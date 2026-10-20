# Desktop KMP handover — 21 October 2026

## Owner instruction and stopping point

The owner explicitly stopped this agent and will use another agent to continue.
**Do not resume work automatically.** The owner rejected the desktop UI after
seeing real offscreen screenshots. Treat its visual design and interaction design
as unfinished, even though its controllers and presentation tests pass. No UI
redesign was attempted after that feedback.

The requested product is Hearth for Windows and macOS using KMP, reusing portable
code while preserving Android. It must accept audio, footage or text; expose the
same independent STT/translation/TTS pipeline to other apps; provide captions and
an OBS integration. This implementation is a functional first desktop port, not
complete Android feature/model parity.

## Repository and delivery state

- Repository: `/Users/salehalanazi/AndroidStudioProjects/Hearth`.
- Branch: `codex/hearth-desktop-kmp`.
- Main/base: `3bb02ed95096e859c93e5eb6ff779a6c7e69c6ea`.
- Main implementation commit: `13429ddbfe48ed78c3c2e4ec53bcc199b55bd582`
  (96 files, approximately 8,945 added lines).
- Draft PR: <https://github.com/Sal7one/Hearth/pull/4>. Attached to the Codex task.
- A final wrap-up commit preserves the prepared Windows PowerShell fix and this
  handover. Its `[skip ci]` marker deliberately avoids launching another CI cycle
  after the owner's stop request. That fix is **not Windows-validated**.
- No desktop release, desktop Git tag, official publication or merge into main
  has happened. Android's existing official `v0.27.3` release remains separate.
- Desktop installer version is `1.0.0`; the intended future draft tag was
  `desktop-v1.0.0-preview.1`. This was only a delivery plan.
- All collaborating agents stopped. No agent-owned local build, rendering process
  or mounted DMG remains. Previously launched GitHub jobs can finish independently;
  no continued monitoring or automation was scheduled.

Read root `AGENTS.md`, `docs/desktop.md` and `desktop-integration/README.md` first.
The existing `docs/handoff/README.md` describes Android model expansion; this
file is the current desktop-specific checkpoint.

## What is implemented

| Module/files | Responsibility and boundaries |
| --- | --- |
| `hearth-core/src/commonMain/.../PipelineContract.kt` | Portable independent stage selections, input/session/output contracts and capability descriptors. Android and JVM desktop targets. |
| `hearth-core/.../HearthPipeline.kt` | Session snapshots, one active heavy job, at most four admitted jobs, ordered captions/events, exactly-once finals, an eight-final downstream queue, visible overload and joined cancellation. |
| `hearth-core/.../TextChunks.kt` | Extracted Unicode/sentence splitting. Existing Android `common-jni/.../translation/TextTranslationChunks.kt` delegates to it without changing behavior. This is the only existing Android production Kotlin behavior touched. |
| `desktop-runtime` | Actual Whisper, llama.cpp GGUF translation and Supertonic native owners; compatible cloud STT/MT/TTS; installed system voices; JavaSound capture and PCM conversion; strict model/native integrity; resident MT/TTS caches. |
| `desktop-media` | AVFoundation on Mac, Media Foundation on Windows, first-audio-track decoding to bounded PCM WAV. macOS Security.framework credential helper. No FFmpeg or editor. |
| `desktop-data` | DataStore settings, portable setups, OS credentials, catalog derivation, exact-size/SHA downloads and verified imports. |
| `desktop-integration` | Optional authenticated loopback HTTP jobs, bounded PCM streaming, SSE, cancellation/audio downloads, uploaded media and a separate read-only OBS Browser Source capability. |
| `desktopApp` | Compose workspace/model/provider/integration screens; independent stage controls; floating captions; file/text/audio outputs; settings and installer entry points. UI rejected by owner. |
| `scripts/desktop` | Pinned native dependencies/builds, model catalog generation, media helpers, Windows CRT staging and actual packaged-launcher validation. |
| `.github/workflows/desktop.yml` | Apple Silicon Mac, Intel Mac and Windows x64 builds, tests, app images, packaged startup/media checks and installers. |

Kotlin is 2.2.0; Compose Multiplatform is 1.9.0. Existing Android AGP/flavors/JNI
packages remain intact. `-PhearthDesktopOnly=true` excludes Android projects so a
desktop host does not need an Android SDK. Root settings includes the six new
modules; desktop code is not folded into Android screens/services.

### Engine coverage and honest limits

- Local STT: pinned whisper.cpp formats. Multilingual models support Arabic and
  English; `.en` models are English-only. Explicit Arabic with an English-only
  model fails. Live recognition is bounded utterance-windowed, not token-level
  acoustic-cache streaming.
- Local MT: existing Hearth llama.cpp prompt/runtime protocol with EuroLLM/Riva
  cards and explicit verified GGUF configuration.
- Local TTS: shared Android-free article SDK and Supertonic 3 source, ten styles
  F1–F5/M1–M5, with one resident verified engine reused across captions.
- macOS Whisper/MT: explicit CPU or real Metal, actual placement checked; no silent
  CPU fallback after selecting GPU. Supertonic uses CPU.
- Windows: native inference is CPU. **No NVIDIA CUDA or Windows GPU backend.**
- Other Android STT/TTS families, camera OCR, automatic system-audio capture and a
  native OBS plugin are not desktop implementations. A virtual audio input or
  external-app PCM feed is available; OBS has a Browser Source/API integration.
- Catalog has 30 file/package choices, not 30 distinct families. Whisper quants
  are grouped file options in the UI.
- Cloud recognition with source `auto` does not propagate a detected language;
  choose an explicit source language before local EuroLLM/Riva MT. Local Whisper
  does propagate its detected language.
- Installers are unsigned previews; no signing/notarization identity was added.
  Mac minimum is 13.3. Review x64 AVX2 requirements before publishing installers.

## UI rejection and redesign entry points

The screenshots were actual Compose UI rendered offscreen from unchanged
`13429dd`, not mockups. They used a clean, unconfigured temporary profile, real
catalog and engine capabilities, without a live window, microphone, provider key,
server or synthetic successful caption. The Mac was locked; the owner allowed
skipping live inspection, then requested screenshots. After seeing them, the owner
rejected the UI and stopped work.

Final reviewed screenshots are ignored local files:

- `build/desktop-screenshots/01-workspace.png`
- `build/desktop-screenshots/02-models.png`
- `build/desktop-screenshots/03-integration.png`
- `build/desktop-screenshots/provenance.json` and `README.txt` record source/image
  hashes and capture boundaries.

`04-connections.png` may exist from an already-started render. It was not reviewed
or included in the finalized provenance; do not present it as reviewed.

The reproducible temporary harness is
`build/offscreen-screenshots/src/com/sal7one/hearth/desktop/DesktopOffscreenScreenshotTest.kt`,
with its Gradle init script in that directory and log
`build/desktop-offscreen-screenshots.log`. It adds an ignored test source directory
only for that invocation and uses Compose `ImageComposeScene` plus scene-local
pointer input to navigate the real app. No production screenshot CLI was added.
Inspect the existing harness/init files rather than inventing a new mock UI.

Start a redesign in these production files:

- `desktopApp/src/desktopMain/kotlin/com/sal7one/hearth/desktop/HearthDesktopApp.kt`
  — shell, navigation, application lifetime and collected state.
- `WorkspaceUi.kt` — stage cards, input/results and caption controls.
- `ModelProviderUi.kt` — grouped model catalog, search, transfers, provider paths,
  credentials, setup import/export and advanced controls.
- `IntegrationUi.kt` — local API/OBS start/stop, tokens and diagnostics.
- `DesktopTheme.kt`, `CaptionOverlayWindow.kt` — styling and caption window.
- `DesktopPresentation.kt`, `DesktopWorkspaceController.kt`,
  `DesktopModelController.kt` — reusable logic; preserve ownership/validation while
  replacing presentation.

The owner likes Android's existing appearance but wants fewer choices/clicks,
simple bundles and genuinely advanced controls kept out of basic flows. The
current desktop has large technical forms/cards and implementation-oriented copy
in primary flows. Do not treat the brown palette, sidebar, card layout or current
information density as accepted requirements. Get an early actual render before
investing in more presentation. Preserve independent STT/MT/TTS choices, DataStore
preferences, explicit model activation, verified imports/downloads and the choice
to show original captions while translation is pending.

## CI state at stopping time — not all green

Desktop PR run: <https://github.com/Sal7one/Hearth/actions/runs/39498483433>

| Job at `13429dd` | Last observed outcome |
| --- | --- |
| Apple Silicon `macos-15`, job `118889610941` | **Success**, including native builds, desktop tests, actual packaged launcher/media checks, DMG and artifact upload. |
| Intel `macos-15-intel`, job `118889610455` | Native builds passed; desktop regression tests/application-image step was running at final snapshot. Read its eventual result; do not assume success. |
| Windows, job `118889611219` | Native compilation passed. Failed at `Stage verified app-local Microsoft runtime`; no Windows app/installer validation completed. |

Android PR run: <https://github.com/Sal7one/Hearth/actions/runs/39498483393>
**Failed** inside the broad root `./gradlew test ...` invocation, in
`:desktop-integration:test`, not an observed Android compiler failure:

```text
DesktopIntegrationServerTest > malformedAndOversizedUploadsDoNotStartPipelineWork FAILED
    java.net.SocketException at DesktopIntegrationServerTest.kt:320
17 tests completed, 1 failed
```

Full downloaded failed-step log: `build/desktop-android-ci-failed.log`. The same
17 integration tests passed locally and in ARM Mac CI. Investigate the HTTP
oversized-upload response/write/close behavior on Linux; do not call this a harmless
flake without evidence or weaken payload limits. Check the CI XML/report artifact
for the full stack. The task failure prevented the entire Android CI workflow
from becoming green, even though local Android checks passed.

Native ABI run <https://github.com/Sal7one/Hearth/actions/runs/39498405196> was
still in progress at the last earlier snapshot. Inspect its eventual result. The
duplicate push Android/desktop runs were intentionally cancelled in favor of PR
runs. No CI check has been waived as complete.

### Prepared Windows CRT fix

Official ONNX Runtime 1.20.1 imports four Microsoft CRT DLLs that a clean PC may
lack. Own JNI targets and media helper use static `/MT`; ORT itself does not.

- `stage-windows-crt.py` discovers an approved installed Visual Studio x64 release
  Redist/MSVC VC143/VC145 directory, verifies Microsoft Authenticode, checks PE
  dependency closure and stages the four exact DLLs. No arbitrary PATH/System32
  copy and no unsigned fallback.
- It adds original byte hashes, signature/version/import provenance and notices
  to the native manifest. The loader verifies every dependency before loading
  VCRUNTIME140, VCRUNTIME140_1, MSVCP140, MSVCP140_1, then ORT.
- Actual CI failure was PowerShell host/module mismatch: pwsh7 exported
  `PSModulePath`, but the script launched Windows PowerShell5.1, whose Security
  module autoload failed before signature verification.
- The preserved fix prefers installed `pwsh.exe`, removes inherited
  `PSModulePath`, explicitly imports the matching Security module, and uses UTF-8
  pipes. Seven Python tests pass, including the host/environment regression.
- **No Windows rerun validated this fix before stopping.** Resume with a run at
  the actual new branch head, not a rerun of the old commit.
- Follow-up risk, not a proven failure: desktop system speech and DPAPI helpers
  also invoke `powershell.exe`. Inspect their inherited module environment and
  test actual Windows credential round trips/voice enumeration before claiming
  clean-PC readiness. No extra change was made after the stop request.

## Local validation already performed

- Required `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin`
  passed repeatedly, most recently together with real Supertonic inference in
  9 seconds (incremental). Each of six Android app test variants has 692 tests,
  with expected Play/Foss skips and no local failures.
- `:common-jni:externalNativeBuildDebug` passed all four Android ABIs.
- Common and speech native host suites passed with Homebrew LLVM22 ASan/UBSan.
  Apple Clang17 ASan hung before main on macOS26.5; sample in
  `build/desktop-host-stall.sample.txt`. Use
  `/opt/homebrew/opt/llvm/bin/clang++` for this host, not the broken Apple sanitizer.
- Focused desktop suites: core10, data4, integration17, media1, UI15. Runtime now
  has 24 tests: normally 22 pass with two opt-in model tests skipped. The last
  local real-Supertonic invocation passed 23 with only the STT/MT real-model test
  skipped. No owner device, microphone, provider or audio playback was used.
- Logs include `build/desktop-final-commit-gate.log`,
  `desktop-supertonic-real-smoke.log`, `desktop-common-host.log`,
  `desktop-speech-host.log`, `desktop-delivery-final-13429dd.log` and
  `desktop-delivery-final-self-check.log`.

Committed real-model evidence:

1. `docs/research/desktop-real-smoke-2026-10-20.json`: pinned Whisper tiny.en Q5_1,
   EuroLLM1.7B Q4 and an installed English system voice complete the shared
   pipeline on M4 Pro; actual Metal MT placement is 25/25 layers. Arabic-to-English
   CPU/Metal fixtures also ran. One fixture is not a broad speed/quality claim.
2. `docs/research/desktop-supertonic-smoke-2026-10-21.json`: all 16 package pins
   verified; real Arabic/English F1/M1 synthesis completes through the shared
   pipeline with one resident CPU engine. All outputs were non-silent 44.1kHz WAVs.
   No pronunciation/listening evaluation was done.

## Packaging and integrity details to preserve

Compose1.9 macOS JAR processing ad-hoc signs raw `.dylib` entries even when signing
is disabled, changing their bytes. The first packaged self-check correctly
rejected those changed hashes. This was fixed with opaque `.payload` resource
names. Manifest hashes remain the original native bytes; extraction restores the
original names and verifies them. Do not remove integrity checks or repin mutated
packaged binaries to bypass the problem.

Current Mac native source-set hash:
`92f7eae2a156fa254345de9e0f886729f737fa41dc35213a1d048fa48d8a003a`.
Whisper tree pin: `5f3464bc06c04426d66eae69e5e510d32fb76621b524c42ab25b4294f035a920`.
llama pin: `64e9bceb2c3a856efed96feda784a50947049feb`. ONNX1.20.1 SDK archive
pins are in `scripts/desktop/prepare-dependencies.py`.

`DesktopSelfCheck.kt` executes before any Compose window when passed
`--self-check <report>`. It loads strict-hash native libraries, actually runs the
media helper on a small WAV, checks audio/EC crypto services, and exercises an
authenticated localhost request. It uses no models/microphone/remote provider.
`scripts/desktop/check-packaged.py` launches the actual Jpackage app/JRE.

Final local Mac DMG from implementation commit `13429dd`:

- `desktopApp/build/compose/binaries/main/dmg/Hearth-1.0.0.dmg`
- 81,312,533 bytes; SHA256
  `de1a02763b1759ed738e9c1f6840a91135cdd8ee0b0f04a97eeb1578db4a3d83`.
- `build/desktop-delivery-provenance.json` verifies source/native hashes and the
  read-only-mounted actual launcher/media/API checks with a fresh profile.
- This DMG predates the final Python-only wrap-up commit; do not claim its source
  is the new head. Production Mac binaries were unchanged by that Python fix.

Apple Silicon CI artifact `12298918311`, `hearth-desktop-macos-15`, is downloaded
as `build/releases/desktop-preview/ci/macos-arm64.zip`. Its SHA256 matches the
GitHub artifact digest:
`2cc03b918890f6d570cde6965d50763929bbe1f2a2ba3eabcb92cd5ce24eb3c6`.
It has not been extracted/released by this agent. Windows' small failure artifact
is diagnostic, not an installer.

Prepared but unpublished release text: `build/desktop-release-notes.md`; PR text:
`build/desktop-pr-body.md`. Review all validation claims before reusing either.
The release text anticipates three installers and all-green validation, which has
**not happened**. Do not publish it unchanged now. The intended asset grouping is
one recommended installer per OS/architecture plus `SHA256SUMS.txt`; the owner
previously disliked confusing releases with many duplicate assets.

## Commands and environment for the next agent

Shell default Java is 26; use a full JDK17 explicitly:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./gradlew -PhearthDesktopOnly=true :hearth-core:desktopTest \
  :desktop-runtime:test :desktop-data:test :desktop-media:test \
  :desktop-integration:test :desktopApp:desktopTest
python3 scripts/desktop/generate-catalog.py --check
python3 -m unittest discover -s scripts/desktop -p 'test_*.py'
```

Desktop packaging selects a full JDK17 toolchain rather than Android Studio's
Gradle-daemon JBR, which may lack `jpackage`. `hearthDesktopJavaHome` overrides
only packaging; do not change Android's daemon/toolchains to fix packaging.

Build native dependencies/helpers before requiring their tests. Full commands
and Windows CRT staging are in `docs/desktop.md`. `-PhearthRequireMedia=true`
turns missing media resources into a failure rather than a skipped native test.
Opt-in model tests require explicitly supplied paths/environment variables; when
changing only the opt-in environment, clear `:desktop-runtime:cleanTest` so an
up-to-date Gradle test result cannot mask a skipped run. No test downloads weights.

Root constraints still apply: required Android checks before commits, native host
and externalNativeBuildDebug for relevant changes, and both QA variants plus
`verify-release.py` before Android APK delivery. No adb/owner device control. Keep
`com.sal7one.common_jni`, bounded queues, exact-once finals, strict ZIP/model
integrity and FOSS network gates. No FFmpeg/editor/books/sign recognition/yt-dlp.
Never commit keys, signing material, local.properties, native binaries or weights.

Local disk had about 2.4GiB free. Task-owned Supertonic fixture weights (~400MB) are
ignored under `build/desktop-supertonic-smoke/models`; the downloaded EuroLLM
smoke weight was removed after recording its verified receipt. Pre-existing
Whisper fixture weights remain untouched. No model/native binaries were committed.

## Recommended continuation once the owner resumes

1. Read this handover and current Git/CI state. Do not assume old run outcomes or
   screenshots describe a redesigned UI or the latest branch head.
2. Redesign the rejected UI with an early actual offscreen render. Reuse tested
   controllers; keep basic flows simple and advanced details behind disclosure.
3. Validate the preserved Windows CRT host fix in current-head CI. Investigate the
   Linux integration SocketException and make the full Android gate green.
4. Finish Intel/Windows packaged execution, real media, credential/system-voice
   checks and installation validation. Preserve strict pins and surface errors.
5. Decide desktop parity scope with the owner. NVIDIA, remaining model families,
   system-audio capture, OCR and a native OBS plugin remain substantial work.
6. Only after the owner accepts the UI and required checks pass, arrange the
   GitHub draft release/tag and any merge. Do not present this checkpoint as a
   completed migration or a production desktop release.
