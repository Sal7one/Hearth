# Real time transiber — finish for publication

Updated 2026-09-12 after the owner's scope correction.

Ship visible improvements to the working app. Cloud BYOK already works well;
preserve it. No benchmark framework, new telemetry, broad JNI rewrite, architecture
project or research program. Reuse the existing translation interface, importer,
downloader and settings store. Do the normal build checks and a short functional
check of changed behavior; do not create extra tooling to justify a small change.

## Release list, in order

### 1. Easy downloads and installation — RT-01 / RT-07

Status: implemented in 0.2.1; see validation-v5.md for checks and limitations.

- [x] Finish saving new model downloads under `Downloads/Real time transiber/models` on Android 10+; direct files use the adjacent `files` folder.
- [x] Let existing downloaded translation models install directly inside the app. No export or file-picker round trip.
- [x] Show Downloading / Verifying / Installed and one clear next action; keep manual import for externally obtained files.
- [x] Keep hash checking and show actual failures; never mark a partial file Installed.
- [x] Put raw URL/filename entry behind a separate Direct file download action.
- [x] Keep older downloads usable and correct the storage/help text.

Done when: the owner can download, install and select a model without hunting for
files, and an old completed download works without downloading it again.

### 2. Simple bubble controls — RT-04 / RT-05

Status: implemented in 0.2.1; owner confirmation of reading comfort remains.

- [x] Split the panel into **Appearance** and **CC & translation**.
- [x] Appearance opens with Height, Width, Text size, Background and Position; keep Reset position obvious.
- [x] Make Height visibly resize the reading area, separate from moving the bubble higher/lower. Keep the current comfortable size available.
- [x] Remove competing sizing behavior that makes the slider confusing; retain usable drag/close controls and screen-edge clamping.
- [x] Keep mode, languages, translation on/off and selected model in CC & translation. Move keys/downloads/full model setup out of the bubble.
- [x] Save the user's size/position and apply appearance changes without restarting captions.

Done when: the owner can make the bubble shorter or move it lower immediately,
without scrolling through engine settings or losing the current transcript.

### 3. Smaller local translator, easy to swap — RT-02 / RT-03

Status: candidate identified; not integrated.

- [ ] Try the official HY-MT1.5 2-bit mobile variant first if it works with the existing runtime. Its publisher advertises about 574 MB versus our current roughly 1.1 GB Q4 download.
- [ ] Keep the current working model available. Add the smaller choice through the existing translator interface, without an engine redesign.
- [ ] Verify the exact file/hash and do a few real Russian/Chinese → Arabic/English translations; check that switching/off still works.
- [ ] Use a brief side-by-side phone check to see whether it feels faster and remains useful. No benchmark harness, performance dashboard or numerical improvement gate.
- [ ] Consider the advertised 440 MB 1.25-bit variant only if its extra STQ kernel support is a small, safe integration. Otherwise leave it for later.
- [ ] Label download size and supported languages plainly. Do not call an untested option faster, or a new compressed variant a newer base model.

Done when: a smaller working model is selectable and can translate locally, with
honest feedback about its observed responsiveness. If integration becomes a large
runtime project, ship the UX improvements first; it is not a publication blocker.

Sources: [official 2-bit model](https://huggingface.co/tencent/Hy-MT1.5-1.8B-2bit-GGUF)
and [1.25-bit model / kernel requirement](https://huggingface.co/tencent/Hy-MT1.5-1.8B-1.25bit-GGUF).
Publisher sizes are approximate; these remain 1.8B-parameter models. No new speed
claim has been validated by this project.

### 4. Unclutter setup and models — RT-06 / RT-07 / RT-13

Status: implemented in 0.3.0; see validation-v6.md.

- [x] Keep Start visible and show only audio source, CC/Translate, language pair and the chosen Local/Cloud setup in the primary flow.
- [x] Collapse advanced engine options; remove repeated setup sections and permanent diagnostic clutter.
- [x] Show one translation model selector; move larger Q6/Q8 variants into Advanced rather than six competing choices.
- [x] Show Installed / Download / Use clearly, plus language coverage when requested.
- [x] Keep the successful cloud presets and key configuration behavior intact. Only simplify placement when necessary.
- [x] Fix clipped controls, excessive spacing and unreadable Arabic/large-text layouts encountered in these screens; avoid a wholesale design-system rewrite.

Done when: a configured user can start without scrolling through a long technical
form, and can tell which model is active and how to change it.

### 5. Reading comfort — RT-09

Language-picker polish shipped in 0.4.0 (validation-v8.md):

- [x] Shared native-name/flag pickers for spoken CC and translation languages in app and overlay.
- [x] Search in the app, scrollable overlay picker with temporary sizing and restored settings position.
- [x] Use existing model capability sets; connect supported hints and label Auto-only/fixed adapters.
- [x] Expose all local translation targets and preserve old preferences.
- [x] Add picker selection semantics, named sliders, unified switches and accessible bubble movement actions.
- [ ] Complete an auditory TalkBack pass on a device with the service installed; current phone has none.

- [ ] Keep previous text readable and make Previous lines easy to find.
- [ ] Keep history steady while reading, with a clear Back to live action.
- [ ] Make paused/loading/error states visible without sound; keep TTS optional.
- [ ] Preserve readable Arabic text and accessible Stop/Recover controls in the compact bubble.

Done when: captions remain readable long enough, older lines are accessible, and
the bubble does not grow over the video as text arrives.

### 6. Package the improvements — RT-14

- [ ] Run the repository's existing required gates; build and verify both APK flavors for delivery.
- [ ] Briefly exercise the changed flows and confirm saved keys/models survive upgrade. No exhaustive new test infrastructure.
- [ ] Update only affected screenshots, setup/storage instructions and release notes.
- [ ] Deliver the APK and concise device checks; resolve the GitHub destination when actually publishing.

Done when: the owner can install the update and immediately try the improvements.
Do not hold the release for every possible model, language or device combination.

## Immediate overlay corrections — 0.4.2

- [x] Make tap-through a session-only opt-in, ignoring older saved true values on a new session.
- [x] Model-based spoken-language data, explanations next to the field and selectable rows only; no coverage list masquerading as a picker.
- [x] Wire Qwen's already-supported language option through Kotlin/JNI/sherpa, keeping Auto available.
- [x] Read actual local Whisper weight capabilities; never offer multilingual choices for English-only weights or blanket hints for unknown cloud models.
- [x] Restore Clear previous text beside pause/stop; clear the held history view, pending recognition/translation and spoken output.
- [x] Keep local ASR weights loaded on Clear and reconnect cloud recognition to discard the previous video's server-side audio.
- [x] Finish APK/device verification and deliver; see validation-v10.md for the final record.

## Conversation mode — owner-requested plan, not implemented

See [Conversation UI and delivery plan](conversation-mode-plan.md) for the screen
wireframe, permissions, existing-code gaps, failure behavior and acceptance checks.
These are sequential feature slices; they do not block publishing working captions.

### RT-16 — Talk and type in two languages

- [ ] Add one Conversation entry on Home and a dedicated screen with two language chips and Speak/Finish buttons; reuse the current language pickers.
- [ ] Separate in-app capture from mandatory overlay permission/display; explain why Android still requires RECORD_AUDIO for device playback.
- [ ] Validate recognition and translation in each direction using existing capabilities, including Auto-only adapters.
- [ ] Preserve original and translated text under stable turn IDs; resolve the cloud output-only transcript gap before advertising paired history for that route.
- [ ] Add Type instead and visible preparing/listening/translating/error states; serialize speaker changes without loading duplicate large models.

### RT-17 — Keep the exchange readable and saved

- [ ] Save finalized originals immediately and attach translations transactionally to the same turn; record interrupted work truthfully after restart.
- [ ] Add History with continue, rename, delete and explicit text export/share; show the save-history preference and honor it.
- [ ] Keep scroll steady with New messages; preserve position after Options and add a large Show-to-other view.
- [ ] Keep text app-private, exclude it from automatic cloud backup when labeled device-only, and prevent late results from recreating deleted history.

### RT-18 — Speak translated replies

- [ ] Extend existing TTS with readiness/completion/error/cancellation and actual voice-language checks; never substitute an unrelated language.
- [ ] Add per-message Play/Stop and optional final-only automatic speech, initially off.
- [ ] Stop/suspend microphone input during playback and discard buffered playback audio; return to explicit tap-to-speak afterward.
- [ ] Enforce offline voice selection for local mode and explicit BYOK cloud selection; preserve readable text when voices are missing.

### RT-19 — Accessible conversation and delivery

- [ ] Verify TalkBack language/action labels, stable focus, optional final announcements and tap controls without required hold/drag gestures.
- [ ] Check large fonts, independent RTL text blocks, landscape, one-way-only language support and lifecycle interruption.
- [ ] Exercise paired history after restart, translation retry, save-history-off and TTS feedback prevention with focused checks.
- [ ] Run existing gates, both APK flavors and release verification; confirm overlay controls and saved captions configuration still work.

No new benchmark system, telemetry, provider redesign or broad JNI rewrite is part
of this plan. Existing model downloads, key storage and speech bindings are reused.

## OSS model expansion — 0.5.0

See [model expansion plan](oss-extension-plan.md) and [validation-v11.md](validation-v11.md).

- [x] Fix continuous Nemotron speech starving final-only translation.
- [x] Prepare translators early; do not expire the first caption while weights load.
- [x] RT-20: optional play-only ML Kit, explicit pack download/remove and local bridge.
- [x] RT-21: Soniox v5 source/translation streams, encrypted BYOK and parser fixtures.
- [x] RT-22: family-specific coverage and a concrete model contribution guide.
- [x] RT-23: pinned TranslateGemma native family/template, download/import choice and host inference.
- [x] RT-25: Moonshine English local profiles, Tiny phone check; Scribe v2 cloud adapter.
- [ ] Soniox/Scribe account checks; broader pair/device coverage and TranslateGemma joint phone check.
- [ ] RT-24: LiteRT-LM Gemma 4 and alternative Nemotron ONNX/QNN remain optional.

The separate HY-MT1.5 2-bit SEQ artifact fails in the pinned native runtime; the
publisher's required kernel is not available there. Keep it out of the catalog.
Existing HY-MT1.5 and Hy-MT2 Q4/Q6/Q8 remain available. No benchmark/telemetry system.

## Existing work to preserve

Qwen/Nemotron startup fix; optional bounded local translation; foreground-service
controls; retained caption history; encrypted keys; speech-only cloud model
filtering; source/target language metadata; no-network foss flavor. These are
working features, not a fresh list of things to rebuild. The download and bubble changes are recorded in validation-v5.md; versionCode/
versionName alone do not mean an APK was delivered.

## Deferred, not release requirements

- RT-08: broad settings/state refactor. Make only local fixes needed by the visible UI.
- RT-10: benchmark system, telemetry, 30-minute performance program, GPU/NPU backend work.
- RT-11: cloud/BYOK redesign or another provider/model-catalog audit without a reported bug.
- RT-12: new diagnostics/export infrastructure. Retain existing real errors and useful diagnostics.
- RT-13 remainder: full navigation/design-system rewrite and exhaustive localization project.
- RT-15: sign-language recognition research/prototype. Earlier request retained; separate from this speech-app release.

No extra tool or feature gets added just because it might be useful. Each code
change should fix a concrete user-visible issue or serve a named task above.
Retain existing integrity, cancellation and offline rules;
short functional checks do not mean hiding failures or skipping required gates.

## RT-26 — Model library and source navigation (0.5.1)

- [x] Group simple setup as Speech, Translation and Cloud; keep Whisper/Vosk in Speech and Marian in Translation.
- [x] Put publisher, file links and installation steps beside the matching model. Label publisher archives requiring packaging.
- [x] Group translation quantizations by family; distinguish browsing from the active translator.
- [x] Show the selected cloud provider’s key and docs; collapse unrelated batch/voice controls.
- [x] Reuse source/install panels in advanced setup and expose local translation for compatible cloud STT.
- [x] Keep the model groups reachable while scrolling; preserve each group’s scroll position.

RT-30 now installs catalogued publisher archives directly on the phone. Existing
manifest/hash/path checks are retained; a user-created ZIP is no longer needed.

## RT-27 — Language picker opening position (0.5.2)

- [x] Open app, overlay and language-pack lists at their current selection.
- [x] Start filtered results at the top; return to the selection when search is cleared.
- [x] Preserve manual scrolling while the picker remains open.

## RT-28 — Cloud connection spacing (0.5.3)

- [x] Minimum 2.dp top padding and consistent 8.dp vertical gaps for shared cloud settings.
- [x] Wrap provider/action rows; space documentation buttons and model details.

## RT-29 — Direct model-family chips (0.5.4)

- [x] Show all supported speech families as quick chips within Models → Speech; open on the active engine.
- [x] Show translator families as quick chips in shared translation setup, with only the browsed family's controls visible.
- [x] Put Marian's legacy import behind its own chip; keep active model labels distinct from browsing.
- [x] Retain the active translation quantization when returning to its family; wrap chips with accessible selected states.

## RT-30 — Public downloads and on-phone model installation (0.6.0)

- [x] Download originals directly to Downloads/Real time transiber, with a persistent custom-folder picker.
- [x] Keep download / installation progress in a foreground notification across page navigation.
- [x] Automatically verify and unpack catalogued Moonshine/Qwen archives and install Nemotron GGUF; generate internal metadata on the phone.
- [x] Automatically install catalogued translation GGUF downloads; retain an explicit Use model action so background completion cannot change an active session.
- [x] Remove export/re-import instructions and computer packaging steps from supported model cards.
- [x] Identify Moonshine's exact February 2026 runtime package; do not confuse the release-collection page's date with its asset date or advertise the latest streaming architecture.
- [ ] Additional one-button catalogs for legacy Whisper/Vosk and custom Qwen 1.7B exports remain separate; their existing imports still work.
