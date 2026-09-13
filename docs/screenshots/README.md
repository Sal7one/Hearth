# Screenshot capture notes

Captured from Hearth 0.11.0 (version code 24), cloud-capable QA build, on a Samsung
SM-S908E running Android 16 on 2026-09-13. Dark appearance, default 100% font scale.
All six PNGs are unedited 1440 × 3088 device screenshots. The README displays smaller
previews linked to the full-resolution originals.

- **Live captions** shows configuration, before capture has started.
- **Conversation / Face to face** show the existing synthetic station-directions
  example entered through Type instead. Nemotron is selected, but this example does
  not demonstrate microphone recognition or speech latency. The two layouts share
  the same stored original and translation.
- **Type to translate** shows the same English question translated by the installed
  on-device ML Kit English/Arabic packs. Both Android and Custom playback controls
  are visible; a screenshot does not demonstrate audio output.
- **Camera & OCR** shows an imported, generated Russian text image. PaddleOCR v5
  recognized it locally and ML Kit translated the recognized text into English.
  The screen is scrolled to show the source image, original and translated text.
  Its displayed OCR duration is one image operation, not end-to-end translation
  latency or a comparative benchmark.
- **Settings** shows setup destinations and saved Dark appearance. Cloud connection
  entries belong to the cloud-capable flavor; the offline flavor hides them.

Only synthetic example text is shown. No provider key fields, personal conversation
history, camera surroundings or notification content are included. The temporary OCR image
and typed demo draft were removed afterward; existing models, keys and history were
preserved. The separate [video preview](../demo/README.md) records the older 0.8.2 UI.
