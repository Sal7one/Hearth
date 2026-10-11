# Voice notes, TTS and document ownership review

Review scope: draft voice-note files, standalone TTS, shared audible ownership,
cloud TTS queue and document cancellation. No device or paid-provider calls.

## Fixed findings

1. Voice notes previously reused one `Dispatchers.IO.limitedParallelism(1)` view
   for blocking microphone reads and transcript writes. The capture loop could
   occupy that worker for the whole recording, starving metadata and eventually
   filling `CaptionHistoryRecorder`'s 64-event queue. `VoiceNoteWork` now supplies
   separate capture and serialized metadata workers. `VoiceNoteWorkTest` holds a
   blocking capture while metadata completes independently.
2. `CaptionEngineController.start` applies `config.saveCaptionHistory` to its
   attached sink. The note previously enabled its sink before startup but reused
   the overlay's default false opt-in, disabling note transcripts again.
   `forVoiceNote()` snapshots explicit note consent without mutating overlay
   preferences or attaching the overlay database. Regression coverage starts
   with overlay history disabled and verifies note capture is enabled.
3. The note's stop callback latch had no deadline. An exception in the
   controller's asynchronous stop job could leave that callback uncalled,
   preventing final storage and retaining the workload lease. The callback wait
   is now capped at 30 seconds; shutdown and native release are always awaited.
   Timeout/provider errors make the note interrupted, preserving available WAV
   and final text. Virtual-time tests cover missing callback, the exact provider
   exception and cleanup-before-success. This does not claim a forcibly
   interruptible native kernel: native ownership cleanup is still awaited safely.
4. Note JSON omitted `SavedCaption.source`; new metadata preserves it while
   older files without it still load. Translated-only round-trip coverage now
   includes the microphone source.
5. Note playback did not participate in shared audible ownership. It now claims
   the same registry as TTS, and completion, playback errors and screen teardown
   release its matching owner instead of allowing separate features to overlap.

## Optional device-credential protection

Voice notes can require Android's existing PIN/pattern/password confirmation.
The switch is off by default, requires a secure device lock and never stores an
unlock result. Backgrounding relocks; canceled authentication remains locked.
The locked view exposes only its own authentication error, not a retained
transcript/provider error. Missing credential activities surface their actual
failure. This is a screen privacy gate, not file encryption, cloud login or
protection for exported files. Access-policy tests cover background/recreation
and canceled authentication; actual credential UI is an owner device check.

## Reviewed ownership without further change

- `CloudTtsSpeaker` claims at `playOnce()` and releases its entry at `release()`.
  Retaining an idle claim between utterances is safe: the next owner stops the
  previous queue outside the registry monitor, and identity-guarded old cleanup
  cannot release its replacement. Stop invalidates pending work; request clients
  and temporary audio files are closed in `finally`. Standalone teardown releases
  the speaker. Voice-player and caption queues remain bounded.
- Cloud and self-hosted clients enforce `ByokPolicy.FEATURE_BYOK`; document bytes
  are never automatically sent to a provider. Optional voice/provider actions
  reuse their existing guarded client paths. No account endpoint is fabricated.
- `DocumentOcrViewModel` keeps one job/native owner, processes pages sequentially,
  checks cancellation between operations and closes under `NonCancellable` before
  releasing its workload lease. Cancellation during model initialization retains
  ownership and closes the returned model; bitmap and import files are released.
  PDF framework parsing/render calls are not interruptible while inside Android.
- TTS controls use theme colors and the existing Android/native/server pipeline;
  explicit engine errors remain visible rather than falling back silently.

## Evidence status

`git diff --check`: pass. Voice-note resource XML and en/ar/zh key parity: pass.
New JVM tests are included in the coordinating agent's final required Gradle
run; they were not run separately during an already-active shared build.
No APK/device, microphone, Keyguard, SAF or model-quality result is implied by
this source review. Account login/sync still requires an owner-selected backend.

## GLM-OCR follow-up source review

Reviewed `vision_model.h`, `vision_jni.cpp`, `vision_limits.h`, the Kotlin
`VisionOcrSession`, shared runtime initialization/UTF-8 exception conversion,
GLM catalog/import and document integration against the pinned local mtmd source.
No duplicate native/Gradle suites were started.

- The registry lease covers RGB conversion and inference. Destroy removes the
  public handle, signals atomic terminal cancellation and waits for an exclusive
  lease before freeing the model. Kotlin swaps its handle to zero exactly once;
  cancel/recognize racing close cannot resurrect a retired handle. Per-session
  inference is serialized and deadlines use `steady_clock`.
- JNI copies ARGB before native inference instead of pinning a JVM array. Native
  dimensions/count are checked independently (2048-pixel side limit), transparent
  pixels are composited onto white and output is UTF-8 validated. Context/image/
  output budgets are explicit; truncated token-limit output is an error, not a
  partial success. The prompt is fixed rather than user-injectable.
- The shared translation/vision initialization is guarded once inside the same
  isolated runtime. JNI exception messages use a UTF-8 byte-array String
  constructor with pending-exception guards, not raw supplementary UTF-8 passed
  to `ThrowNew`/`NewStringUTF`.
- Both exact GGUF artifacts have independent byte/hash pins and are reverified
  before loading. Import rejects unsupported hashes and oversized files. GLM is
  document-only, unavailable for 32-bit inference, explicitly experimental and
  does not advertise Arabic. FOSS uses only local imports, not download clients.
- Document initialization remains noncancellable until returned native ownership
  can be retained/closed; cancel during that handoff closes before releasing the
  workload lease. Hashing/loading 1.34 GiB can therefore delay cancellation;
  neither file size nor host smoke establishes phone working-set suitability.

Actionable integration finding (sent to root/native vision owner): the newly
independent document profile still passed global camera `ocr.source` to TTS in
`DocumentOcrScreen`. A GLM English/Russian document could be spoken using an
unrelated Arabic camera language. The document needs its own explicit language
choice and must pass it to read-aloud; multilingual reader metadata alone cannot
infer the page's language. Fix/verification is owned by the native vision agent.

Boundary hardening requests (not a demonstrated normal-fixture failure):
`VisionOcrNative.cancel/destroy` should catch potential registry lock exceptions
rather than allowing C++ exceptions across JNI; negative token-piece sizes should
be capped before resizing, including safe handling of `INT_MIN`. Sent to the
native vision owner before final staging. In-flight cancellation/device memory,
multilingual accuracy and Android JNI execution remain distinct from a completed
host English fixture or post-completion cancellation check.

## Final source and documentation review

The three GLM follow-up requests above are resolved in the final working tree:

- `DocumentOcrScreen.kt:51–52,155,167–171` now persists a separate document text
  language and passes it to `VoicePlayer`. `DocumentTextLanguageTest` checks
  explicit Arabic/Russian choices independently of image-reader coverage and
  reader defaults without camera state. Native PDF/DOCX text can legitimately
  use a language unsupported by the selected image reader; the docs distinguish
  these contracts rather than promising Arabic GLM recognition.
- `vision_jni.cpp:53–64` catches standard and unknown exceptions in cancel and
  destroy and uses the shared UTF-8 Java exception converter. These void entry
  points now have the same exception boundary as create/recognize.
- `vision_model.h:123–130` negates a negative token-piece size in `int64_t`,
  rejects sizes exceeding the 64 KiB output limit before allocation, and checks
  the second conversion result. `INT_MIN` no longer overflows during negation.

The native owner's recorded real-weight proof is
`glm-ocr-native-2026-10-11.json`: an exact two-line English decode through the
production CPU adapter, plus in-flight cancellation with no published text.
The recorded 59.6-second load/117.0-second inference occurred under concurrent
build load; these are not phone performance, multilingual quality or a precise
model-memory benchmark. This reviewer inspected the source and evidence without
rerunning model/build/device suites; the coordinator owns the final build gates.

Read `README.md`, `docs/voice-notes.md`, `docs/document-ocr.md`,
`docs/tts-feature-and-models.md` and `docs/draft-0.24.0.md` against the source.
README clearly separates stable 0.23.9 downloads from draft features. Account
login/sync remains explicitly pending the owner's backend selection; the optional
device-credential screen gate is not described as encryption or server login.
No automatic document upload or unguarded FOSS client path was found.

Sent the coordinator these concrete documentation corrections: verified vision
inputs are file paths rather than file descriptors; FOSS clients are guarded,
not absent from the source; independent metadata scheduling prevents microphone
worker starvation but cannot guarantee its bounded queue never fills; timed-out
note drain preserves completed text and reports interruption rather than
guaranteeing every pending translation completes.

One additional TTS source defect was flagged during this review, without editing
the frozen production snapshot: `TtsScreen.kt:139–141` clears an explicit saved
Android voice whenever the discovery list is temporarily empty. Discovery resets
that list at screen entry/refresh, so the persisted selection can disappear
before a successful probe; a genuinely missing voice is also silently cleared
despite the documented error contract. Preserve the selection through discovery
and let missing-voice validation report its actual failure. The coordinating
agent owns this final correction and its verification.

The coordinator authorized the proved TTS correction after the documentation
review. `TtsScreen` now retains the explicit voice through discovery/refresh,
tracks successful discovery separately and displays a localized missing-voice
message instead of erasing the setting. Manual engine/language selection still
resets the voice deliberately. `OfflineVoicePolicy.savedVoiceMissing` is shared
with the actual playback validation; it ignores pending/failed discovery and
requires a local voice in the requested language once discovery completes.
Regression cases cover pending empty lists, actual removal, matching voice IDs
with a wrong language/network requirement, and successful rediscovery. Existing
missing-voice playback failure coverage remains intact. The documentation
corrections listed above are applied. Required build/tests remain the
coordinator's final gate; this agent has not duplicated them.

Final static verification: TTS en/ar/zh XML parses, has no duplicate names and
matches resource key sets; `git diff --check` passes after these corrections.

The coordinator's aggregate FOSS debug test run reported 496 tests, three
failures and eight skips. One failure was this review's drain regression:
`expected same:<java.lang.IllegalStateException: Provider CloseStream rejected>
was not:<java.lang.IllegalStateException: Provider CloseStream rejected>`.
The helper catches and returns the provider exception after awaiting release;
coroutine stack-trace recovery may clone that exception at suspension, so object
identity is not its contract. The test now asserts the preserved concrete type,
exact message and mandatory release instead. Production code is unchanged.
This agent did not rerun builds/tests; the coordinator owns the corrected gate.
