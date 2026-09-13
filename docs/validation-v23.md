# Hearth 0.10.1 / v23 — voice cards and explicit playback

- Added visible Supertonic, Chatterbox, Qwen3-TTS and Fish cards with runtime,
  language/voice coverage, license and publisher/model/setup links. Hosted models
  remain explicitly server-only; foss exposes the native card with a selectable
  publisher address and no network-launch action.
- Shared manual controls now offer Android and Custom playback for typed originals
  and translations, camera originals/results, traveler turns, face-to-face history,
  large-text presentation and voice preview. The custom engine is remembered
  independently of Android configuration/playback. Unconfigured custom actions open
  setup. Caption read-aloud also has a Custom default choice.
- No native runtime or model-file format changes.

Validation on 2026-09-13:

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
  :app:assemblePlayQa :app:assembleFossQa`: PASS.
- App: 191 tests per flavor/build type, no errors/failures and one intentional
  opposite-flavor skip each. Common-jni: 72 tests each for debug/release, unchanged.
  The new policy tests cover system/custom/default separation, migration of an
  existing native selection, changed custom engines and blocked foss remote routes.
- `python3 scripts/verify-release.py`: PASS for both QA APKs, including native hashes,
  dependency closure, 16 KiB alignment, notices and offline permissions.
- Samsung SM-S908E / Android 16: confirmed the Supertonic card, publisher link and
  persistent custom-default label. Changed settings to Android voices; Supertonic
  F1 remained the custom default. Typed English translated to Arabic using installed
  ML Kit packs; independently invoked Custom translation and Android translation.
  Both ran without an error, and the controls were exposed with distinct TalkBack
  descriptions in the UI hierarchy. Visually reviewed the typing controls in dark mode.
- Hosted inference quality and full TalkBack traversal retain the limits documented
  in v22; this update does not claim new server-model inference testing.
