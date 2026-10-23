# Android Screen translation device recovery — 2026-10-24

Work follows the [RC9 handover](android-screen-reader-controls-2026-10-24.md).
Baseline: `main` at `ca8e291`, Android 0.30.0 / code 105. Recovery work is on
`codex/android-screen-reader-recovery`, targeting code 106 with the existing QA
application ID and signing identity. At this checkpoint the final APK, tag,
release and reinstall/retest are pending; do not treat the earlier code-106 test
APK as proof that the final source changes were device-tested.

## Reproduced on the connected phone

The owner explicitly authorized operating their Android phone and testing coffee
articles. Tests used **Samsung SM-G781B, Android 13**, Chrome, the Latin Paddle
OCR profile and pinned **QuickMT English → Arabic**, with local CPU inference.
This establishes no new GPU, other-device, other-language or manga-model claim.

With the installed code-105 build:

- The first page capture returned black content rather than the article.
- Retrying could highlight text, then lose the overlay result.
- Pausing passive sampling allowed an Arabic result, identifying capture and
  motion/control interaction as a real device issue rather than only a layout bug.

An intermediate code-106 test APK with persistent capture then demonstrated:

- English coffee-article text was recognized and replaced with Arabic.
- Repeated explicit reads worked after the article stopped scrolling.
- Automatic reading responded to a page change and produced Arabic. One displayed
  **3,486 ms** value measures OCR only; it is not capture-to-translation latency.
- Opening options and pressing Back returned to Chrome. Clean-capture count
  remained **5 → 5**, establishing that closing options did not translate again.
- The area-selection screenshot visually excluded Hearth's handle and labels.
  **Center → Read area** translated the selected crop; Back cancelled the selector.
- The full-width red Stop action had about **56 physical pixels** of visible
  bottom clearance in the tested layout. Tapping it stopped the session; the
  actual MediaProjection dump then reported no active projection.
- Saved language pair and model selections survived updating the QA installation.

The recorded code-106 session log contained no `FATAL` entry or disabled
`OnBackInvokedCallback` warning. This describes that recorded session only.

The Latin OCR download was approximately **12.3 MiB**. An aria2 OpenSSL legacy
provider error exercised the standard HTTPS fallback, which completed the
download. The pinned QuickMT package was imported from an existing local ZIP
through the real Android file-picker/import UI. This is evidence for those two
paths, not a throughput comparison or broad downloader/backend validation.

## Source changes

- Keep a MediaProjection display surface attached to one ImageReader. Retain one
  latest image with three reader slots, close replaced images, and serialize
  capture ownership. Motion samples no longer detach/reattach the surface ahead
  of explicit captures.
- Classify actual RGBA buffers: reject black/protected content, ignore repeated
  timestamps, inspect the selected source area rather than system chrome, and
  distinguish blank initialization from readable content. Blank-page fallback is
  bounded. Screenshot copies use absolute reads, preserve buffer position, honor
  row padding and produce opaque pixels.
- Give explicit Translate priority over passive sampling and wait for the old
  capture's cleanup. Expose capture failures and retry without opening options.
- Revalidate the saved OCR/language/translator configuration for admitted pages.
  Retire changed workers before opening replacements, recover from repaired
  setup errors, and use the controller's current lifecycle state for handoff.
- Keep page ownership through intermediate progressive translation failures.
  Complete only when OCR, detection, translation and shutdown are idle. Current
  errors remain visible; partial output and original text remain available.
- A provider-local cancellation no longer silently kills the translation worker.
  ML Kit's cancelled-task callback is a real supported path to this condition.
  A later explicit retry remains usable; actual session cancellation still stops
  work. Blank translations report errors, and unchanged live frames do not mark
  an outstanding translation complete.
- Preserve an observed motion baseline across setup navigation. Clear uncertain
  or cancelled partial geometry before automatic verification; unchanged-page
  admission still prevents unnecessary inference. Pausing unfinished work clears
  its partial geometry so Resume can verify the current page.
- Swiping over an interactive translated paragraph no longer becomes a text-box
  click. Normal capture completion/errors do not open the options window.
- Enable the Android Back dispatcher at application level for service-owned
  windows. Options/selection handle Back while the reader underneath stays put.
- Give the red Stop action its own full-width row, scroll-bottom clearance and
  system-bar/gesture inset padding. Controls expose errors and accessible Retry
  without disabling options during inference.

The late setup-return, lifecycle-handoff and pause fixes are covered by host
tests. They were **not yet present in the phone APK described above** at this
checkpoint. Mode-switching during unfinished work also needs explicit final
review/retest; do not infer it from the completed-page pause test.

## Host validation

The required gate passed, including late source fixes available at its start:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
```

Log: `/tmp/hearth-reading-full-gate.log`; **BUILD SUCCESSFUL in 3m 37s**, 358 tasks
(100 executed, 258 up-to-date). No GitHub Actions or `gh run` commands were used.

XML results at this checkpoint:

| Current module/suite | Cases | Skipped | Failures/errors |
| --- | ---: | ---: | ---: |
| Each Play app variant: Debug, QA, Release | 1,001 | 3 each | 0 |
| Each FOSS app variant: Debug, QA, Release | 1,001 | 15 each | 0 |
| common-jni, Debug + Release | 136 each | 0 | 0 |
| hearth-core Android, Debug + Release | 46 each | 0 | 0 |
| hearth-tts, Debug + Release | 68 each | 2 each | 0 |

That is **6,506 Android case executions including 58 skips**, or 6,448 executed
cases, across variants; it is not 6,506 distinct tests. Unchanged module results
were accepted as up-to-date by Gradle. Exclude leftover XML under the retired
`hearth-tts-*` directories when recounting. Desktop task caches are separate and
are not evidence for this Android device work.

Focused Play QA coverage includes seven actual controller worker tests, ten
capture-buffer tests, twenty service/window tests, ten control tests and five
translation-layer tests. Tests exercise concurrent progressive native callbacks,
intermediate failure/final retry, stale results/errors, cancellation and resource
ownership, actual button touch dispatch, Back, mode selection, setup return,
partial-work pause, layer gestures and Stop clearance. Host fakes do not establish
native model accuracy or Android compositor behavior; the phone checks above
provide the latter evidence for the stated configuration.

Local, ignored evidence is in `build/reading-phone-qa/`, including
`baseline-first-read.png`, `fixed-first-result.png`,
`fixed-steady-read-result.png`, `fixed-auto-scroll-result.png`,
`fixed-selector-ready.png`, `fixed-area-clean-capture.png`,
`fixed-area-result-options.xml`, `fixed-stop-visible.png`,
`fixed-back-check.json` and `fixed-session-full-log.txt`. Host renders are under
its `host-ui/` subdirectory. These are local evidence paths, not published assets.

## Finish before delivery

1. Resolve/review any final mode-switch or other source changes and repeat the
   required gate if they postdate the recorded successful build.
2. Build final Play and FOSS QA variants. Run `python3 scripts/verify-release.py`,
   including the application-level Back flag, package/code/signer, ABI/native
   inventory, alignment and FOSS permission checks.
3. Install the **final** Play ARM64 APK as an in-place QA update. Recheck first
   capture, repeated manual reads, automatic page change, setup return, partial
   pause/resume, switching refresh mode during translation, Back/outside-dismiss,
   area replacement/cancel and Stop padding on the connected phone.
4. Record the final APK hash, commit/tag, verifier receipt and final phone result
   here. Publish APKs and accompanying assets individually; no combined ZIP.

Final artifact identity: **pending**. Final phone reinstall/retest: **pending**.
