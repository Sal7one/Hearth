# 0.6.0 / versionCode 16 — public downloads and automatic model installation

Catalogued Moonshine Tiny/Base, Qwen3-ASR 0.6B, Nemotron and translation GGUFs
now have Download & install. The play-only data-sync foreground service writes
originals directly into MediaStore Downloads/Real time transiber or a persisted
SAF folder chosen in Downloads. Each job captures its destination when enqueued.
Model files use models/, direct URLs use files/. Android 9 requires a selected
folder instead of falling back to hidden external app storage.

The service serializes transfers, retains progress/errors across navigation,
redelivers interrupted work where Android permits, and exposes Retry after an
interrupted process. Completed downloads are retained when an installation needs
retry. Background completion installs but does not change active speech/translation
settings. Downloads includes Use model, Open file, Open folder and deletion;
Export file is removed. Existing DownloadManager records remain readable and
catalogued older downloads can be installed directly without export/re-import.

PublisherSpeechPackage verifies exact archive size and SHA-256, rejects unsafe
paths/links/duplicates/foreign roots/metadata injection, applies file/entry/byte
limits, generates the internal manifest, and invokes the existing speech verifier
before publishing. Known publisher tar.bz2 / GGUF imports also work from the local
file picker. ZIP imports keep their existing validation. The archived source and
verified installed files are separate: installed models remain app-owned for native
runtime access. ML Kit's SDK-managed language packs are explicitly described as
app storage. No native inference or cloud-provider settings were changed.

On-phone testing found unbuffered compressed-input reads; a 64 KiB buffer now
prevents one content-provider file-descriptor read per compressed byte. A host
regression fixture fails if an individual-byte read reaches the underlying source.
Cancellation coverage checks cleanup after a partially written asset, independent
of the number of read callbacks.

## Validation

- App regression suites: 138 tests per six variants, zero failures/errors.
- Common JNI JVM suites: 63 tests per two variants, zero failures/errors.
- Speech native host suite: 50 checks PASS; common native utility suites PASS.
- Both QA Kotlin compiles / APK builds and release verification PASS; final build
  details and byte sizes are recorded below.
- Samsung SM-S908E / Android 16: downloaded Moonshine Tiny from its real publisher
  URL into public Downloads; installed without an import picker or computer.
- Chose Device/Download/Real time transiber through Android's folder picker and
  granted access. The choice persisted through an APK update and app restart.
- Downloaded the 29,858,559-byte archive again through the SAF path using the
  buffered installer; it installed successfully while navigating between screens.
  Both the app and Android filesystem show the original archive at the selected
  public folder. Open folder opens Samsung My Files at that directory; the archive
  is visible under models. No export/re-import was used.
- Download deletion removed the test's first original without uninstalling its model.
  The final original remains available. Speech remained Nemotron / Russian, target
  Arabic, ML Kit unchanged; capture stayed stopped.

These are installation/storage checks, not new recognition or translation quality
measurements. Qwen/Nemotron/translation adapters have host/compile coverage here;
only Moonshine's live publisher download was exercised on the phone this turn.
Legacy Whisper/Vosk catalogs and custom Qwen 1.7B still use their existing manual
imports; this release does not claim one-button downloads for every custom model.

Interrupted publisher installs reuse their job's staging directory; retry clears
the incomplete extraction before writing. The host fixture seeds a partial model,
retries successfully, and verifies a repeated install of the same job keeps one
published model. Network/SAF tests cover successful transfers; network interruption,
revoked-folder grants and Android 9 are not separately device-tested this turn.

Final delivery gates: BUILD SUCCESSFUL in 1m15s; 268 tasks (47 executed,
221 up-to-date). Final release verifier passes native inventories/hashes,
dependencies, 16KB alignment and play/foss permissions. Play APK 33,037,304 bytes;
foss APK 24,655,781 bytes. Both include Apache Commons dependency license/notice
assets. Version 0.6.0 is installed on the authorized test phone.
