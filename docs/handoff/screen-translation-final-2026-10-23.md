# Screen translation finalization — 2026-10-23

Release subject: `v0.30.0-rc.6` on `codex/hearth-desktop-kmp`.
Android remains 0.30.0, version code 103; desktop package is 1.0.1.
QA uses the existing `com.sal7one.transiber.qa` application identity and test key.
No GitHub Actions or Android device automation was used.

## Delivered

- Android Home/Quick Settings/legacy shortcuts open **Screen translation** with
  explicit **Websites & apps** and **Japanese manga** choices and new artwork.
  Website mode selects a compatible Paddle OCR script; each workflow remembers
  reader/source through the existing DataStore facade. Translation route and
  destination stay shared. Saved pass-through remains recoverable via the lock.
- Android automatic capture waits for translator initialization and rearms on
  resume/settings return. Invalid remembered workflow choices report errors.
- Desktop Screen navigation supports image import and explicit region/display
  selection, a changed-content watcher, resident reader reuse, bounded translation
  cache, transparent click-through replacement layer and independent controls.
  Stale page results are discarded; config changes require a fresh watcher.
  Caption-overlay visibility is suppressed transiently, without rewriting prefs.
- The authenticated loopback image API supplies bounded PNG/JPEG decoding,
  progressive geometry/text/translations, status/SSE and joined cancellation.
  It shares native admission and the existing four-job upload/session cap.
  New API requests cannot publish the previous image's regions. Server shutdown
  and browser trust changes clear revoked UI state even during cancellation.
- The unpacked browser extension translates visible selectable website text
  through the saved translator; links/forms retain behavior. Explicit per-tab
  activation, exact-origin pairing plus bearer auth, bounded mutation/scroll work,
  stale-result rejection, restoration and actual engine errors are implemented.
- Shared manga budgeting checks blank/final OCR/cleanup calls and preserves useful
  completed output. Cancellation from a stage callback prevents obsolete inference.
  Final cleanup snapshots update paints without retranslating the final bubble.

Usage: [Android](../screen-reading.md), [desktop](../desktop-screen-translation.md),
[image API](../../desktop-integration/README.md),
[browser extension](../../integrations/browser-extension/README.md).
[Artwork files and exact built-in generation prompt](../artwork/screen-translation.md).

## Validation

- Required Android `test`, Play QA Kotlin and FOSS QA Kotlin compilation passed.
  Every Play/FOSS Debug/QA/Release app unit suite has 901 cases, zero failures or
  errors; 3 Play / 15 FOSS cases intentionally skipped in each variant.
- Both QA variants built for all four ABIs plus universal; all ten APKs pass
  `scripts/verify-release.py`. QA upgrade signing/identity checked from code 102
  to 103 for both Play and FOSS ARM64 artifacts.
- All six required desktop suites passed: core 42, runtime 93, data 29, media 9,
  integration 27, desktopApp 104. There are 36 total intentional opt-in/fixture
  skips in that general gate. Do not describe these as all real-model runs.
- Rebuilt Mac media helpers (2/2 CTest cases) and staged OCR helper before desktop
  validation. Dedicated real pinned manga JNI inference passed 2 tests: 10/10
  fixture regions, first outline ~151 ms, first text ~7.8 s, quality cleanup
  ~28.6 s after ~7.3 s model loading under host load. No new speedup is claimed.
- Actual packaged Mac Metal Whisper and GGUF translation inference passed on the
  Apple M4 Pro, including GPU allocations and 25/25 translation layers offloaded.
  This does not imply Metal support for OCR or every speech/voice model family.
- Packaged launcher/JRE/native/audio/crypto/authenticated API checks passed.
  macOS `.app` deep/strict code-signature verification passed (ad hoc signature,
  no Developer ID/notarization). DMG built; new Screen page visually inspected
  in the actual app, then relaunched with the owner's existing setup.
- Browser extension: 14 Node execution/contract tests and 3 JS syntax checks pass.
  Dark/light reading fixtures and transparent-region rendering inspected.

Evidence is staged under ignored `build/screen-translation-final/evidence` and
included in the validation asset. No private page image, model weights or keys
are included. Mac was left on the new Screen page with the owner's preferences.

## Owner acceptance and precise limits

- Android device testing belongs to the owner; none is claimed here. Screen
  capture cannot read protected content. Generic overlays may obscure movement
  detection when nearly all visible content is covered; visual polling is not
  exact scroll tracking. CPU/thermal/translation quality remain model-dependent.
- Desktop screenshot/image OCR is Japanese. General website text uses the browser
  extension; arbitrary other-app multilingual desktop OCR is not implemented.
- Screen watching is settled-page polling, not video-frame translation. Only
  registered Hearth reading/control windows briefly hide for capture exclusion;
  this can flicker. Quality Koharu/LaMa mode remains a CPU still-page workload.
- Actual macOS screen capture consent and cross-app click-through acceptance are
  separate owner checks; UI inspection and pure geometry/native/window tests do
  not replace them. Browser extension installation/live webpage inference has
  not been performed; it is a development/unpacked artifact.
- Windows installer, native capture and NVIDIA execution still require matching
  Windows hardware. No new Windows binary is presented as validated. CUDA paths
  remain Whisper/GGUF translation only, and have a local validation script.
