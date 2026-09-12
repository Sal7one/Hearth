# 0.4.0 — shared language pickers

September 12, 2026. Source-language selection is available for original CC as well
as translation, in Home, advanced setup and the overlay. Translation setup uses the
same target picker. Native names, English labels and decorative flags replace the
long chip rows. App lists support search; the overlay list expands temporarily and
restores its saved reading height and settings position afterward.

Runtime/UI language policy consumes existing speech and local-translation capability
sets. Nemotron hints reach JNI; Deepgram selections now reach its existing client
parameter. Auto-only adapters do not receive stale hints. Local translation exposes
all 37 HY targets and persists language codes while reading legacy preferences.
Whisper capability metadata matches its bundled language-code table. No native
binary, model weights, cloud protocol implementation or key storage was replaced.
See language-pickers.md for precise limits and contributor extension points.

## Gates

`./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa` passed on the final code.
115 app tests per six variants; 58 common-module tests per two variants; zero
failures/errors. New host checks cover search/RTL/locale behavior, model capability
filtering, hint policy, old/new target persistence and temporary overlay sizing.
`python3 scripts/verify-release.py` passed for both APKs, including permission
separation, native hashes/dependencies and 16 KB alignment. Native code did not change.

## Connected phone check

On the explicitly authorized Samsung SM-S908E / Android 16 with its existing large
display scale:

- Home displayed both native-name/flag language fields without clipping.
- Spoken-language dialog displayed Auto and supported Nemotron languages. Searching
  “Russian” returned русский / Russian; selecting it returned to Home and saved it.
- Android's accessibility tree exposed checked/unchecked selection rows, language
  text, named search/back controls and a scrollable list. Flags are decorative.
- Started the microphone overlay, paused it, opened CC & translation, and opened
  the source picker. The bubble expanded from 160 dp to 440 dp within the screen.
- Selecting Auto returned to the compact bubble and its previous settings location.
  Home reflected the updated source language. Translation picker displayed the
  selected Arabic target and the model's 37-language list; Back restored the bubble.
- The final gated APK was installed after adding movement accessibility actions and
  correcting dark-overlay field-label contrast. Reopened the overlay and visually
  confirmed the readable label. Stopped capture; no service remains running.

TalkBack is not installed on this phone, so there was no auditory screen-reader pass.
No live cloud request, all-language accuracy/speed comparison, or new native model
benchmark was performed. Search/selection and capability wiring are verified; a
universal performance improvement or complete app accessibility certification is
not claimed. The phone retains Auto-detect and its existing Arabic target/160 dp height.
