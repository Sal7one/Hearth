# Android Screen translation controls — 2026-10-24

Release target: `v0.30.0-rc.9`, Android **0.30.0 / code 105**. Preserve the
existing QA application ID and signing identity. Deliver each APK separately;
no combined release ZIP. The desktop speech release remains
[`desktop-v1.0.4-rc.1`](https://github.com/Sal7one/Hearth/releases/tag/desktop-v1.0.4-rc.1).
No GitHub Actions or Android device automation is used.

## Reported behavior and causes

Closing the old controls always called `requestCapture`, so simply dismissing
options translated the page again. Settings return also discarded geometry and
rearmed automatic capture. Very small unmasked page changes could trigger full
OCR; motion exclusions omitted cleanup patches extending outside OCR text boxes.
All windows were non-focusable, so Back could escape to the underlying app.

Progressive OCR could also finish a partial translation while its final request
was queued. The older request cleared `translating` for the same page revision,
exposing a premature terminal state. Invalidated native OCR errors could tear
down the otherwise reusable model worker after the reader moved.

## Implemented behavior

- Replaced runtime controls with a branded, draggable compact handle: Refresh,
  drag and Options have separate 48 dp targets. Expanded handle is optional.
- A bounded scrollable options window has Controls and Reading text tabs. The
  main choices are Translate now, Pause automatic translation, touch the page
  underneath, show original and selected area/whole screen. Appearance,
  automation and session actions expand on demand. Full text supports copy,
  original/translated read aloud, Android voices, stop voice and 20 text-only
  session history entries. Screenshots are not persisted.
- Back, predictive Back and outside taps dismiss controls; Back cancels area
  selection before it reaches the application below. Only the options and area
  windows acquire keyboard focus. The handle stays independent of pass-through.
- Rebuilt Screen translation setup with Websites/apps and Manga content choices,
  shared OCR/translator controls, Manual and Page changes refresh choices,
  separate display/advanced options, and Return/Stop for a running session.
  OCR/model/language changes require restarting that capture session; display
  and automation options apply to it live.
- One `ReadingOverlaySettings` contract uses the existing `UserPreferences`
  DataStore. It retains saved mode, model selections, position and workflow
  bookmarks; new installations default to manual refresh. Session pause/history
  remain session state. English, Arabic RTL and Chinese labels are included.
- Closing controls requests no capture. Setup return preserves the page, selected
  area and result. Pause cancels pending admission, keeps output and retains the
  loaded engine. Explicit Translate now bypasses unchanged-page checks.
- Automatic reading uses bounded settling (default 900 ms), sampling (500 ms),
  cooldown (4 s) and Stable/Balanced/Sensitive policies. Distributed changes are
  required, excluding system bars, Hearth controls, text covers, progress strokes
  and actual cleanup masks/patches, including their disappearance frame.
- A bounded clean-page signature guard prevents the same completed, blank or
  failed page from immediately repeating. Actual errors remain visible and manual
  retry remains available; this is not an infinite automatic retry loop.
- Every progressive/final translation request has its own ticket. Only the latest
  can publish, fail, enqueue or complete. Final work registers before publishing
  OCR completion. Invalidation/stop revoke tickets; stale OCR failures are ignored
  while current failures, cancellation and fatal errors retain their semantics.
  The same controller also serves Camera OCR, with no model/backend changes.
- Optional voice-playback errors remain visible without completing or discarding
  an independent OCR/translation request.
- Caption size adjusts both the reading panel and text fitting in original page
  areas, reusing labels and immutable cleanup bitmaps.

## Validation

The combined required gate passed (506 tasks). Each of the six app suites has
964 cases, zero failures/errors, with three intentional Play and fifteen FOSS
skips per variant. Both QA variants built all four ABIs plus Universal; all ten
APKs pass native/ELF alignment, permissions, package/version and signature checks.
Actual cached RC8 and new ARM64 APKs establish code 104→105 update compatibility
with signer `7a9256582e2627e5b715ef2648373e52d62c512127f40b2aff3ccbcc787d5792`.
This verifies install identity, not an attempted device installation. Exact
artifact hashes and source identity are in the release receipt. Reproduction:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin \
  :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
HEARTH_READING_UI_ARTIFACTS="$PWD/build/reading-ui-review" \
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:testPlayQaUnitTest --tests '*Reading*Test' \
  --tests '*OcrFrameWorkTest' --tests '*OcrTranslationWorkTest'
```

Host regressions use actual Android Activity, service windows, callbacks and
native-graphics views: Back does not capture or leave setup; appearance choices
persist and late OCR updates cannot reveal a hidden layer; setup return and
pause/resume preserve output; cleanup bounds and typography are correct; and
stale native/translation completion cannot supersede newer work. Six rendered
views cover English dark, Arabic light at 296 dp, advanced automation, bilingual
text and both handle sizes. They are reviewed independently of the device.

The owner tests Android. Host checks do not establish on-phone MediaProjection,
predictive Back gesture, scrolling/motion calibration, GPU throughput or thermal
behavior. Secure/DRM screens and images too small for the selected OCR engine
retain their existing platform/model limits. No new OCR/model speed claim is made.
