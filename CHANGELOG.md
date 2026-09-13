# Changelog

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
