# Hearth 0.8.1 — make Face to face discoverable

The 0.8.0 split view was behind Conversation → Options, and the conversation
entry itself was below the home caption settings. Version 0.8.1 puts Conversation
and Face to face directly at the top of Home. Conversation also has a visible
Face to face button. The existing settings-sheet switch remains available.

Home passes the requested initial presentation to the same conversation screen;
in-screen switching retains its controller and session. No audio/provider/native
behavior changed.

Validation, 2026-09-13:

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
  :app:assemblePlayQa :app:assembleFossQa` passed (268 tasks).
- `python3 scripts/verify-release.py` passed both APKs, including native hashes,
  alignment and the offline permission gate.
- Installed over v18 on Samsung SM-S908E, Android 16/API 36. Both Home buttons are
  visible before scrolling. Face to face directly opened the rotated split view.
  Done → Home → Conversation opened the original list view; its visible Face to
  face button opened the split view again. Left the phone on the new view.
- APKs: `hearth-v19-cloud.apk` and `hearth-v19-offline.apk`, version 0.8.1/code 19.
