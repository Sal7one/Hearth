# Hearth 0.8.0 / v18 — conversation layouts and cloud text translation

Date: 2026-09-13. Stable application ID: `com.sal7one.transiber.qa`.

## Delivered

- Direct two-way swap arrow between the existing language buttons.
- Options → Open face-to-face view: upper person rotated 180°, my half upright,
  separate Speak/Finish buttons, theme-based reading areas, per-language history
  opened by tapping each area, and a return to the unchanged conversation list.
- Shared controller, microphone lifecycle, local history, typed input, native work
  gate and offline voice playback. Switching layouts does not recreate the controller.
- Configurable text size, keep-awake, originals, saved history and read-aloud in the
  settings sheet, with model/connection links, typing, rename/share/new/history.
  Settings retain their scroll offset and use an expanded sheet so a connection
  result cannot collapse the form out of view.
- Independent conversation translator: local model, Google Cloud Translation Basic
  v2, Microsoft Azure Translator v3, DeepL Free/Pro, or HTTPS LibreTranslate server.
  No overlay translation preference is changed. See conversation-cloud-translation.md.

## Automated checks

`./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa` passed. App Play QA: 163 tests, no failures;
common-jni Debug: 67 tests, no failures. Full test task also exercises other variants.

Thirteen new cloud tests cover the four protocols, discovered directional languages,
provider wire codes, aliases, unsafe URLs, escaping, malformed/error/empty replies,
key redaction, the offline gate, blocked redirects, cancellation/closed ownership,
and the HTTP response-size bound. The inherited conversation identity/history tests
continue to pass. No native sources or bundled runtime binaries changed.

`python3 scripts/verify-release.py` checks both QA APKs, native hashes/ownership,
16 KiB ELF alignment and network permission separation. QA artifacts are debug-signed.

## Phone checks

Connected Samsung SM-S908E, Android 16 / API 36:

- Installed over 0.7.0, preserving downloaded models and existing speech configuration.
- Direct swap changed Me Arabic/Them English to Me English/Them Arabic; swapped back.
- Opened Face to face from Options; confirmed top rotation and bottom upright,
  dark theme contrast, large controls and accessible button labels in the UI tree.
- Typed synthetic English `Hello` for Them through the selected ML Kit translator:
  top showed `Hello`; my bottom area showed `مرحبا`.
- Tapped both reading areas. Arabic history opened upright with `مرحبا`; English
  history opened rotated with `Hello`. Returned to the original view with the same
  stable turn, original, translation and playback controls intact.
- Final settings sheets open expanded and remain readable after selection/check;
  the new view remained reachable after reinstall/restart.
- A deliberately invalid Google test key was encrypted, read again after process
  restart with an empty replacement field, and produced the exact Google HTTP 400
  `API_KEY_INVALID` response. Removed that test key afterward; no owner key changed.
- LibreTranslate's live `/languages` returned 49 source/target codes. Selected it
  explicitly and submitted synthetic `Hello` without a key. The actual HTTP 400
  API-key requirement was shown; original text remained saved, with no success
  fallback or discarded error. Restored on-device ML Kit afterward. Deleted only
  the synthetic two-Hello test conversation and left a new Face-to-face session.

No valid Google, Azure, DeepL or LibreTranslate billing credential was supplied for
this change. Successful paid translation calls are not claimed; request/response
contracts are fixture-tested, while live Libre discovery and failure handling were
exercised. This check did not re-record a private microphone conversation, measure
translation latency, run a full TalkBack session or establish large-font/landscape
accessibility across all devices. The original audio path remains shared.
