# Hearth 0.14.0 / code 28 — live positioned reading translations

Date: 19 September 2026. Kotlin/UI changes only; native OCR adapters, model pins,
permissions, package name and signing identity are unchanged.

## Behavior

The reading overlay keeps OCR geometry through translation instead of combining
all boxes into an unaligned page translation. The chosen local/cloud translator
processes boxes sequentially, with bounded work and a session cache; each result
retains its source box. Obsolete results stop publishing and do not enqueue
remaining boxes after a page change. Empty outputs and provider errors remain
failures. The camera's existing whole-text translation path is unchanged.

Translations are drawn over the real reader in a transparent window. Its locked
mode uses `FLAG_NOT_TOUCHABLE` and the device's permitted obscuring opacity, capped
at 0.8. A separately touchable, draggable lock stays above it. The 20 dp icon has a
32 dp visible circle and 48 dp touch target. Unlock expands the reading controls
and enables tapping individual boxes for full text and read aloud. The audio
caption recovery handle uses the same smaller dimensions. Notification controls
remain available independently.

Visual motion is sampled every 250 ms by default (adjustable 200–1,000 ms), excluding
system bars, the handle, and current/previous translation rectangles. Detected
movement clears stale positions and invalidates old work; settled views run fresh
OCR and translation. Motion sampling leaves the overlay visible. Only clean OCR
frames briefly hide it. No Accessibility service, global input interceptor or
stored screenshot viewer is involved. Opening text controls pauses automatic
observation; returning captures the actual reader again rather than restoring old
coordinates. History retains text only.

## Limits

This is experimental positioned replacement, without artwork inpainting or
frame-perfect tracking during scrolling. Visual detection may miss tiny/low-contrast
movement, especially when little unpainted content remains, and animations can
trigger refreshes. It is not an exact scroll-event counter. Faster polling costs
battery. Android's pass-through opacity limit can leave original glyphs faintly
visible beneath translated text.

Meiki/Paddle return line boxes, not semantic paragraphs. Manga uses a drawn bubble.
Independent line translation can lose sentence context and take more requests/time.
Work is bounded to 64 boxes and 3,000 source characters. Small boxes may ellipsize
at the 10 sp minimum; full text/TTS remain in the panel. TalkBack, large font sizes,
vertical/RTL layouts and diverse readers need broader device coverage.

## Validation

Ten new translation/geometry tests cover duplicate/multiline responses, immutable
progressive results, stale inference, exact provider failure, empty results, work
limits, crop/letterbox placement, vertical boxes, invalid native coordinates and
clipped glyph-cover margins. Seven motion tests cover baseline/noise, visible
movement, system/handle exclusions, handle dragging, reset behavior and preventing
new translations from triggering their own recapture loop.

Build gates: `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa`. App: 224 tests per variant across six
variants, zero failures/errors, one expected conditional skip per variant. Common
JNI: 74 tests each for debug/release, zero failures/errors/skips. Native source is
unchanged, so native inference suites were not rerun for this UI/Kotlin change.

Phone checks used a Samsung SM-S908E (S22 Ultra), Android 16, with Meiki Japanese
lines and local Hy-MT2 1.8B Q4_K_M, Japanese → English. An authored browser page
provided separate bubbles for weather, station directions and thanks. Translations
appeared at each source location; a drawn two-bubble region preserved positioning.
Touch-pass-through swipes moved the real browser, cleared previous placements and
produced “Thank you.” at its new position after scrolling settled. Stable translated
pages stayed visible. Unlock expanded controls, and dragging the compact handle
moved it away from the text. Android's opacity limit visibly allows faint source
glyphs through in locked mode; unlocked mode is opaque.

Phone testing also caught status/navigation symbols being recognized as page text,
a blank selected region unnecessarily opening the large panel, and an oversized
platform emoji. The final code crops system insets for whole-page OCR, leaves blank
views unobstructed until further movement, and uses a fixed 20 dp vector lock. Its padding is assigned after the background
to prevent Android InsetDrawable from enlarging the icon.

The reading lock was exercised on the phone; the audio handle size change was
compile/test checked, without a new microphone transcription session. This is not
a broad OCR accuracy, latency, TalkBack or thermal benchmark. Current runtime/model
support is unchanged.

Final build passed in 2m 14s. `python3 scripts/verify-release.py` passed for both
flavors, including permissions and 16 KB native alignment. Final cloud QA was
installed through ADB for functional checks; this is not a Play Protect result for
these exact bytes. The previously tested browser-install scan belonged to an
intermediate candidate. Play Protect stayed enabled, and Brave's temporary
unknown-source install permission was restored to off.

Final-build checks confirmed the small vector lock and whole-page system-bar
exclusion on the same phone. A pass-through swipe again produced “Thank you.”
at the new bubble position. Unlocking and tapping that translation opened the
matching Japanese original and full English text; Stop removed the overlay.
Browser address text remains eligible OCR content;
draw a reading region to exclude app-specific chrome.

QA artifacts (debug signed; not committed):


- `hearth-v68-live-reading-cloud.apk` — 35,108,155 bytes; SHA-256
  `edfcfe9e752456caf09964db4bb780c9d0353185c035fabf0a6e28da4018443b`.

- `hearth-v68-live-reading-offline.apk` — 27,322,653 bytes; SHA-256
  `204929031930b9c37ea5fd0c9558a4474374ca54514b32df403862d3ce5c2257`.
