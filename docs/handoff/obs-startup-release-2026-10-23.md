# OBS, saved desktop setup and combined source finalization — 2026-10-23

Release subject: `v0.30.0-rc.7`, PR [#4](https://github.com/Sal7one/Hearth/pull/4)
into `main`. Android is **0.30.0 / code 103**, desktop is **1.0.2**.
Android keeps the existing QA application ID and signing certificate. RC6 was a
source checkpoint only, with no GitHub release/assets; code 103 was not previously
delivered. Release binaries are separate assets, including all ten Android APKs.
There is no combined APK or validation ZIP.

## What changed

- Desktop startup retains the saved engine, model path, input and language
  configuration. Pinned verification is an explicit temporary **Checking saved
  setup** state instead of a false **Finish setup** failure. The selected models
  are audited before unrelated catalog entries. The app becomes ready when the
  selected files pass, without requiring another **Use** action.
- Verification proofs and errors belong to each normalized model ID/path pair.
  Importing a second copy does not activate it, invalidate the selected first
  copy, or hide a failed verification. Route changes during an audit queue a new
  check; obsolete results cannot replace the newer route's state. Paused managed
  downloads do not block a verified external copy. Real missing files/hash
  failures remain errors; file existence alone never proves readiness.
- Integrations has an illustrated four-step OBS guide, working **Copy OBS URL**,
  **Preview in browser**, and **Open Captions** actions, separate service/session
  status, and expandable troubleshooting. The copy action includes the complete
  private fragment. OBS receives read-only captions; the write API token is a
  separate credential. Developer inputs stay under Advanced.
- [OBS instructions](../obs-setup.md) explain Browser Source dimensions, layer
  order, microphone/system audio, translation, transparent background, and fresh
  links after restarting the service. OBS must run on the same computer.
- Generated [OBS artwork and exact prompt](../artwork/obs-integration.md) are
  bundled offline. Narrow/light and wide/dark UI layouts have render coverage.
- The source also contains the preceding Android Screen translation chooser,
  artwork, saved Websites/apps versus Japanese manga workflow and corrected
  Quick Settings/legacy routing. Desktop adds settled-region image translation,
  click-through output, an image API and an unpacked browser text extension.
  See the [preceding implementation record](screen-translation-final-2026-10-23.md)
  for scope and acceptance limits; its RC6/package version is superseded here.
- Packaging now publishes each Play/FOSS ARM64, ARMv7, x86, x86-64 and Universal
  QA APK independently, alongside the Mac DMG, extension, checksums and individual
  validation receipts. The browser extension ZIP and aria2 corresponding-source
  tarball are purpose-specific packages, not combined release bundles.

## Local verification

No GitHub Actions dispatch, run listing or polling was used. No Android device
was driven. The owner performs phone and TV acceptance.

The final combined local gate passed in 1m 11s (521 tasks):

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin \
  :app:assemblePlayQa :app:assembleFossQa \
  :hearth-core:desktopTest :desktop-runtime:test :desktop-data:test \
  :desktop-media:test :desktop-integration:test :desktopApp:desktopTest \
  :desktopApp:createDistributable :desktopApp:packageDmg
```

The exact six-suite `-PhearthDesktopOnly=true` gate also passed. Native helpers
were built before desktop verification. No new native source changed in this
follow-up.

| Suite | Cases | Failures/errors | Intentional skips |
| --- | ---: | ---: | ---: |
| Each Play Debug/QA/Release app unit suite | 901 | 0 | 3 |
| Each FOSS Debug/QA/Release app unit suite | 901 | 0 | 15 |
| hearth-core desktop | 42 | 0 | 0 |
| desktop-runtime | 93 | 0 | 32 |
| desktop-data | 30 | 0 | 1 |
| desktop-media | 9 | 0 | 0 |
| desktop-integration | 27 | 0 | 0 |
| desktopApp | 115 | 0 | 3 |

The 316 desktop cases include 36 opt-in/fixture skips; they are not all real-model
inference. Focused startup/model/concurrency coverage passed 20 cases, including
real DataStore close/reopen, multiple copies, paused transfers, route races and
failed integrity. Three OBS contract cases and seven screen render cases passed;
eight OBS render fixtures were inspected. Extension execution/transport tests
passed 14 cases plus three syntax checks. Seven packaging cases pass, covering
all ten standalone APKs, checksums, individual evidence and unsafe input rejection.

All ten APKs pass `scripts/verify-release.py`: identity/signature, ABI/native
linkage, runtime pins, exported-component and FOSS-permission checks. Play and
FOSS ARM64 upgrade receipts match the previous code 102 QA signer at code 103.
Models are not bundled. The ARM64 editions retain the complete local TTS/GPU
plugin paths; unsupported architectures do not claim those engines.

The actual **1.0.2 DMG** passed checksum verification, read-only mounting,
deep/strict ad hoc code-signature checks and `check-packaged.py` against its
mounted application. That launches the bundled JRE/application and checks native
adapters, audio/crypto services and the authenticated loopback API. The DMG was
detached successfully. It is not Developer ID signed/notarized.

Actual packaged Metal inference passed on the Apple M4 Pro: Whisper allocated GPU
model buffers and transcribed the pinned fixture; GGUF translation offloaded
**25/25 layers** and returned Arabic. CPU paths were also executed. This receipt
proves these two engine paths, not Metal support for every family/OCR. NVIDIA
execution and Windows installers still need the owner's Windows machine.

The preceding dedicated pinned Japanese manga inference passed two tests and
10/10 fixture regions: first outline ~151 ms, first text ~7.8 s and quality
cleanup ~28.6 s after ~7.3 s loading. Quality mode remains a CPU still-page
workload, not video-frame OCR. No private fixture or model weights are released.

Evidence is staged in ignored `build/obs-startup-final/evidence`; release assets
include the individual receipts and build provenance with exact source identity.

## Acceptance still requiring the owner

- Android device installation, benchmark runs, CPU/GPU models, screen capture and
  thermal behavior. Benchmarks retain the existing per-sample timeout and isolated
  worker recovery rather than allowing overlapping native work after a timeout.
- Live OBS recording and macOS audio/capture permission checks. The Mac locked
  before OBS acceptance; the prior instruction to continue without UI inspection
  was followed. No live recording, streaming or OBS scene changes are claimed.
- The preceding 1.0.1 Screen page was inspected in the actual Mac app. This final
  1.0.2 OBS guide has render/contract and packaged-runtime verification, not a live
  unlocked-app inspection. Restart into the new installer to test these changes.
- Desktop screenshot OCR is Japanese and watches settled/changed pages. General
  selectable website text uses the extension. Arbitrary multilingual image OCR,
  browser-store signing, live extension installation and Windows/NVIDIA execution
  are not claimed. Brief hiding of Hearth's own windows during capture can flicker.

No provider keys, signing stores, model weights or generated builds are committed.
