# Saved speech overlay captions

Save overlay history is **off by default**, independently of visible previous
lines and read-aloud. It is available from Settings, Speech settings, caption
options and the overlay CC/translation tab. Saved captions opens a dedicated
page with selectable text, renaming, Share, Save TXT, Save JSON and confirmed
individual/all deletion. No extra storage permission is requested; Save uses
Android's document picker.

## Content and provider contract

| Speech path | What is saved |
| --- | --- |
| Local/cloud transcription, optionally followed by a text translator | Original CC plus the translation when it successfully arrives. |
| GPT live translation | Translation only. Original CC is explicitly unavailable. GPT transcription mode remains original CC. |
| Whisper English translation task | English translation only. |
| Whisper English pivot followed by Arabic MT | Translation starts as the English intermediary and becomes Arabic if MT succeeds. Original source speech is never fabricated. |
| Structured provider with independent source and translation runs (Soniox) | Separate run identities; no invented sentence/word alignment. Source-only and translated-only entries remain recognizable. |
| Error, empty final, audio or live partial | No transcript invented. Existing valid text remains; actual errors stay visible. |

The saved pair is independent of the overlay's Original/Translated/Both display
filter, font/height, visible-line count and optional TTS. Known language tags and
engine/input-source metadata are retained per line. Unknown source language is
null; the translation target never becomes an assumed source language.

## Consent, ordering and ownership

`CaptionEngineController` emits finalized/corrected text and translation patches
using stable line IDs. Only the capture service installs the recorder. Other
features retain their existing history ownership. No audio buffer, API key,
model directory, provider request or native handle enters this storage path.

- Enabling saves newly finalized lines, without backfilling visible history or
  an already-observed cloud run. Partials never save.
- Turning off invalidates waiting writes and late corrections/translations.
  Re-enabling does not make those old IDs eligible. A write already committing
  when the toggle happens can finish; consent is rechecked before commit.
- Pause/reconnect, engine restart and Clear establish a new saved-session
  boundary. Clear does not delete older saved sessions. IDs never reset within
  a controller lifetime. Already accepted finals can drain into their old session.
- Translation patches preserve the original field and original timestamp.
  They can update a saved line after it leaves the short overlay display tail.
- Service teardown closes admission and drains accepted writes on an independent
  IO scope. Forced process death may lose still-uncommitted text; committed
  sessions survive app restart.
- Session deletion leaves a tombstone. Queued and future results for that saved
  session cannot recreate it. Clear/restart starts a new session. Delete-all
  also invalidates ownership of queued sessions not yet in SQLite.

## Storage and limits

The SQLite database is `noBackupFilesDir/caption-history.db`, with secure-delete
and transactional upserts. App backup/device-transfer are already disabled.
Text is stored in app-private plaintext; this is not a separate encryption claim.
All SQL runs on serialized IO, outside the audio/NDK/recognition workers.
Transactional usage counters avoid rescanning all text for every saved line.

The queue holds at most 64 validated text events. Each text field is at most
64000 characters; metadata has separate limits. Identity tracking is bounded at
5000 runs, with long sessions split at that boundary. Persistent limits are
200 sessions, 50000 lines and 64 MiB of original/translation UTF-8 payload;
SQLite overhead is additional. Full storage, oversized input, disk or queue
failure stops saving and surfaces the actual reason, while captions and TTS
continue. Export/delete, then deliberately toggle saving off/on to retry.
Old sessions are never automatically evicted.

JSON exports have schemaVersion 1, session IDs/timestamps, stable line IDs,
nullable original/translation fields, language tags and source/engine metadata.
TXT exports label original and translated text, including unavailable original
CC. Sharing uses a scoped FileProvider text file rather than a large Binder
payload. App-private share copies expire after 24 hours on the next share, have
a 128 MiB total budget, and are removed when their source session is deleted.
External exported/recipient copies remain under the user's control.

## Validation boundary

`CaptionHistoryTest` covers the pure routing, consent, identity, patch/export,
queue and failure contract. Both app flavors compile and their JVM suites run.
A host SQLite probe checks production schema/summary SQL, Unicode, rollback,
usage counters and deletion tombstones; it does not exercise Android's SQLite
binding, the document picker or FileProvider grants. No paid provider calls,
adb, device UI or physical streaming tests were run for this change.

Owner checks: enable during a local CC/MT session; verify both fields, then
turn off/on and Clear. Stop/reopen the app and check history survives. Rename,
share, save TXT/JSON and delete a current session while speech continues; it
must stay deleted until Clear/restart. Verify translated-only GPT history when
using that provider normally, plus Arabic RTL and Chinese text, rotation and
cancelled document selection. Those Android integration checks remain open.
