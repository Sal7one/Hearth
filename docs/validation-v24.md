# Hearth 0.11.0 — navigation and launcher integration

## Changes

Five bottom tabs replace the Home launcher: Captions, Talk (Conversation and Face
to face), Translate (typed text), Camera (camera/photo OCR), and Settings. Nested
setup keeps its originating tab and Back path. Reselect/Done returns to that tab's
root. Only one screen is composed at a time; saved drafts and scroll state survive
navigation. The existing foreground caption service remains explicitly controlled.

The generated Arabic/English mascot from the branding branch is now wired into
normal, adaptive and round launcher resources for both flavors. Package IDs remain
unchanged for upgrades. Cloud speech and text translation have separate Settings
entries. No speech, translation, OCR or TTS runtime/native implementation changed.

## Gates

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
  :app:assemblePlayQa :app:assembleFossQa :app:assemblePlayRelease
  :app:assembleFossRelease`: PASS.
- App: 197 tests per flavor/build type, zero failures/errors, one intentional
  opposite-flavor skip per suite. Common JNI: 72 tests each in debug/release.
- Six navigation tests cover per-tab paths, root selection/Back, all 13 existing
  numeric deep links, repeated model/download links and saved-state restoration.
- Python hosted voice protocol: 8 tests; speech packaging: 5 tests; PASS.
- `scripts/verify-release.py` for QA and unsigned release: PASS for both flavors,
  including packaged native hashes/provenance, dependency closure, 16 KB alignment
  and network/camera permissions. Native implementations were unchanged; their
  previous host/device results remain in v21–v23 records.

## Phone checks

Samsung SM-S908E, Android 16 / API 36; upgrade over 0.10.1 without uninstalling.

- All five tabs visible and reachable, with selected accessibility nodes.
- Talk opens Conversation and Face to face; both Speak buttons remain reachable.
- Typed test draft survives tab changes and visiting voice setup; translation
  regenerates through the already-installed ML Kit English/Arabic pair.
- Settings retains its scrolled position across tab switches. Voice setup Back
  returns to the originating typed screen. Existing selected models/languages remain.
- Camera opens with live/capture/import controls. Leaving the screen follows its
  existing disposal path; no new capture session was started for this navigation check.
- Light/dark appearance checked; 130% system font checked for readable, non-overlapping
  tab labels and wrapping Settings cards. Original 100% font and dark theme restored.
- Android app info displays the new mascot icon with the Hearth name.
- Offline QA installed in place: five tabs present, cloud connection entries absent.
  Cloud QA restored afterward; existing models/keys/history retained.

These are navigation/layout checks, not new model-quality or latency benchmarks.
Auditory TalkBack testing and wider handset/landscape coverage remain outstanding.
Production APKs remain unsigned; QA artifacts are debug-signed.
