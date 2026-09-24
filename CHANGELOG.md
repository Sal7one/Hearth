# Changelog

## 0.22.16 — 2026-09-24 · screen reading speed

- Group nearby OCR lines into one positioned translation block, reducing translation calls for multi-line bubbles while keeping separate bubbles apart.
- Keep existing on-screen translation labels as new blocks finish instead of rebuilding the whole overlay each time.
- Sample motion directly from the small RGBA capture grid without allocating a full-size Bitmap on each scroll check.
- Add host tests for grouping, geometry, and padded capture-plane sampling. Manga OCR's uncached autoregressive decoder remains a separate device-performance follow-up.

## 0.22.4 — 2026-09-23 · experimental local models and comparison

- Add Xiaomi MiLMMT-46 1B Q4/Q5 as an experimental local translator with pinned downloads, advertised language coverage and its publisher-format native prompt. It is not the Easy setup default.
- Guard the Kotlin/JNI translation prompt protocol so an older native library cannot silently run the wrong prompt.
- Expand the on-device speech and translation comparison to model-supported languages through personal WAV/text samples, retain up to 200 local results, filter history by language or pair, and export timestamped JSON.
- Document publisher-verified June–September model candidates, deployment limits and the small Samsung S22/Mac comparison results. No new speech backend is claimed in this update.

## 0.22.3 — 2026-09-21 · pipeline ownership audit

- Camera/reading and conversation/typed translation now remember their own local model choices; choosing one no longer rewrites the live-caption translator. “Use everywhere” still updates all three groups explicitly.
- Switching between reading and audio overlays waits for the stopped models to finish releasing, with visible progress and a bounded timeout.
- Clearing typed text, invalid setup and same-language input release the previous translator and workload reservation. Results from a cancelled load cannot start obsolete inference or replace current text.
- Read aloud emits one terminal outcome, preserving the actual failure instead of immediately emitting a successful completion; invalid remote voice selections allocate no client.
- Added native handle-isolation, cancellation/retirement and translation-bridge regression coverage. Common native host checks now run with address and undefined-behavior sanitizers.

## 0.22.2 — 2026-09-21

- Fix the simple captions speech selector retaining a local translator when switching or reselecting Cloud; integrated OpenAI/Soniox translation now wins on explicit provider selection.
- Restart streaming connections on explicit provider reselection, and save the provider mode before notifying the caption service.
- Stop audio admission after socket failure, preserve the first provider error, ignore retired-session callbacks, and finish queued audio before ending provider input.

## 0.22.1 — 2026-09-20

- Selecting OpenAI or Soniox streaming now selects its integrated cloud translation route instead of retaining an old local/text translator override.
- Show the integrated provider by name in caption translation settings, with an explanation that no local translator is required.
- Preserve installed model choices and allow an explicitly selected separate translator afterward; ASR-only cloud connections keep their separate translation stage.

## 0.22.0 — 2026-09-20

- Add English, Arabic and Simplified Chinese interface resources across Home, setup, settings, captions, conversation, text/camera/reading translation, models, downloads and voice controls.
- Add a persisted App language choice, including Follow phone, independent of speech and translation languages.
- Support Arabic right-to-left layout, localized accessibility labels and language-picker search/display names.
- Localize overlay controls, authored notification actions and shared model capability explanations while preserving actual provider errors.
- Check resource coverage and format arguments for all three locales in host tests.

## 0.21.4 — 2026-09-20

- Replace OpenAI's three-language restriction with a shared searchable picker and provider-validated language codes in Easy setup and live captions.
- Read ML Kit language choices from the installed SDK; retain server-discovered translation directions and voice capabilities.
- Remove invented language fallbacks for unknown local models and empty conversation capabilities.
- Expand Paddle OCR and Scribe language metadata to their documented model coverage.
- Bind translation discovery results to the current connection revision, hide stale remote-voice choices, and scope saved keys to their API roots.

## 0.21.3 — 2026-09-20

- Make Easy setup header and Android Back retrace confirmation, form and chooser before leaving.
- Start fresh setup entries at the Local/Cloud chooser instead of restoring a stranded branch.
- Collapse the Home Easy setup entry to a left-edge tab and remember its visibility across restarts.

## 0.21.2 — 2026-09-20

- Reuse reading/camera translations across scrolling with a bounded 256-box session cache.
- Ignore harmless OCR spacing differences when looking up an existing translation.
- Restore known boxes immediately at their new positions before translating new text.
- Keep observing every scroll sample until settled, and prevent obsolete OCR frames from completing a newer capture.

## 0.21.1 — 2026-09-20

- Fix selected local translators being rejected after switching from CC back to Translate.
- Reconcile older saved translation flags and share mode changes across Home, advanced settings and the overlay.
- Open translator setup when translation is missing instead of showing a misleading Start CC button.

## 0.21.0 — 2026-09-20

- Add Easy setup on Home and in Settings, with two large Local/Cloud choices.
- Automatically select Nemotron and Hy-MT2 translation; reuse installed files or download the missing pair.
- Offer simple OpenAI, OpenRouter and compatible HTTPS server forms, plus links to keys/models and full Settings.
- Allow keyless Custom Batch speech servers without forwarding an old provider key.
- Preserve the FOSS offline/import-only setup path.

## 0.20.1 — 2026-09-20

- Make Screen & manga and Camera languages directly editable with separate source/destination cards.
- Automatically choose a compatible OCR reader when changing source language; preserve the translation destination.
- Keep Camera/Reading settings synchronized and allow captured photos to be translated again after changing settings.
- Return Screen & manga to its remembered Home card; nested model/download setup returns to Reading.
- Default new installations to Ink + Dark while preserving existing appearance choices.

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
