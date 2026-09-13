# Changelog

## 0.11.0 — 2026-09-13

- Replace the crowded Home launcher with five bottom tabs: Captions, Talk, Translate, Camera and Settings.
- Keep Conversation and Face to face together, with independent tab return paths and saved drafts/scroll positions.
- Integrate the generated Arabic/English mascot as the adaptive and round launcher icon in both flavors.
- Put cloud speech and text translation connections directly in Settings; update public setup instructions.

## 0.10.1 — 2026-09-13

- Show TTS model cards with runtime, language/voice coverage, license and publisher/setup links.
- Add separate Android and Custom playback throughout text, camera, traveler/history/presentation and preview controls.
- Preserve the preferred custom engine independently of Android playback/settings, and add caption Custom default.

## 0.10.0 — 2026-09-13

- Add Type to translate with explicit languages, live debounced results, swap/copy/clear and source/result playback.
- Share installed Android, native Supertonic 3 and optional self-hosted voices across traveler, camera and captions.
- Add verified public-folder Supertonic downloads/import, ten voice styles and model-based language choices.
- Add explicit Chatterbox/Qwen/Fish server adapters and setup/source/license documentation.
- Preserve draft/settings navigation state and remove the unknown-language English read-aloud fallback.
- Add native bounds, WAV/import/HTTP, typing scheduler and server contract tests.


## 0.9.0 — camera and photo translation

- Add Camera translate on Home: live recognition, capture, photo import, original
  text, local/cloud translation and separate copy actions.
- Add verified PaddleOCR v5 mobile detector/readers for Latin, Arabic, Chinese/
  Japanese and Russian/Ukrainian/Belarusian groups. Downloads install automatically
  and keep originals in Downloads/Hearth/models; foss supports verified file import.
- Add a dedicated C++ ONNX image adapter with bounded frames, lifetime-safe native
  cancellation and stale-result guards. Preserve actual recognition/provider errors.
- Make camera permissions optional and update privacy, sources and extension docs.
- Show OCR download progress in model setup and cancel blocked download calls when
  the user cancels a transfer. Enforce known OCR download sizes before installation.

## 0.8.2 — publication hardening

- Correct Android Keystore encryption and migrate old speech keys to per-provider
  storage-mode records after successful decryption. Removing one key no longer
  deletes encryption material used by other saved providers.
- Keep system read-aloud on installed offline voices in the requested language.
  Preserve errors when a matching voice is unavailable.
- Remove recognized text and provider response bodies from routine caption logs.
- Enforce offline gates in legacy cloud entry points and disable authenticated redirects.
- Verify packaged native identities and speech build provenance, pin the Gradle
  download checksum, scan Git history in CI, and remove a tracked Python cache.
- Refresh setup, privacy, attribution, extension and release-signing documentation.

## 0.8.1

- Make Face to face visible at the top of Home and directly from Conversation.

## 0.8.0

- Add quick language swap, a split Face to face layout, rotated partner controls,
  shared conversation history and a settings sheet.
- Add Google Cloud, Microsoft Azure, DeepL and LibreTranslate text translation.
  Paid-account success checks are still pending; protocol/mock tests are recorded.

## 0.7.0

- Adopt Hearth branding and Downloads/Hearth, persistent light/dark/system themes,
  two-way conversation, and on-device local model benchmarks.
- Correct translation reset/drain behavior. See the [backlog](docs/BACKLOG.md) and
  versioned validation notes for earlier changes and device evidence.
