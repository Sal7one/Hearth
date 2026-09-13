# Utilities and consumers

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
