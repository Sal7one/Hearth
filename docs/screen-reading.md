# Manga and book screen reading

Added in Hearth 0.13.0; permission reduction in 0.13.1; positioned translations in 0.14.0. Open **Home → Screen & manga** (or the Camera shortcut). This is a distinct
cross-app overlay, separate from live audio captions. Starting either capture
feature stops the other; Android's projection session is never reused after stop.

## Use it

1. Choose **Text in image** and **Translate to** directly in Screen & manga.
   Changing the text language selects a compatible OCR reader if needed. **Reader · Change**
   chooses/downloads Paddle, Manga or Meiki without a detour into Camera. Manga and
   Meiki read Japanese; translation destinations come from the selected translator.
   **Reading settings** selects the local/cloud translator and timing. Camera and
   reading share these saved choices.
2. Choose **I tap** or **Page changes**, then **Start reading overlay**. Grant display-over-apps and screen-sharing access.
   Reading does not request the camera or microphone.
3. Open your reader. **Translate** reads the page; **Draw area** captures a still
   page and lets you circle/drag a bounding rectangle. Manga requires a selected
   bubble before automatic reading starts. Center/Whole image offer button
   alternatives to drawing. The area is reused for this session; rotation clears it.
4. Translations appear **over detected text in the actual reader**, with a transparent
   background outside the text boxes. The reader remains live; no screenshot viewer
   replaces it. With **🔒 Scroll through** enabled, touches pass through the text layer.
   The separate movable lock stays touchable: 20 dp glyph, 32 dp visible circle,
   48 dp touch target. Tapping it reveals the expanded controls. Open the lock to tap a box for its
   full text/read-aloud controls; close it again to scroll.
5. Page-change mode checks visual movement at an adjustable interval (250 ms by
   default; 200–1,000 ms). Movement clears old placements and invalidates translation
   work. After the adjustable settling delay, OCR finds new box positions and the
   current text is translated. This detects visual changes, not exact scroll events
   or instantaneous tracking. Existing manual-mode choices remain manual.
6. The **⋯** panel offers full source/translation, retry, drawing, clear, session
   history, text size, Android or custom voice, pause and stop. **Read page** closes
   controls and captures the current view again. The notification opens controls
   and also offers Translate, Pause/Resume and Stop. Rotation clears old coordinates.

Android Share also accepts an image into Camera or text into Type to translate.
Shared text starts in manual mode, so the user chooses when to translate it.
Model import accepts multiple ONNX files in one picker selection. Downloads retain
original files in Downloads/Hearth/models or the folder selected in Downloads.

## Automatic reading

Motion checks sample a 64×128 luminance grid with the overlay still visible; they
exclude the handle, status bar, navigation region and current/previous translation
rectangles. Changes to Hearth's own painted text do not trigger a capture loop.
Movement is detected from the remaining visible reader content. Motion-only
frames do not hide/repaint the translation layer. Fresh OCR frames briefly hide
Hearth controls and translations so the model never reads its own output.
There is one latest-view OCR slot and a conflated translation queue. A newer view
invalidates older work before it can publish. Manual requests retry identical text.

Hearth 0.13.1 removes the optional Accessibility service and its scroll-distance,
scroll-count and Volume Up controls. Existing distance/count settings migrate to
Page changes with an explanation in setup. The adjustable settling delay remains.
This reduces sensitive access in downloaded APKs; manual/draw capture and visual
page-change detection need only overlay and user-approved screen sharing.
See [the installation fix and verification limits](validation-v27.md).

Screenshots live in memory and are not saved or sent to an OCR server. Models run
locally; cloud translation sends recognized text through the existing BYOK
connection. FOSS has no networking or delegated downloads. The screenshot surface
is detached between captures; each OCR capture hides Hearth windows and waits for a
fresh frame, avoiding OCR of its own output. Protected/black captures, model errors
and provider errors are surfaced. Projection revocation stops the service and
releases capture resources. A session cannot restart without fresh consent.

## Current limits

- Replacements are translucent white text covers over OCR boxes, without artwork
  inpainting. Android limits pass-through overlay opacity (normally 80%); the app
  respects the device's maximum, so some original text can remain faintly visible.
- Meiki/Paddle return line boxes; Manga uses the drawn bubble. Semantic paragraph
  grouping and dense-page reading order remain future work. A small clipped cover
  margin handles detector-trimmed glyphs without scaling to a vertical column's length.
- Each box is translated independently through the selected existing provider.
  This preserves geometry but loses cross-line sentence context and may take more
  requests/time. Pages are bounded to 64 boxes and 3,000 characters; larger pages
  need a smaller selection. Identical text is cached within the session.
- Visual detection can miss tiny/low-contrast movement, especially when little
  unpainted page content remains, or react to animations.
  Faster sampling uses more battery. Placements clear on detected motion and refresh
  after settling; the app does not claim frame-perfect tracking while scrolling.
- Text fits down to 10 sp and may ellipsize inside tiny boxes. Unlock and tap a box,
  or use the full-text panel, for the complete result and accessible voice controls.
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
projection and windows. `ReadingTrigger` and `ReadingMotion` are Android-free and
have host tests. `OcrPageTranslation` preserves box identity and rejects stale work;
`OcrPageLayout` maps crop offsets and fitted-image geometry into `ReadingTranslationView`.
No Accessibility service is declared.
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

See [0.14.0 validation](validation-v28.md) for positioned-page checks.

Whole-page capture excludes Android status/navigation insets. Empty views clear
translations without opening controls, so scrolling through a blank page remains
uninterrupted. A drawn region stays at the same screen position until changed.
