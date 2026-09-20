# Hearth 0.20.1 / code 36 — OCR languages and return navigation

20 September 2026. v76 QA patch; native runtimes and provider integrations unchanged.

## Changes

Camera and Screen & manga now share observable OCR preferences and the same labelled
Text in image / Translate to controls. The source picker can choose any language in
the actual OCR catalog, selecting a compatible reader when necessary. An explicit
Manga/Meiki choice stays selected for Japanese. Destination choices come exclusively
from the chosen translator's direction capabilities. Invalid saved destinations stay
visible with an explanation; they are not silently replaced with English.

Reader selection/download/import is available inside Reading setup. In Simple mode,
Back returns to the Screen & manga Home card, including entry through Camera or quick
settings. Model/download screens launched by Reading return to Reading before Home.

Changing camera settings cancels existing inference, clears stale results, preserves
the captured bitmap and exposes Translate again. Manga reprocessing retains the
region-selection requirement. Capture/retry callbacks read the saved selection at
the action boundary, so a callback retained by Compose/CameraX cannot start the
previous model or language pair. New installations also include the earlier Ink + Dark
default commit; explicit appearance preferences survive upgrades.

## Automated validation

`./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa`.

All six app suites: 259 cases each, zero failures/errors; existing expected skips:
one per Play variant, two per FOSS variant. Common JNI debug/release: 74 cases each.
Five new host cases exercise reader switching, explicit Japanese reader retention,
independent destinations, directional cloud capabilities, missing/unknown capability
handling and FOSS ML Kit restrictions. No new native files or parsers were changed.

`python3 scripts/verify-release.py`: both QA packages pass native dependency/hash,
16 KiB alignment and flavor permission checks. FOSS still has no network permission.

## Phone checks

Samsung SM-S908E, Android 16, cloud/local QA build, updated without clearing app data:

- Both source and destination controls appear directly in Camera and Reading.
- Japanese → English can change to Japanese → Arabic in Reading.
- Reopening the target picker positions the selected Arabic row in view.
- Choosing Russian switches to the Cyrillic reader and preserves Arabic destination.
- Returning to Japanese chooses a compatible reader; selecting Meiki is retained.
- Back returns to Home at Service 6 of 6, Screen & manga.
- Reading → OCR reader → Downloads → Back restores Reading's reader sheet.
- Camera sees Reading's new pair and engine; no stale saved pair overwrites it.
- A known English fixture was imported and OCR boxes appeared. While that captured
  session was still running, the destination changed to French. The old session
  stopped, the bitmap remained, and Translate again became available.
- On the final build, changing a captured image to Manga and tapping Read again
  opens Draw around text before inference. This regression exposed and verified
  the fix for an action retaining the previous profile.
- Japanese / Meiki / English and translation enabled were restored after testing.
- Cold launch restores the Screen & manga Home card.

These are capability/navigation and captured-image regression checks, not a new
translation-quality benchmark. Cloud translation calls and continuous screen-overlay
tracking were not rerun for this patch. Full TalkBack, large-font, landscape and
multi-device audits remain unverified.

## Delivery

Both QA APKs use `com.sal7one.transiber.qa` and replace one another. They are
release-optimized, debug-signed ARM64 test builds. Draft GitHub delivery contains
binary assets only; source changes remain local until the owner pushes them.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `hearth-v76-ocr-languages-cloud.apk` | 36,002,577 | `354264911eafc92856efdd94b8b88cabb4af6f6d49ca73d097cbc225f907b2c4` |
| `hearth-v76-ocr-languages-offline.apk` | 28,167,955 | `0a9bc742c22bdcf14331ca98827ed713bfa7b0c7245efc05af634360a1f4d6a5` |
