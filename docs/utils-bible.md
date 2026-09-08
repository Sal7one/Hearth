# Utilities and consumers

Extracted from Hearth at 978382d. Keep utility logic independent of Android when practical, with a real consumer and host tests. Preserve error messages and validate at trust boundaries. Never report unsuccessful inference or downloads as success.

- SpeechModelPackage + ModelIntegrity: bounded import, path containment, SHA-256 checks; consumers LocalSpeechModels / ModelRegistry; copied host tests.
- LiveSpeechProcessor + SpeechSession: bounded PCM queue and native session ownership; consumer CaptionEngineController; copied lifecycle tests.
- CaptionReading / OverlayGeometry / transcript helpers / SpeechModelCatalog / Pcm16Resampler: production caption and cloud consumers; copied host tests.
- DownloadSpec: HTTPS URL and basename validation for system download requests; consumer FileDownloads.enqueue; DownloadSpecTest covers path traversal, credentials and invalid URLs.

Network features require ByokPolicy.FEATURE_BYOK; the foss APK has no network permissions and does not delegate downloads to the system. JNI package names remain stable for native bindings. Device checks belong to the owner.

SpeechRuntime.open reports deterministic verification/create stages through an optional callback; CaptionEngineController persists these via CaptionDiagnostics for owner crash reports. No audio or credentials enter the report. Existing native-handle ownership tests also cover failure after allocation.
