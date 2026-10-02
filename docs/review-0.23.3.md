# Hearth 0.23.3 overlay review

Reviewed public main `044903f` on 2026-10-02, including the recent Audio,
read-aloud, geometry and TV-control changes (`044903f`, `9120e31`, `5ffe0be`,
`429c134`, `7109c32`). Version 0.23.3 uses Android version code 76.

## Changes

- Appearance is first, CC/translation second, Audio third. The shared TTS and
  device-volume controls inherit the overlay's `LocalContentColor`, including
  headings that previously inherited dark text on a dark surface.
- Caption text, settings and language pickers share the user-selected height.
  Content scrolls inside it; opening controls does not enlarge the bubble.
  The existing general 144 dp floor and viewport clamp are retained, without a
  special settings minimum. WindowManager receives the explicit height and
  updates when height changes, as it already does for width.
- Width/height slider ticks preview the window immediately. Only completion
  persists settings and applies runtime configuration. Size drafts preserve
  live geometry during older DataStore emissions while accepting unrelated
  saved changes. Commit revisions protect a newer drag from an older commit;
  hide/rotation discard the draft.
- Reset is the first header control while settings are open, outside scrolling
  content, replacing the header's Clear control. Clear remains in CC/translation.
  Reset restores appearance and the active orientation's geometry. It preserves
  selected models/providers/languages, translation and read-aloud preferences,
  pause state and open settings; device media volume is unaffected.
- Explicit portrait saves retire the old `bubble_height_dp` fallback, so Reset
  cannot resurrect a legacy tiny height. Landscape geometry is retained when
  portrait is reset, and conversely.

## Review boundaries

The optional TTS lifecycle, bounded cloud queue, Stop/Silence cancellation,
gender mapping, device-volume adapter and TV/media-key behavior from the
previous reviews were checked against the current sources. This change does
not alter those pipelines or their network policy. Native source, staged
runtimes and Phonon model pins are unchanged.

## Validation

The new/updated JVM checks exercise fixed viewport height, resize interleavings,
presentation reset, legacy-height migration and separate orientation storage.
No screenshot-only assertions or additional UI-test dependencies were added.

Checks actually run:

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa`
  — PASS (`BUILD SUCCESSFUL in 4m 6s`).
- App suites: 398 tests per flavor/build, zero failures/errors; Play skips one
  flavor-specific test and FOSS skips five. Common-JNI suites: 89 tests per
  tested build type, zero failures/errors/skips. Eight new regression tests
  cover resizing and Reset, with the existing picker check updated.
- `python3 scripts/verify-release.py --build-type qa` — PASS for both APKs,
  including vendor hashes, native dependencies, 16 KiB alignment and flavor
  permissions. Play: 44,996,217 bytes; FOSS: 37,051,844 bytes.
- `aapt2 dump badging` — both artifacts report 0.23.3 / version code 76.
- `git diff --check` — PASS.

Native host suites were not repeated because no native code/runtime changed.
QA APKs are debug-signed test artifacts; the release is a GitHub draft.

No adb, device playback/capture, TV hardware or paid provider calls were used.
Owner checks remain: drag both size sliders in a running overlay, scroll long
captions/settings at compact height, Reset from every tab and the language
picker, inspect headings in dark/light themes, and rotate with TTS active.
Host tests cannot establish those physical interaction results.
