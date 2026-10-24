# Visual colour pickers and manual screen snapshots

Android candidate RC18: `0.30.0` / code **114**, existing QA app ID/signer.

- `ui/components/HearthColourPickerView.kt` supplies the actual two-axis HSV
  gradient, hue strip, selection marker and preview to native and Compose UI.
  Drag previews are local; pointer-up commits. Cancellation restores without a
  save. Valid six-digit hex commits on Apply/IME Done. Same-colour state echoes
  and unrelated preference updates preserve active gestures and unfinished hex.
- Screen translation has separate background/text pickers in setup and floating
  Appearance. Existing saved ARGB keys, presets, opacity and dimensions remain.
  Speech-caption editors reuse the picker, including independent opacity and
  theme reset. Their nonfocusable floating window uses visual controls; hex is
  offered in the normal editor. No new overlay window or library dependency.
- Reading options alone become IME-capable; the handle and translation layer
  retain their touch/focus policies. Closing, setup suppression and teardown hide
  input and release focus. Back still closes the controls before the app below.
- Explicit Translate now owns its captured frame through all motion classes,
  including widespread video motion. In Manual mode the completed snapshot stays
  readable until refresh/clear or lifecycle/configuration invalidation. Automatic
  page mode resumes its usual motion following after the explicit request ends.
- Replacement cleanup and its window are fully opaque, independent of saved box
  opacity. Shrinking/moving labels never exposes original glyphs. Regular opacity
  and touch-through choices stay saved. Only while replacement is visible, the
  full-screen reading layer is interactive; its eye button hides/shows it without
  OCR or translation. Hidden/original/off restores saved touch-through. Android
  limits untrusted touch-through window opacity; do not bypass system protections.
  Motion behind fully opaque pixels is not observable; automatic mode relies on
  uncovered areas. Never pretend to deblend source pixels at window alpha 1.
  See https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE
- Stop, setup suppression, newer taps, OCR/translator/language/crop changes and
  orientation still retire work. Current-ticket checks reject obsolete output.
  Bounded queues, model integrity and native ownership are unchanged.

Required Android tests/compiles pass 1,223 app cases per variant (six variants,
27 net additional regressions). Both QA builds and APK checks accompany the draft.
Host renderer tests can save actual production picker images to
`HEARTH_PICKER_PREVIEW_DIR`; these show synthetic state, not device screenshots.
The owner tests the phone. No adb or GitHub Actions runs.
