# 0.23.6 review and QA build evidence

Optional speech-overlay history is off by default. Captured content keeps CC
and translations separately where provided, labels translated-only output,
and has app-private viewing, rename, share, TXT/JSON export and deletion.
Recording does not restart recognition/TTS or alter the native audio pipeline.
See [the storage/consent contract](caption-history.md).

Checked on the Mac on 2026-10-03:

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa`
  — **BUILD SUCCESSFUL in 3m 21s**. Final log: `/tmp/hearth-0236-final-gradle.log`.
- App JVM suites: 437 tests per flavor/build (debug, qa, release), zero failures
  or errors. Existing skips: 1 per Play variant, 8 per FOSS variant. Includes
  20 new history tests and one added navigation/restoration test.
- Common-jni JVM suites: 89 tests per debug/release, zero failures/errors/skips.
- `python3 scripts/verify-release.py --build-type qa` — PASS for both APKs;
  native/vendor/dependency verification, 16 KiB alignment and flavor permissions.
- `aapt2 dump badging` — both APKs have version 0.23.6 / code 79.
- Host SQLite 3.45.1 probe of production schema/summary SQL: Unicode rows,
  transaction rollback, deletion tombstones and usage counters — PASS. This
  does not test Android SQLite bindings or document-picker/provider integration.
- `git diff --check` — PASS. All 19 new history resource IDs match en/ar/zh.

Initial compile failures, fixed before the passing final checks:

```
CaptionOverlayWindow.kt:605:13 Unresolved reference 'HorizontalDivider'.
CaptionHistoryScreen.kt:133:70 Unresolved reference 'addFlags'.
```

The divider now uses the correct Material3 reference; Intent clip data is assigned
before chaining `addFlags` (Android `setClipData` returns void).

QA APKs are debug-signed. No adb/device, paid provider request, Android history
persistence/share/document-picker exercise or physical STT/TTS performance
measurement was run. Native host suites were not repeated because no native
source/runtime changed. Owner Android checks are listed in `caption-history.md`.
Existing releases and the pinned Phonon model archive are retained. This version
is delivered as a GitHub draft.

Artifact SHA-256s:

```
da0c2cca48242bab873b07a3d5c5acc923a51de76fa83dfac744ee4d0d9982c0  hearth-0.23.6-play-qa.apk
805888336d2a5239a76934f3c85d374458748a0deaf8d85f0cc5b77a819f2ecb  hearth-0.23.6-foss-qa.apk
```
