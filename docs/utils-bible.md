# Utilities and consumers

## Hearth 0.12.0 reading additions

- `OcrEngine` is consumed by CameraOcrController with Paddle and Japanese native implementations.
- `ReadingSelection` maps the DrawOcrRegion UI through fitted-image letterboxing;
  host tests cover clipping, reversed bounds, tiny/non-finite selections and model completeness.
- `OcrTokens` is the JapaneseOcr native-output consumer; tests reject invalid vocabulary
  IDs and Unicode values and preserve supplementary characters.
- `japanese_geometry.h` character suppression is consumed by native Meiki recognition;
  ASan/UBSan checks cover duplicate predictions and invalid intervals/code points.
- Explicit captured-text observations retry even if previous identical OCR was committed;
  the CameraOcrController consumer and OcrPolicyTest cover the manual/live distinction.

Extracted from Hearth at 978382d. Keep utility logic independent of Android when practical, with a real consumer and host tests. Preserve error messages and validate at trust boundaries. Never report unsuccessful inference or downloads as success.

- SpeechModelPackage + ModelIntegrity: bounded import, path containment, SHA-256 checks; consumers LocalSpeechModels / ModelRegistry; copied host tests.
- LiveSpeechProcessor + SpeechSession: bounded PCM queue and native session ownership; consumer CaptionEngineController; copied lifecycle tests.
- CaptionReading / OverlayGeometry / transcript helpers / SpeechModelCatalog / Pcm16Resampler: production caption and cloud consumers; copied host tests.
- DownloadSpec: HTTPS URL and basename validation for system download requests; consumer FileDownloads.enqueue; DownloadSpecTest covers path traversal, credentials, invalid URLs, unique filenames and contained public download paths. FileDownloads consumes this destination policy and installs completed translation downloads through the same verified LocalTranslationModels import used by the file picker.

Network features require ByokPolicy.FEATURE_BYOK; the foss APK has no network permissions and does not delegate downloads to the system. JNI package names remain stable for native bindings. Device checks belong to the owner.

SpeechRuntime.open reports deterministic verification/create stages through an optional callback; CaptionEngineController persists these via CaptionDiagnostics for owner crash reports. No audio or credentials enter the report. Existing native-handle ownership tests also cover failure after allocation.

VoskApi is the VoskEngine consumer's process-lifetime, RTLD_LOCAL C-function loader. It keeps the legacy Vosk static C++ runtime out of common JNI's dependency scope. Its host fixture checks local symbol visibility, calls, and verbatim loader errors; APK verification rejects a direct libvosk dependency.

TranslationCatalog declares pinned model identities/directions and prompts; LocalTranslationSetup and LocalTranslationSession consume it. Catalog tests cover supported pairs, unknown codes, aliases and input limits. LocalTranslationModels reuses ModelIntegrity for verified atomic GGUF import, with corrupt/partial import coverage. CaptionTranslationBridge is the final-only bounded queue used by CaptionEngineController; host tests cover stable IDs, overload, unknown/unsupported languages, stale output, cancellation during load and cancellation during inference. LocalTranslationSession implements the existing SpeechTextTranslator contract; its JNI runtime uses LeaseRegistry for cancel-before-retire ownership. See local-translation.md.

OverlayGeometry.overlayHeightPx is consumed by CaptionOverlayController. Host tests cover legacy-size migration, explicit sizing independent of the old percentage, tiny/rotated viewport bounds and persisted height without changing transcription mode.

LanguageCatalog provides display/search/RTL metadata to the shared app and overlay
language pickers; host tests cover native names, accent-insensitive search, locale
independence and model coverage. CaptionLanguages consumes existing speech and
translation capabilities in the picker and CaptionEngineController; host tests cover
supported native hints, Auto-only/fixed adapters, cloud mode differences, target
persistence/migration and temporary overlay sizing. See language-pickers.md to extend.

placeTapThroughHandle is consumed by CaptionOverlayController's independent recovery
window. Host checks cover placement outside caption text and reachability at screen
edges, full-screen and tiny/rotated viewports. The same controller owns both windows
and removes the handle on restore/hide/stop; the existing service provides notification
recovery. See validation-v9.md for the verified tap/drag/notification/restart sequence.

Version 0.4.2: SpeechCapabilities exposes SpeechSourceLanguage records consumed by
CaptionLanguages and both UI surfaces. whisperVocabulary reads eight header bytes
for the selected local weights; tests cover English-only/multilingual/Cantonese,
renamed weights, invalid magic and truncation. Qwen's native qwenLanguageName maps
validated ISO codes to the prompt's language names; SpeechConfig and Qwen's decoder
consume it, with native host checks. SpeechRuntime forwards the selected code.
LiveSpeechProcessor.reset is consumed by Clear captions: a serialized reset command
suppresses old in-flight/queued results and keeps weights loaded. Host tests cover
reset during inference, discarded queued PCM, zero-based new audio, repeated reset
and Stop during reset with exactly-once release.

Version 0.5.0: EndpointBudget is consumed by the native Nemotron session to request
an endpoint during uninterrupted speech. Native host checks cover silence, natural
final/reset and decoded-sample limits. CaptionTranslationBridge prepares once and
excludes startup loading from queue expiry; its tests exercise captions arriving
while load takes longer than the age cap. SonioxTranscript feeds StructuredCaptionClient
with independently revised source/translation runs; tests cover interim replacement,
final grouping and endpoint flush. ScribeEvent feeds ElevenLabsStreamingClient;
fixtures cover revised partials, timestamp-event deduplication and verbatim errors.
CaptionLanguages/CaptionTranslationRoute regression tests cover forced Russian,
fixed English and cloud direct-translation precedence. Moonshine uses the existing
verified manifest/import utilities with Kotlin and Python missing-role/file checks.

Version 0.5.1: ModelSources is consumed by ModelSourcePanel in the simple model
library and advanced setup. Catalog tests require source/install information for
every native profile, validate direct-download inputs with DownloadSpec, and prevent
the custom Qwen 1.7B / cloud paths from claiming ready-made downloads.

Version 0.6.0: PublisherSpeechPackage is consumed by LocalSpeechModels and the
foreground downloader. It streams pinned publisher archives into the existing
ModelIntegrity staging sink, validates raw size/digest, rejects links, traversal,
duplicate/foreign paths and injected metadata, and runs SpeechModelPackage.verify
before publication. Host fixtures cover those rejection paths, cancellation,
missing assets and raw GGUF. SpeechDownloads supplies exact catalog artifacts.
Downloads write directly through MediaStore or the user's persisted SAF tree;
no temporary private copy/export is used for original downloads.

## Hearth 0.7.0 additions

- `BenchmarkAudio` / `BenchmarkMetrics`: production benchmark runner and UI; host tests cover bounded WAV parsing, downmix/resampling and comparable timing arithmetic.
- `LocalWorkGate`: caption service, conversation and benchmark prevent overlapping native workloads; host test verifies rejection and stale lease safety.
- `CaptionDrainPolicy`: controller Stop preserves buffered finals and pending translations; host tests cover prior silence, late translation and hard timeout.
- `CaptionTranslationBridge.reset`: Clear keeps loaded weights while invalidating old work; host tests cover queued/in-flight resets, load races and stale failures.
- `ConversationData` stable-ID updates, restart interruption and export are consumed by conversation controller/store/screen, with host coverage.

## Hearth 0.8.0 additions

- `CloudTranslationProtocol` / `CloudTranslationLanguages`: production cloud conversation
  adapter and capability-driven pickers; host tests verify provider request contracts,
  authentication, language aliases/directions, unsafe URL rejection and actual errors.
- `TranslationHttpTransport`: discovery and conversation translation consumer; host tests
  verify the offline gate, status/body preservation, credential redaction, blocked
  redirects, coroutine cancellation/closed ownership and bounded response size.
- `FaceToFacePanel` is a presentation-only consumer of the existing conversation state,
  with no new microphone, persistence or native ownership path.

## Hearth 0.9.0 additions

- `OcrCatalog` / `OcrModels`: camera setup and foreground download installation;
  tests cover pinned identities, actual language groups, invalid import preserving
  the installed file, and cancelled staging cleanup. Runtime open rechecks hashes.
- `OcrStability` / `OcrText`: camera pipeline consumers; tests cover live settling,
  blank/reset transitions, immediate capture and Arabic ordering/number preservation.
- `ocr_geometry.h`: production native detector/CTC consumer; ASan/UBSan host checks
  cover expansion, clipping, noise rejection and CTC repeat/blank semantics.
  `PaddleOcr` additionally consumes the shared, host-tested lease registry.

## Hearth 0.10.0 additions

- `VoiceText`: production Supertonic tokenizer input and read-aloud chunking; host
  tests cover Arabic/NFKD, unsupported text/languages and surrogate-safe boundaries.
- `PcmWave`: remote TTS and benchmark audio consumers; tests cover sample rates,
  signed stereo downmix, malformed headers, bounds and existing benchmark behavior.
- `VoiceModels`/`VoiceCatalog`: actual download/import and native load consumers;
  pinned SHA/size, invalid/cancelled/unsafe ZIP imports and no partial-ready state.
- `RemoteVoiceProtocol`/`RemoteVoiceClient`: setup discovery and shared player;
  tests cover capability checks, credentials, offline gate, redirects, actual
  errors/redaction, size bounds and cancellation/closed ownership.
- `TypedTranslationController`: typing page consumer; tests drive the production
  scheduler through debounce, conflation, in-flight stale output, background/resume,
  model closure, local workload lease and original error propagation.
- `voice_bounds.h`: native Supertonic consumer; sanitizer host checks cover invalid
  tokens/styles/speed/steps/duration and latent/audio length geometry. The JNI adapter
  also consumes the existing tested lease registry.

Version 0.10.1: `VoiceSelection` is consumed by settings, shared manual playback
and caption Custom default. Host tests cover independent Android/custom/default
routing, existing selection migration, model changes and rejection of unconfigured
or offline-inaccessible custom engines. `ReadAloudButtons` reuses this selection
across typed text, camera, conversation/history/presentation and voice preview.

Version 0.11.0: `AppNavigation` is consumed by MainActivity's five bottom tabs.
Host tests cover independent nested paths, reselect/back, all existing deep links,
loop-free model/download navigation and saved-state restoration/validation.

## Screen reading

`ReadingTrigger` and `ReadingMotion` are consumed by `ReadingOverlayService`. `ReadingTriggerTest` covers settling, rate limits, retired scroll-mode migration, unknown-mode safety, reset; `ReadingMotionTest` covers movement in uncovered reader content, handle/system exclusions, dragged-handle masks, noise and avoiding publication feedback loops. The existing `ReadingSelection` mapping is reused by both still-photo and cross-app drawing. Hardware OCR adapter coverage is in `JapaneseOcrDeviceTest`; no weights enter the repository.

`OcrPageTranslation` is used by `CameraOcrController` in reading-page mode to keep
provider output attached to each source box. `OcrPageLayout` is used by
`ReadingTranslationView` to map crop coordinates into the live screen. Ten host
tests cover duplicate/multiline responses, immutable progressive results, stale
inference, exact provider failures, empty outputs, work limits, crop/letterbox
mapping, vertical boxes and invalid/overflowing native coordinates, and clipped glyph-cover margins.

## Hearth 0.15.0

`TranslationOptions.supports` is used by caption target choices; host cases verify
unknown model, unsupported source and same-language rejection. `CaptionLanguages`
uses discovered cloud direction data instead of local-model defaults.
`CaptionTextTranslatorTest` covers explicit routing, preserved defaults and config
round-trips. `CloudCaptionBridgeTest` exercises the existing production queue with
the real cloud adapter and an intercepted response, preserving source segment ID.
The shared `TranslatorChooser` is used by every translation feature; no new native
interface or model manifest was needed.

`captionTranslationSource` consumes the source validated with actual model metadata
at engine load. Host checks prevent forced Whisper language from being erased by
a model-less lookup and prevent guessing a language when neither source is known.

## Hearth 0.16.0

`SettingsDestination` supplies the Local/Cloud destination list to `SettingsScreen`
and `SettingsTabs`; host cases cover the FOSS cloud exclusion and local-only OCR.
Focused settings composables reuse existing tested stores, imports and transports.
No native API, model integrity rules or inference scheduling changed.

`SettingsTabState` is consumed by the shared tab container. Regression cases keep
explicit directory entries distinct from Back/return restoration, and clamp
restored Cloud state in FOSS. Per-feature entry revisions do not touch engine
configuration; per-tab Compose state holders retain scroll positions.

## Hearth 0.17.0

`checkOverlaySetup` is consumed by the tile launch activity's preflight. Host tests
cover all-failure collection, verbatim errors, empty/native-failed reports,
readiness and cancellation without subsequent work. `checkSpeechAssetPresence`
is the quickstart consumer's bounded presence/size check; filesystem tests cover
missing/truncated assets, traversal and links. It does not replace runtime hashes.

`OverlaySetupVisibility` is consumed by the quickstart activity, caption overlay
controller and reading service. Its ownership test covers overlapping activities,
repeated close and stale release; temporary UI suppression never changes saved
caption settings or stops the existing session.


## Hearth 0.18.0 appearance/navigation

`minimalPalette` is consumed by the shared `HearthTheme` palette selection.
`MinimalPaletteTest` checks foreground contrast for light/dark surfaces and primary
controls, the Ink dark background, saved classic identifiers and defaults.
`AppNavigationTest` also covers the new appearance/shortcut destinations returning
to their originating feature and surviving save/restore. No native utilities changed.


## Hearth 0.19.0 home selection

`HomeService.restore` and `forPage` are consumed by the carousel and MainActivity.
`HomeServiceStore` persists only the stable service ID. Host tests cover stored
identities, missing/unknown fallback, ID uniqueness, the two conversation layouts
and exclusion of settings pages from last-feature updates. Phone validation covers
preference restoration through Home and process restart.

## Hearth 0.20.1 OCR language controls

`OcrSelection` and `ocrTranslationTargets` are consumed by both Camera and Reading
setup, with target validation also used by ReadingOverlayService. Five host tests
cover source-driven reader changes, preserving explicit Japanese engines and saved
destinations, cloud directional restrictions, absent capabilities, unknown models
and FOSS pack restrictions. `OcrPreferences` observes the existing shared preferences
so one screen cannot overwrite another's choice just by returning to the foreground.
Camera releases active inference before changed settings are used and preserves the
captured bitmap for an explicit retry. Capture/retry callbacks snapshot the current
selection at invocation rather than retaining an earlier composition’s profile. No native API or language claims changed.

## Hearth 0.21.0 Easy setup

`EasySetupPreset` is consumed by `EasySetupActions` and the setup UI. Host cases
cover Nemotron/text-translation routing, OpenAI live versus batch caption routing,
preserved user appearance/audio choices, credential scope and invalid endpoints.
`cloudSpeechKeyRequired` is consumed by setup, caption readiness, preflight and the
engine loader. All public/streaming routes require keys; Custom Batch can omit one.
The real HTTP client has a MockWebServer test for omitted/authenticated headers and
the FOSS no-request gate. No native inference implementation was changed.

### Caption translation selection (0.21.1)

`CaptionOverlayConfig.withCaptionMode` reconciles the legacy bridge flag with the
requested mode and selected translator. Used by persisted reads/updates, route
resolution and Home/advanced/overlay mode controls. CC remains original-only;
integrated cloud translation retains priority without an explicit text provider.
`CaptionTranslationSelectionTest` covers old saved false flags, CC/Translate
round-trips, language/route agreement and integrated-provider preservation.

### Stable page translation (0.21.2)

`OcrTranslationCache` is scoped to one CameraOcrController session (fixed OCR
language, translation provider/model and destination). It replaces the 32-entry
map with bounded LRU storage (256 entries, 96,000 source+result characters),
canonical Unicode/whitespace keys and no fuzzy word/number matching.
`OcrPageTranslation` consumes its lookup to publish all cached boxes before new
inference, always using fresh OCR geometry. Cache fixtures exercise a 64-box
page after scroll, duplicate text, incremental publication, changed wording,
eviction, blank/oversized responses and session isolation. `ReadingMotionTest`
also drives the real settle trigger over consecutive scroll frames.

### Easy setup back navigation (0.21.3)

`EasySetupStep` is the pure step contract consumed by MainActivity's shared header/
Android Back dispatcher and the setup UI. Four host cases verify full local/cloud
back chains, completion boundaries and serialized step restoration. Navigation
state contains no form values or credentials. `HomeServiceStore` additionally
persists the presentation-only collapsed flag; device checks exercise both values
across process restart instead of duplicating SharedPreferences implementation.

### Shared language capabilities (0.21.4)

`CaptionLanguageChoices.accepts`, `customCode` and `pickerCodes` are consumed by
shared caption/setup selection and validation. `CloudSpeechLanguages` centralizes
provider metadata and ISO-name suggestions without claiming language discovery.
`OpenAiTranslateClient.sessionUpdate` is the production session payload builder;
the language tests check its actual output after the Easy setup/preferences path.
`PlatformTranslation.languages` comes from the Play SDK and is empty in FOSS.
`LanguageCapabilitiesTest` covers arbitrary-code boundaries, saved selections,
unknown models, discovered translation directions, flavor isolation and OCR reader
selection. Fixed model catalogs remain authoritative for local inference.

`CloudTranslationProtocol.sameEndpoint` and `RemoteVoiceProtocol.sameEndpoint`
are used by connection settings to scope credential reuse; protocol tests cover
normalized roots, distinct paths/hosts and rejected insecure URLs. Translation
capability writes require the saved connection generation so a late response cannot
restore stale language choices after another save/forget. No inference or native
API was changed. See [capability inventory](language-capabilities.md).


## 0.22.0 — interface localization

`UiText` resolves Android resources for Compose, callback and service consumers without
retaining an Activity. `UiLocaleProvider` supplies locale/RTL configuration to service
Compose windows; `HearthTheme` supplies it to ordinary screens. `UiLabels` translates
presentation labels without changing stored enum IDs, model identifiers or request data.
`UiMessage` defers capability-message localization until display and retains a pure
English diagnostic representation for existing host consumers. `CaptionLanguages` is
its production consumer. `LocalizationResourcesTest` checks all locale keys, blanks,
format arguments and the nested, context-free diagnostic representation. Phone journeys
exercise `AppLanguageSettings`, persistence and RTL. See [localization](localization.md).


## 0.22.1 — explicit cloud selection

`selectCaptionEngine` is consumed by advanced caption engine chips and the shared
Cloud speech connection picker. It clears a previous text translator override only
when the user explicitly selects an integrated OpenAI/Soniox connection. It keeps
remembered local weights and permits a later explicit override. `integratedCaptionProvider`
is also the caption translator chooser's provider label source. `CaptionCloudSelectionTest`
covers stored round trips, actual route/picker agreement, CC → Translate, deliberate
separate translators and ASR-only connections. Opening a screen does not apply a preset.

## Hearth 0.22.2

The simple caption settings now consumes the same `selectCaptionEngine` transition
as advanced/cloud setup. A persisted speech selection revision makes connection
changes observable even when the engine remains Cloud. Host cases cover retained
local overrides, Auto → Spanish, reselection, and preference round trips.
`StreamingCloudEngine` is the production lifecycle owner; fake-client concurrency
tests cover shutdown during send, late callbacks/connection acknowledgement, first
provider error preservation, failed-socket admission and queued tail drainage.


## Hearth 0.22.3 pipeline audit

- `TypedTranslationController` now retires blank, invalid and same-language work; production scheduler tests cover clearing during inference/load, lease retention until cleanup, and stale-error/result suppression.
- `OcrSelection.translationModel` is consumed by Camera, reading setup/service and shortcut diagnostics. A scoped choice survives caption-model and OCR-reader changes; old preferences retain their existing model fallback.
- `LeaseRegistry` is consumed by speech, OCR, translation and voice JNI adapters. The sanitizer host suite now checks independent handles during blocked retirement, cancellation isolation, stale handles and move-assignment of the last lifetime-bearing lease.
- `CaptionTranslationBridge` has two-owner regression coverage: closing a previous bridge cannot cancel another or publish its old result into it.

`LocalWorkGate.awaitIdle` is consumed by the explicit audio-to-reading handoff. Host
coverage verifies it waits for model cleanup and cancelling a waiting page never
unlocks another owner. The reading controller still acquires its own lease.

## Model comparison and download review · 2026-09-23

`BenchmarkSuite` supplies pinned, hash-checked FLEURS cases to
`LocalBenchmarkScreen`; `BenchmarkAudio.normalizeForComparison` is consumed by
`LocalBenchmarkRunner` and its host test covers quiet speech and silence.
`BenchmarkScoring` is consumed by saved result cards and has normalization/error
metric fixtures. The selected production adapters supply the actual timings;
phone evidence and limits are in `docs/benchmark-phone-smoke-2026-09-23.md`.

`SpeechArtifactCatalog` and `WhisperDownloads` supply pinned variants to
`SpeechArtifactBrowser`, which uses digest identity for installed Whisper files;
catalog tests cover grouping, HTTPS revisions, hashes and language scope.
`DownloadBudget` and `DownloadResume` are used by the foreground
`DownloadService`; host tests cover capacity arithmetic and range validation.
`FileDownloads.progress` makes pause/removal take priority over late transfer
updates. A completed phone transfer exercised the hash/install path; interrupted
network resume still needs a physical test.

## Hearth 0.22.6 source-language recovery

`source_language.h` is consumed by the Nemotron native adapter. It compares
locale tags by primary language, so `ru-RU` and `ru` do not become a false
mixed-language result. The speech host suite covers matching, different and
empty codes. `TranslationSourceEvidence` is consumed by the shared
`CaptionTranslationBridge` when an ASR final has no usable language code. It
accepts a source only if a selected translator direction and the caption's
non-Latin script agree uniquely. Host tests cover Russian recovery, ambiguous
Cyrillic directions, Latin text, target mismatch and explicit-source priority.
Other unknown or genuinely ambiguous captions remain CC with the original
source-language error.

`DownloadIntegrity.matches` is consumed by the Play foreground downloader both
after transfer and before retrying installation from a previously completed public
file. Its host test covers valid, changed, truncated and oversized bytes and
cancellation. A failed recheck makes Retry start a fresh transfer, while a valid
original avoids wasting another model download. The phone has verified a normal
transfer and pause/resume; corrupt-file retry and custom-folder recovery remain
device checks.
