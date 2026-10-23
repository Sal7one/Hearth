# Downloader, import and Koharu integration handover

Candidate: Android **0.30.0 / code 98**, `com.sal7one.transiber.qa`, existing QA
debug/test signer. Intended tag `v0.30.0-rc.1`; draft, not a production publication.
Branch `codex/hearth-desktop-kmp`. No GitHub Actions, adb or owner device driving.

## Delivered areas

- Persistent concurrent Android/desktop model queues; independent preparation,
  pause/resume/restart, DataStore settings and HTTP/aria2 selection.
- Source-built Play-only Android aria2, authenticated executable provenance,
  complete corresponding sources and automatic desktop executable discovery.
- Application-owned batch Android imports with per-file outcomes, progress,
  cancellation, private verified copies and explicit model activation.
- Transfer measurement tables/CSV alongside the existing Model Lab inference
  comparisons. Queue registration no longer rejects hundreds of future requests
  solely because all future model bytes would not fit simultaneously.
- Magpie benchmark batches include the previously omitted tokenizer. Its strict
  authenticated range transport is centralized and resumable; the 1.47 GB source
  archive is not accidentally fetched in full.
- Model repair groups component files into families, distinguishes never-installed
  requirements from damaged installations and restores complete saved downloads.
- Koharu's actual RF-DETR instance masks and panel/bubble/text hierarchy, fast
  preliminary outlines, Manga OCR, the existing Hearth translator, flat/neural
  LaMa cleanup and fitted RTL-aware text on Android/desktop.
- Fast, Koharu and Koharu + cleanup are explicitly selectable; desktop persists
  the chosen bundle and includes its identity in resident-reader reuse.
- Android frozen camera pages and cross-app reading share the region renderer;
  Original removes cleanup/typesetting without modifying the source bitmap, and
  tapping translated text opens its full accessible text/copy/voice controls.

Read [download/import contracts](../downloads-and-imports.md),
[Koharu implementation details](../koharu-integration.md) and
[OCR model details](../manga-ocr-adapters.md).

## Actual vision measurements

Mac M4 Pro, ORT 1.20.1, pinned weights:

| Check | Result |
| --- | --- |
| Optimized four-thread RF-DETR, 770×1080 fixture | 4,910 ms; 31 layout instances with real masks |
| LaMa synthetic gradient/glyph cleanup | 3,018 ms; 1,080 masked pixels reconstructed, unmasked pixels unchanged |
| Final full native reader with cleanup | 4,580 ms load; 99 ms preliminary outlines; 4,957 ms full detection; 32,703 ms total, 10/10 regions read, 9 neural cleanups |

The full-reader time excludes translation. The early 42.6-second detector result
was from an unoptimized smoke binary and must not be reported as release speed.
The compact detector remains the default. No Android performance or OCR GPU
acceleration is claimed. Model bundles are 127,716,506 / 276,158,509 / 483,641,153
bytes; no weights are packaged in the app. RF-DETR weights have custom/academic
data terms, while the shared reader and LaMa exports declare Apache-2.0.

This ports the image-reading pipeline, not Koharu's desktop editor/Torch frontend.
Diffusion cleanup, specialized SFX OCR, rotated typography and editor/export
features are not included. New native code retains lease ownership, bounded
page/mask/tensor contracts and cooperative cancellation between kernels.

## Restart and owner acceptance

Install the new Play ARM64 APK over the existing QA app on S25/S22, without
uninstalling. Open Models → Review: Magpie is one bundle, and Restore from saved
downloads reuses accessible originals. Queue Magpie from Model Lab and confirm
the tokenizer is the third job. Check other healthy models remain benchmarkable.

Try multiple concurrent downloads, pause one/all, navigate away, import a batch
with one invalid file, resume after restarting, and compare transfer results.
Test CPU/GPU inference through Model Lab separately from downloader throughput.
FOSS must import locally with no network delegation; TVs must reject local work.

For manga, test each explicit profile on the same page, preliminary outlines,
cleanup, Original, full-text taps, cancellation, crops, Arabic/English fitting and
actual selected translation. The larger profiles can consume substantial memory.

Windows runtime/installer checks and all real Android native execution remain
owner/platform checks. Desktop aria2 is an optional installed executable.
Import batches survive navigation, but unfinished selections need reselection
after process termination. Retain these limits in future release descriptions.

## Final local validation

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
  :app:assemblePlayQa :app:assembleFossQa :desktopApp:createDistributable` passed.
  Each of the six Android unit-test variants reports 772 cases, zero failures or
  errors; 3 Play / 15 FOSS cases are intentionally skipped by platform policy or
  optional-fixture assumptions. This is host coverage, not phone execution.
- All six required desktop suites were forced to rerun with the actual aria2
  executable and pinned manga weights. They report 271 cases, zero failures or
  errors, 35 optional-fixture skips. Both real manga cases and the real aria2
  transport case executed without skips.
- The real 8 MiB HTTPS fixture passed TLS, pinned hashing, Range and pause/resume
  checks through Homebrew aria2 1.37.0; seven Range requests were observed.
- Speech/common sanitizer host suites and OCR host coverage passed; the full
  all-ABI Android `externalNativeBuildDebug` completed successfully. ABI switch
  verification passed without cleaning, including ARM64, x86, x86_64, explicit
  multi-ABI selection and rejected conflicting/unsupported TTS selections.
- All ten QA APKs passed `scripts/verify-release.py`. The package, existing
  certificate and version-code upgrade were checked for both Play/FOSS ARM64.
  Play 64-bit packages contain pinned 16 KiB-aligned aria2 executables; FOSS has
  neither the executable nor download network permissions.
- 48 general Python checks and 22 desktop Python checks passed. Corresponding
  aria2/OpenSSL sources repackaged offline without Git to the identical source
  archive hash; this offline check does not claim a second native compilation.
- The rebuilt macOS ARM64 package passed `scripts/desktop/check-packaged.py`,
  exercising its actual launcher/JRE/native adapters, media decoding, crypto and
  authenticated local API. Its packaging version is **1.0.30** (Compose's native
  package version), corresponding to this **0.30.0 RC1** source release. It is a
  local ad-hoc build, not a notarized installer. No Windows binary is delivered.
- Actual Compose rendering at 820 and 1500 pixels was inspected using a
  synthetic manga fixture: fitted English/Arabic text, cleanup, controls and
  surrounding artwork. Android view host tests cover Original, immutable source
  pixels and full-text taps. Owner handset/desktop live interaction remains
  separate acceptance work.

Validation evidence is under ignored `build/downloads-manga/evidence` and is
packaged with the draft release. It contains logs, test summaries/regressions,
source/runtime manifests, ABI-switch receipts, upgrade identity and synthetic
previews. Real vision fixtures/weights remain under ignored `build/ocr-koharu`,
never in Git or release assets; OCR fixture prose is omitted from shared XML.
No GitHub Actions were dispatched or polled; the commit/tag use `[skip ci]`.
