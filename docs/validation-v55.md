# 0.22.13 QA validation — download integrity and explicit duplicates

Downloads are idempotent by URL and model ID. Repeating a request reuses the
existing record and asks the foreground worker to verify completed bytes before
restoring its Complete/Installed state. New transfers (including direct URLs)
stream to the chosen public folder and save SHA-256 plus byte count. Catalog
models must also match the publisher's pinned size and SHA-256. A direct URL's
saved checksum detects later local edits but cannot authenticate what the server
originally sent.

Downloads → Check file shows the actual original and installed-model state, the
location, and any underlying filesystem error. A repeated direct URL opens the
same choice. Keep preserves the existing copy; Repair/restart reuses a verified
original for reinstall or explicitly starts a fresh transfer; Download another
copy creates a separate public record. A corrupt completed file is not silently
overwritten. Speech packages, GGUF files, Marian folders, Whisper models, OCR
files and voice files are checked before selecting from Downloads. The speech
and GGUF installers can replace damaged app-private copies from a verified
original.

Host checks passed on 24 September 2026: `./gradlew test
:app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa
:app:assembleFossQa` (BUILD SUCCESSFUL); `python3 scripts/verify-release.py`
(Play QA 44,808,198 bytes, FOSS QA 36,859,909 bytes; native alignment and
flavor permissions PASS).

Owner device checks:

1. Download a small pinned model, tap Check file, and confirm Original and
   Installed both say Verified. Tap the same model download button again: the
   same record should enter Verifying then return to Installed.
2. In Downloads, tap Check file → Download another copy. Confirm two public
   records and two public files with independently verified bytes.
3. For a direct HTTPS file, repeat the same URL. Confirm the choice dialog
   appears. Edit or delete the downloaded public file in My Files, then Check
   file again: the result should say Changed or Missing and offer an explicit
   restart. No old file should be overwritten until that choice is made.
4. Damage a disposable installed model file while leaving its original intact.
   Check file should report a healthy original and damaged install. Repair
   should reinstall from the original without another network transfer.
5. Repeat with a custom SAF folder, then revoke its permission. Check file
   should surface the actual permission/filesystem error. Confirm FOSS still
   exposes no download action or network permission.

No device was driven by the coding agent.
