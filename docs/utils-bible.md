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
