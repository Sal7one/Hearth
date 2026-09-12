# 0.2.1 — easier downloads and bubble controls

Implemented September 12, 2026. No speech, cloud-provider or native runtime changes.

- Downloads use Downloads/Real time transiber/models or files on Android 10+.
  Android 9 retains app storage; existing app-stored downloads remain accessible.
- Completed recognized translation downloads install through the existing verified
  importer, directly from Models or Downloads. Installation does not enable the
  bridge. Raw direct-download forms are collapsed by default.
- Bubble settings have Appearance and CC & translation sections. Height presets
  and an explicit saved height replace competing reading/settings height rules.
  Existing reading height is preserved until resized, subject to reachable controls.
- Full model/cloud setup opens in the app. Read-aloud options remain available.
  Settings use an opaque background for legibility; normal captions retain the
  user's transparency. Diagnostic actions are collapsed in the main app.

## Checks

`./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa` passed. App: 111 tests in each of six
variants; common JNI: 55 in each of two variants; zero failures/errors.
`python3 scripts/verify-release.py` passed for both APKs, including native hashes,
alignment, dependency closure and offline/network permissions.

Added small host checks cover preserved legacy height, explicit resizing,
rotation/tiny-window bounds and preference round-trip without changing caption mode.
Download destination containment/uniqueness and existing verified-import tests pass.
No benchmark framework or new diagnostics infrastructure was added.

On the authorized connected Samsung SM-S908E / Android 16, the update installed
without clearing data. Nemotron started with the existing model. Appearance opened
at Height and Compact reduced the bubble to half its previous 320dp reading height.
The session was paused for UI inspection. A 1,078-byte HTTPS fixture downloaded via
the app completed in the public app-named files folder. No provider key was used.
The final APK includes the small legibility and resize-position corrections found
while inspecting the first candidate. On the final installed APK, the saved compact
height survived upgrade and a new session; Appearance and CC & translation tabs
were both exercised. Capture was stopped after the check.

A fresh large-model download and direct installation of an older DownloadManager
record were not repeated on this phone; that installation path uses the existing
size/hash-verified importer. Android 9 was not device-tested. Smaller translation
weights and the remaining main setup redesign are not part of this release.

Owner checks: install a completed translation download without export; open bubble
Appearance, resize and move it lower; switch to CC & translation; confirm appearance
changes keep captions running and the chosen height survives a new session.
