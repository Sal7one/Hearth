# Phonon 2.5, accessible caption timing and Jisr downloads

Branch: `codex/phonon25-fast-captions`, based on RC13 (`3292024`). Android
candidate: `v0.30.0-rc.14`, version 0.30.0 / code 110, existing QA app identity
and test certificate. Model artifacts have their own public prerelease tag,
`v0.30.0-models.1`. The owner published the [RC14 application release](https://github.com/Sal7one/Hearth/releases/tag/v0.30.0-rc.14)
on 24 October 2026; its source remains `2100e766cb603930b6b35a6c8a7b3f4e36ebe5cd`.
RC12, RC13, RC14 and the Meta glasses handover are integrated into `main`.
Application
assets are separate APKs, never a combined release ZIP. The model import ZIP is
a single model package.

## User-visible behavior

- Models → Speech → Phonon offers both English-only Phonon 2 and multilingual
  Phonon 2.5. The latter covers 25 European languages, including Russian. Arabic
  and Chinese speech are excluded. Translators and voices remain independent.
- Home and speech overlay options → CC & translation expose Standard,
  Responsive and Eager timing. This uses the same per-profile DataStore tuning
  as the model settings. Custom controls remain under Advanced. Applying a
  change restarts recognition and clears current caption state; it does not
  provide token streaming or seamless retiming.
- Both Jisr directions and both precisions now download directly on Android
  ARM64 and macOS Apple silicon. They use the existing public v0.27.3 packages.
  FOSS continues to import offline. Installation does not activate translation.

## Important implementation boundaries

`SpeechProfile.PHONON_2_5` reuses the existing PHONON backend and four transducer
roles. No ABI change, extra TTS module, GPU claim, or stateful partial decoding
was added. Native code accepts the 25 individual source declarations, rejecting
combined/whitespace codes. The model cannot force decoding or report detected
language. English-only Phonon 2 resolves Auto to English before native entry;
multilingual Auto stays unknown. Android preflight and desktop UI require a
known source for downstream text translation. Desktop offers Auto for original
captions only and constrains language choices by the selected checkpoint.

The new source/export/package contract and tested dependency versions are in
[the model card](../models/phonon-2.5.md) and
[its machine-readable receipt](../models/phonon-2.5.json). The original packed
156.2 MB format is not supported directly. Hearth's ONNX TAR download is
339,205,640 bytes; six model/attribution files total 629,548,381 bytes. The
363,898,653-byte offline ZIP contains the root manifest. The automatic-download
TAR intentionally excludes that manifest: Android generates trusted installation
metadata after checking the archive. Do not weaken the publisher TAR installer
to accept supplied installation metadata.

`Ct2Catalog` has an optional pinned download archive. `Ct2Package` downloads one
Jisr ZIP, verifies its transport pin, restricts CT2 staging to the requested model,
then verifies every component before atomic publication. It budgets ZIP plus
expanded storage and preserves cancellation. QuickMT/SMaLL component identities
remain unchanged. Desktop accepts these flat CT2 ZIPs only with exact component
and manifest pins, bounds and inventory checks. The existing named-root Vosk
tree ZIP path remains separate. Archive CT2 selections propagate every component
pin into runtime integrity verification.

Desktop catalog generation now reads Qwen's actual language-name map and the
new Phonon receipt. There are 76 unfiltered cards, including seven CT2 variants
and two Phonon checkpoints; platform filtering still applies. Regenerate with
`python3 scripts/desktop/generate-catalog.py` and check with `--check`.

## Verification and limits

- Required Android `test`, Play QA compile and FOSS QA compile passed. Each of
  six app variants contains 1,125 tests, zero failures/errors. FOSS has 25 skips
  and Play four; optional real-artifact cases were separately enabled and run.
- Speech/common ASan/UBSan host suites passed, including 142 speech config
  checks and 73 real-adapter/fake-external-API Phonon checks. The adjacent-code
  negative cases include `en es`, `auto en` and `mt el`.
- Genuine host / four Android ABI / Vulkan / OpenCL runtime builds were staged
  through the official scripts, with exact current source receipts. Android
  externalNativeBuildDebug passed. The desktop ONNX helper was genuinely
  rebuilt; no hand-edited attestation was substituted for a build.
- All six required desktop test targets passed. Real native C ABI and actual
  DesktopEngines/JNI paths transcribed English and Russian and preserved
  unknown Auto metadata. Decoding seven-second clips took roughly 0.16–0.22 s
  in the initial C ABI checks, excluding loading, hashing, media preparation
  and endpoint waiting. Complete desktop file requests took roughly 2.5–4.1 s.
- The raw English FLEURS fixture was below the speech gate. Its no-decode
  result is retained; real English inference used a documented normalized copy.
  Russian was unchanged. Android benchmarks already normalize quiet fixtures
  before every model, so the production gate was not changed.
- Both actual converted archives passed the production Android JVM installer
  paths, including root manifest, exact roles, all six file pins and attribution.
  All four existing Jisr ZIPs were fully downloaded and checked against every
  component pin. Exporter/packager Python regressions: 16 passed.

Ignored local evidence lives under `build/caption-timing/rc14-evidence/`,
`build/phonon-2.5/native-validation/` and `build/jisr-downloads/`. To repeat the
real installer smoke, set `HEARTH_PHONON_25_INSTALL_SMOKE=1` and
`HEARTH_PHONON_25_ARCHIVE_DIR` to the folder containing both model archives, then
run `:app:testPlayQaUnitTest --tests '*Phonon25InstallerRealSmokeTest*'`.
For the desktop engine smoke, set `HEARTH_PHONON_25_SMOKE=1`,
`HEARTH_SMOKE_PHONON_25` to the extracted model and `HEARTH_SMOKE_AUDIO_ROOT` to
the documented normalized fixture tree; run `:desktop-runtime:test --tests
'*DesktopPhonon25RealSmokeTest*'` with `-PhearthDesktopOnly=true`.

No device was driven and no GitHub Actions run was dispatched or queried.
Android phone inference/quality remains owner testing. No Windows inference,
Windows installer, desktop installer, 25-language corpus score or new GPU
acceleration is claimed by this candidate. Before delivering a desktop installer,
rebuild the matching native bundle and run the packaging check on that OS.
