# Hearth 0.23.5 cloud voice review

Based on public main `ad2f470`, reviewed on 2026-10-02. Android version code 78.

## Delivered

Independent caption cloud voices for OpenAI, OpenRouter, Google Gemini,
ElevenLabs and compatible servers; dynamic speech-model/account-voice discovery;
provider-specific voice, speed and style controls; explicit preview and overlay
binding; separate self-hosted UI. Controls are localized in English, Arabic and
Chinese. [Research, setup and protocol limits](cloud-tts.md).

Configuration and encrypted credentials are separate from STT. The existing
legacy voice settings/key are snapshotted once; the known old OpenRouter
Google/`alloy` mismatch becomes `Kore`. Provider and endpoint scope each key.
Cloud config is captured per queued line, with credential resolution on IO.
An unknown local ASR language does not block a provider that infers voice language
from text. Local STT and optional translation keep their existing ownership.

The same bounded caption speaker plays previews and finalized caption text,
with request cancellation, Stop, voice loudness and transient audio focus. Its
MP3/WAV files retain ownership across dispatcher handoffs and are removed on
success/error/cancel. Google REST audio is decoded from actual REST fields, not
an SDK convenience property. Legacy PCM gets one WAV header. Provider/model
voice IDs and unsupported speed/style controls cannot leak across providers.
ElevenLabs' maximum float speed serializes exactly as 1.2, avoiding a value just
above the provider's upper bound. Custom root/nested JSON audio compatibility is
preserved. No retries or silent fallbacks add requests.

Preview/catalog generations prevent cancelled work from clearing a later
request's busy state or client owner. Previews stop when backgrounded/leaving;
configuration errors are notices, not STT-session failures. Native source,
staged libraries, model hashes and C ABI are unchanged.

## Checks actually run

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa`
  — PASS, final run `BUILD SUCCESSFUL in 3m 20s`.
- App: 416 tests per flavor/build type (debug, QA, release), zero failures/errors.
  Play skips one flavor test; FOSS skips eight network/flavor tests. The 18 new
  checks cover provider schemas/formats/filtering/scoping, malformed audio,
  local-server cancellation/headers/redaction/redirects/bounds/offline gate and
  temporary-file cleanup. Common-JNI: 89 JVM tests per tested build type,
  zero failures/errors/skips.
- `python3 scripts/verify-release.py --build-type qa` — PASS for both artifacts;
  native/vendor hashes, dependencies, 16 KiB alignment and flavor permissions.
- `aapt2 dump badging` — both artifacts are 0.23.5 / 78.
- `git diff --check` — PASS.
- Both new string resources contain the same 38 unique keys in en/ar/zh.

QA APKs are debug-signed. Play: 45,065,589 bytes, SHA-256
`ce0d438ac638c226a8920422adc98f3d677411c6d9ef6578d3420a913ef6f501`.
FOSS: 37,082,188 bytes, SHA-256
`187cfec0d73d0d6ab1b7f96b1dae1ff3fa1496cdede6e63101299ac403a2b50c`.

Earlier check failures and their fixes are quoted in `cloud-tts.md`.
No adb/device, paid provider requests, physical audio-focus/capture checks or
voice-quality/latency measurements were used. Keystore/preference migration
requires Android validation. Native host suites were not repeated because no
native code/runtime changed. Existing published releases and the pinned Phonon
model archive are retained. This version is delivered as a GitHub draft.
