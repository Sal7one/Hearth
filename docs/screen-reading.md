# Manga and book screen reading

Added in Hearth 0.13.0; permission reduction in 0.13.1. Open **Camera → Open reading overlay**. This is a distinct
cross-app overlay, separate from live audio captions. Starting either capture
feature stops the other; Android's projection session is never reused after stop.

## Use it

1. In Camera settings choose Paddle, Manga or Meiki, install its model files, and
   choose the text language, target language and existing local/cloud translator.
2. Open the reading overlay. Choose **I tap** or **Page changes**. Grant display-over-apps and screen-sharing access.
   Reading does not request the camera or microphone.
3. Open your reader. **Translate** reads the page; **Draw area** captures a still
   page and lets you circle/drag a bounding rectangle. Manga requires a selected
   bubble before automatic reading starts. Center/Whole image offer button
   alternatives to drawing. The area is reused for this session; rotation clears it.
4. Results show the original immediately, then the translation. **Read page**
   collapses the panel and resumes automatic observation. The panel deliberately
   suspends automatic captures while you read, copy or listen.
5. Use the panel for retry, clear, history (last 20 results; tap History to browse), text size, Android or
   your default voice. Drag the small handle to move it. Its position is remembered
   and clamped to the display. Tap the notification to recover it; notification
   actions offer Translate, Pause/Resume and Stop.

Android Share also accepts an image into Camera or text into Type to translate.
Shared text starts in manual mode, so the user chooses when to translate it.
Model import accepts multiple ONNX files in one picker selection. Downloads retain
original files in Downloads/Hearth/models or the folder selected in Downloads.

## Automatic reading

Page-change mode samples a small luminance grid roughly once per second and waits
for movement to settle. The delay is adjustable. This is **image-change detection**,
not an estimate marketed as exact scroll distance. There is one latest-view OCR
slot and a conflated translation queue. A newer view invalidates older work before
it can publish. A manual request can retry identical text.

Hearth 0.13.1 removes the optional Accessibility service and its scroll-distance,
scroll-count and Volume Up controls. Existing distance/count settings migrate to
Page changes with an explanation in setup. The adjustable settling delay remains.
This reduces sensitive access in downloaded APKs; manual/draw capture and visual
page-change detection need only overlay and user-approved screen sharing.
See [the installation fix and verification limits](validation-v27.md).

Screenshots live in memory and are not saved or sent to an OCR server. Models run
locally; cloud translation sends recognized text through the existing BYOK
connection. FOSS has no networking or delegated downloads. The screenshot surface
is detached between captures; each capture hides Hearth windows and waits for a
fresh frame, avoiding OCR of its own output. Protected/black captures, model errors
and provider errors are surfaced. Projection revocation stops the service and
releases capture resources. A session cannot restart without fresh consent.

## Current limits

- Results use an accessible text panel, not translated text painted into each manga
  bubble. Panel grouping, inpainting and dense-page reading order remain future work.
- Manga reads one selected bubble and can hallucinate on non-text; it is not a page
  detector. Meiki recognizes Japanese lines; vertical punctuation can be missed.
- Settings are global, not stored per reader. History is bounded and session-only.
  There is no persistent book library or glossary.
- The model cards remain experimental. Small generated fixtures and browser pages
  establish runtime integration, not broad manga-quality or thermal benchmarks.
- Exact scroll-distance/count triggers and global Volume Up shortcuts are unavailable.
  Normal reader swipes and page-turn keys are not intercepted.

## Extending

`OcrCatalog` owns pinned model assets and capabilities; `OcrModels` verifies imports
and opens `OcrEngine`. `CameraOcrController` owns bounded OCR/MT workers and stale
revision rejection, shared by camera and reading. `ReadingOverlayService` owns the
projection and windows. `ReadingTrigger` and `PageDifference` are Android-free and
have host tests. No Accessibility service is declared.
Reuse `ConversationTranslatorSnapshot` and `VoicePlayer`; do not add independent
credentials, downloads, translation engines or TTS implementations to the overlay.

## Hardware tests

`common-jni/src/androidTest/.../ocr/JapaneseOcrDeviceTest.kt` is an opt-in real-model
instrumentation suite for Paddle, Manga and Meiki. Build with
`./gradlew :common-jni:assembleDebugAndroidTest`, install that test APK, and stage
verified files in its **private** `files/ocr-smoke` directory using `adb shell run-as
com.sal7one.common_jni.test`. Android 16 scoped external storage can hide shell-created
external files from the test process; private staging avoids that discrepancy.
Run with `adb shell am instrument -w -r -e class
com.sal7one.common_jni.ocr.JapaneseOcrDeviceTest
com.sal7one.common_jni.test/androidx.test.runner.AndroidJUnitRunner`.
The suite skips if the opt-in directory is absent, and fails if a required file is
missing. Models/fixtures are not bundled in the app or committed as large assets.
See [validation](validation-v26.md) for results and known limits.
