# Hearth

This is a standalone speech/caption app extracted from Hearth. Keep scope to local
speech models, cloud speech/translation, overlay, camera/image OCR translation,
model import, direct downloads, typed translation and shared TTS. Camera OCR and
TTS/typed translation are owner-approved scope expansions.
Windows/macOS desktop support through KMP, native media audio input and the
loopback external-app/OBS interface are also owner-approved. Keep Android
platform screens/services intact while sharing portable contracts and SDK code.
Do not reintroduce the media editor, FFmpeg, sign recognition, books or yt-dlp.

- `foss` has no network permissions. Gate clients AND system download delegation with
  `ByokPolicy.FEATURE_BYOK`. Never send provider API keys to model/file hosts.
- Surface actual engine/provider errors. Never convert a failure into empty success.
- Native bindings retain the `com.sal7one.common_jni` package. Preserve native handle
  ownership, bounded audio queues, pinned model integrity and exact-once final text.
- Model ZIP manifests live at the archive root. Do not weaken archive limits/path checks.
- Utils have a production consumer and meaningful host coverage; see docs/utils-bible.md.
- Before committing: `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin`.
  For native changes also run the speech/common host suites and externalNativeBuildDebug.
  Before APK delivery build both QA variants and run `python3 scripts/verify-release.py`.
- For desktop changes also run `./gradlew -PhearthDesktopOnly=true :hearth-core:desktopTest
  :desktop-runtime:test :desktop-data:test :desktop-media:test :desktop-integration:test
  :desktopApp:desktopTest`. Build native helpers first for full host coverage. Validate
  installers with `python3 scripts/desktop/check-packaged.py` on each matching OS.
- The owner device-tests. Do not use adb or drive their device unless explicitly asked.
- Never commit keys, signing stores, local.properties, model weights, generated builds
  or a parent project's Git history. QA APKs are debug-signed test artifacts.
