# Voice notes draft

Voice notes is a separate Home feature. Recording does not require a speech
model, network connection, overlay permission or account. Microphone access and
recording/privacy acknowledgement are required. Speech transcription is off by
default; translation is a separate option available when transcription is on.
Configuration is snapshotted from speech settings, with note-specific language
choices that never modify overlay preferences. Native/provider failures remain
visible; the saved original text is never fabricated when a provider produces
translation only.

The feature reuses `CaptionEngineController` and its stable-final history hook,
without acquiring the overlay's history database or read-aloud queue. A failed
provider stop callback cannot hold the note forever: its drain wait has a 30-second
cap and always initiates shutdown, recording the actual failure on the interrupted
note. Final/translation events drain or report interruption; native cleanup finishes
before its workload lease is released. `Pcm16WaveWriter` streams 16 kHz mono PCM16
on a dedicated capture IO worker with a fixed byte scratch buffer. Metadata uses
a separate serialized IO worker, so a blocking microphone read cannot monopolize
final-text persistence. Its 64-event queue remains bounded; storage/overflow
failures are visible. Note transcription explicitly enables only its attached private
note sink, independently of the overlay history opt-in. Partials do not enter saved transcripts. Stable IDs
update each finalized line rather than duplicating translated/corrected text.

Recordings live in `noBackupFilesDir/voice-notes`, outside Android backups.
Metadata uses atomic replacement; interrupted recordings are explicitly marked
and recover only complete on-disk samples. Storage is app-private plaintext,
not an encryption claim. Optional device-credential protection is off by default;
Android confirms the user's existing PIN, pattern or password before this screen
opens. Unlock state is never persisted and is cleared when the screen backgrounds.
This is a UI privacy gate, not encrypted storage or an app account; exported files
are outside its protection. A secure device lock is required before enabling it. Limits: 100 notes, 512 MiB total storage, one hour per
recording, 5,000 final IDs and 256,000 text characters per note. Storage errors
stop the operation with their actual reason, without silently evicting notes.
Leaving/backgrounding the feature finishes and saves; this draft intentionally
has no background microphone service. The archive retains previews only; selecting a note loads its transcript lazily,
and transcript lines are lazy list items. Rename/export always load the complete
record instead of writing a preview back over its text. Listen, rename, confirmed deletion and
WAV/TXT/JSON export are available. External exports remain user-controlled.

A local display-name profile is optional and is not authentication. Account
sign-in and cloud sync need the owner's account service selection/endpoint; no
fake login, credentials, upload destination or paid storage account is created.
The draft exposes the local storage/privacy choice clearly. An account backend
must remain optional, feature-gated from FOSS, and never be a requirement for
recording or importing local files.

Verification: Kotlin PCM writer tests and Android-framework storage tests on
API 28/36 cover UTF-8 transcript round trips, late translation/correction IDs,
translated-only content, reopen/rename/delete, invalid paths, bounds and partial
WAV recovery. Scheduler regression coverage holds a blocking capture worker while
metadata still progresses; access-policy tests cover canceled authentication,
background relocking and a new screen instance. Virtual-time drain tests cover a
missing callback, the exact provider exception and cleanup-before-success. Device microphone/playback, provider/model quality and SAF exports
remain owner checks; no physical phone or paid cloud requests are driven here.
