# Desktop owner-test repairs — 22 October 2026

This follows the [post-handover audit](post-handover-audit-2026-10-22.md).
The owner's live Mac test exposed important gaps that the earlier small render
fixtures missed. The earlier desktop “feature-complete” description is not an
acceptance result. These changes keep Android screens/services and the one
`:hearth-tts` module intact. Windows remains parked.

## Downloads and model ownership

- Two model downloads run concurrently; additional model jobs queue visibly.
  Import verification has a separate single-worker queue and remains available
  during downloads. Duplicate work for the same model is rejected without
  replacing its active owner.
- The application-wide activity bar survives navigation. It shows the current
  phase and byte counts and opens Downloads. Individual jobs can be cancelled,
  including queued jobs; permits remain held until real cleanup completes.
- Downloading, checking existing files, unpacking and final verification have
  separate progress. Qwen's 878,702,423-byte compressed package expands to
  987,015,347 pinned bytes; decompression is no longer labelled as an unexplained
  verification wait. Progress publication is capped at ten updates/second plus
  phase changes/final counts.
- Library locks are per model. Installation map changes are atomic. A stale
  refresh cannot overwrite a newer external import, and a damaged installation
  loses its ready status before a repair attempt. Healthy unpacked models are
  verified and reused without downloading their deleted transport archive.
- Archive cancellation checks run throughout extraction/tree hashing. Existing
  archive path, hash, size, entry-count and staging checks remain enforced.
  Repair downloads missing/changed files; it does not activate a model.

## One saved setup and usable controls

- Actual engine names and runtimes distinguish GGUF/llama.cpp from CTranslate2.
  STT/TTS/translation choices wrap in a grid with readable widths and at most
  three columns. Form labels stack when the settings column is narrow.
- Model menus filter by the selected engine. Ready requires its exact verified
  path and family, or a configured provider connection. Cloud/system voices do
  not pretend to need local models or run on CPU.
- DataStore remembers each engine's previous route, model path, options and
  credential reference; voice style is retained too. Switching engines/off no
  longer erases that setup. Portable exports omit this private history and
  machine paths. Restored external model paths are checked before UI readiness.
- Home, Captions and typed Translate edit the same saved language pair.
  Checkpoint constraints control choices: output is never Auto, typed translation
  requires an explicit source, fixed QuickMT directions cannot be swapped into
  an unsupported route. Explicit Use repairs a forced single direction.
- Live Start actually starts the chosen input and shows the floating overlay.
  The input is visible on Home and selectable in Captions. Mac audio is the
  default when supported; microphone-device settings are explicitly labelled.
  Home hides unavailable mobile-only destination tiles.
- Active captions use the session's frozen configuration. Settings changes apply
  to the next session and say so. Typed results retain their actual target label,
  show only text-session translations, and never substitute original text after
  translation fails. Setup guidance links to the stage that needs attention.

## Native Mac system audio

`hearth-system-audio` uses Apple's ScreenCaptureKit on macOS 13.3+ to feed live
system output through the existing pipeline. No loopback driver or FFmpeg is
used. It attaches only an audio output, not screen/video/microphone output, and
excludes the Hearth application when ScreenCaptureKit lists its parent PID.

The helper emits bounded 100-ms PCM16 mono/16-kHz frames. A sixteen-frame native
queue, bounded OS pipe and rendezvous JVM boundary prevent unbounded buffering.
Overflow and permission/framework errors fail visibly. Finish drains normally;
cancellation closes the producer and joins/reaps its process. The helper's
`--probe` checks packaging/framework protocol without requesting recording
permission or enumerating capture content.

The owner must test live YouTube/app audio and permission grant/denial/revocation.
No live microphone or system audio was captured by the agents. macOS may request
Screen & System Audio Recording access on the owner's first Start. Protected
content/OS capture behavior is not established by a helper probe.

## Local verification

- Required Android tests + Play/FOSS QA Kotlin compilation passed, along with
  all six desktop module suites, Android native builds and Mac application image.
- Desktop UI/controller: 57 tests, no failures/errors (two opt-in render skips).
  Coverage includes actual 11-engine card geometry at four widths, language
  policy/readiness, per-model jobs/stale-refresh/cancellation and session snapshots.
- Desktop data: 22 tests, no failures/errors (one optional fixture skip), including
  engine-history restart/export, concurrent model ownership, failed-repair
  invalidation and archive extraction/cancellation/reuse.
- Desktop media: nine tests, including eight fake-process/protocol/probe capture
  tests; native CTest 2/2 passed. Common/speech host ASan/UBSan suites passed again.
- Packaged-launcher self-check passed, including all 16 local engine routes,
  native media, audio/crypto, authenticated loopback HTTP and the noncapturing
  system-audio probe. Runtime model tests remain opt-in; this change does not
  introduce new model quality/performance claims.
- Full engine-list screen renders were inspected. Real OS UI behavior is checked
  separately during the owner's test; rendered pixels alone do not prove capture.

Evidence is ignored under `build/audit-2026-10-22/`: `owner-feedback-*-gate.log`,
`owner-feedback-*-host.log`, `owner-feedback-packaged.log` and
`owner-feedback-previews/`. No GitHub Actions calls, publication, merge or remote
push are required for this local owner-test update. Tags stay local because the
Android workflow triggers on any pushed tag.
