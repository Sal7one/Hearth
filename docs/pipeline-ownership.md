# Feature pipeline ownership

Initial audit: 0.22.3 (2026-09-21); native performance follow-up: 2026-10-18.
This records code and regression-test evidence; it is not a claim that every
provider/model combination has been device-tested.

| Feature | Configuration snapshot | Runtime ownership / teardown |
|---|---|---|
| Live captions | CaptionConfigStore, speech connection and translator snapshot per generation | Capture service owns recorder/projection and workload lease. Controller retires speech, text bridge and speaker; awaitReleased includes cancelled initialization and translation cleanup. |
| Conversation / face to face | Speech config per turn; shared conversation/typed translator, credentials and languages frozen before the turn | One microphone turn. Recognition drains before translation. Cancellation joins recording and model cleanup before releasing the workload lease. Face to face is a presentation of this same controller. |
| Typed text | Conversation/typed provider and local model choice; newest input revision | One resident translator and conflated pending request. Clear, blank input, invalid setup and backgrounding invalidate old work. Local lease stays held until model close completes. Cloud requests have their own transport owner. |
| Camera / image OCR | OCR profile/source/target and camera/reading translator | Bounded image queue, frame epochs and translation revisions. Joins translation worker before destroying OCR/MT and releasing lease; discarded bitmaps are recycled. |
| Manga / screen reading | Same selection as camera, frozen at overlay start | Reading service owns projection/windows; CameraOcrController owns inference. Movement invalidates old output; setting changes restart through setup. |
| Read aloud | Shared voice default; request chooses system/custom backend | One audible owner across pages; one serialized local voice synthesis at a time. Per-instance ONNX run options/handles; Stop cancels only that player. Speech/MT may coexist intentionally with read aloud. |
| Benchmark | Explicit selected models and directions | LocalWorkGate prevents overlapping heavy inference workloads; cleanup precedes lease release. |
| Model/file downloads | Pinned artifact and destination | Installing does not activate a model. Explicit Use actions in the model/download screens configure the main speech/model default. FOSS has no network permission or delegated network downloads. |

## Selection boundaries

There are three translation groups: captions, conversation/typed text, and camera/reading.
Feature pickers now write only their group's model/provider. Conversation and typed
text deliberately share a choice, as do camera and reading. The settings translation
hub edits conversation/typed text. `Use everywhere` explicitly updates all three.

Existing installations without a group-specific model continue using the previous
main model as a fallback. The first explicit choice pins that group's model. A later
caption-model change no longer replaces a pinned choice. Download/model-library Use
remains an explicit main-default action; use the feature picker to change a pinned
feature choice. Language pickers, labels, controller snapshots and reading shortcut
preflight use the same resolved model.

Connections and voice defaults remain shared libraries. A saved connection change
is used by future snapshots; an in-flight request keeps its original route/credentials.
No feature cancels the whole shared HTTP client or calls CommonJni.cancelAll.

Conversation records until explicit Finish, backgrounding or a visible text-limit
failure; it no longer silently ends at one minute. Its transcript owns finalized
IDs independently of the overlay display tail. After speech is drained/released,
`TextTranslationChunks` performs bounded, ordered requests on one existing
translator while retaining the same workload lease. Cancellation is checked
between requests and after a JNI abort; native calls are still joined/closed
before another workload is admitted. The helper never publishes a partial batch
as a completed translation and never retries a paid provider automatically.

## Fixes and native review

- Removed cross-feature writes to the caption translator from camera/reading and
  conversation/typed pickers.
- Fixed typed blank input retaining native ownership; invalid setup and same-language
  input now retire previous work. A model that finishes loading after Clear does not
  start the obsolete translation.
- Read aloud reports one terminal outcome rather than a failure followed by a null
  completion. Remote voice validation now happens before allocating a client.
- Reviewed speech/router, translation, Paddle/Japanese OCR and Supertonic JNI handle
  tables, cancellation flags, per-session mutexes and ONNX run options. No production
  C++/ABI or vendored runtime change was justified by this pass.
- Added sanitizer-backed LeaseRegistry coverage: cancel one handle, retire while
  inference holds a lease, use and close another handle during that wait, reject stale
  handles and safely move-assign the last lease. Added simultaneous translation-bridge
  coverage for isolated cancellation and stale-result suppression.

## Limits and follow-up device checks

LocalWorkGate rejects competing heavy inference sessions. The reading Start button
explicitly says it stops live audio captions. Both reading and caption entry screens
now wait up to 30 seconds for previous model cleanup before launching the next service.
They show waiting progress and a timeout message in English, Arabic and Chinese. Cancellation of that wait
does not release another owner. Other conflicting starts fail without stealing a lease. It is a memory/lifetime guard, not automatic
scheduling or a promise that all features can run concurrently. Voice has a separate
serialization gate so captions can still be read aloud.

Native host checks do not measure real model throughput, thermal behavior or GPU
execution. Useful owner checks: keep captions running while changing another
feature's translator; stop/restart during local model initialization; clear typed text
mid-translation then start a different feature; switch read-aloud pages; change
camera/manga model and confirm the caption selection is retained. Paid cloud and
full local model quality checks still require actual account/audio/model runs.

## Independent cloud caption voice (0.23.5)

Cloud read-aloud has its own provider/endpoint-scoped encrypted credentials and
configuration; it does not reuse a changing STT connection. Requests retain the
voice/configuration/loudness chosen when queued. Only finalized text crosses
this boundary; native local STT and optional translation keep their existing
owners. Caption speech is still one active request plus three pending entries,
with Stop invalidating generations and cancelling the active connection/player.
Config/voice failures are reported through the overlay notice and do not escape
the optional speaker into the STT session. Provider setup/preview never enables
read-aloud automatically. [Cloud TTS evidence](cloud-tts.md).


## Saved caption history (0.23.6)

Only `CaptionCaptureService` installs a text-history recorder on its controller.
Conversation/typed/OCR controllers do not acquire it. Final captions and
translation patches are emitted after stable IDs are assigned; audio pushes and
interim text do no persistence work. The separate IO queue drains accepted text
on service destruction, with consent rechecked before transaction commit.
Opening history or toggling saving does not restart recognition or voice output.
History failures have their own error state and never turn an STT failure into
success or suppress translation errors. See [the history contract](caption-history.md).

## Capability-driven PCM and native failures

Research and implementation follow-up, 2026-10-18. The
[one-to-one VoiceStudio comparison](voicestudio-engine-comparison.md) starts with
the actual NDK/library boundaries and distinguishes file APIs, utterance-windowed
recognition and stateful incremental decoding. An engine accepting chunks does
not establish decoder-cache reuse or bounded live latency.

`SttEngine` retains its existing batch and live APIs. Two opt-in Kotlin contracts
describe implemented behavior without testing a concrete engine's class:
`DirectPcmSttEngine` borrows native-endian mono PCM16 for one call;
`CancellableSttEngine` cancels that engine instance and still requires its owner
to join/release. Whisper and Vosk implement direct PCM; the unfinished generic
ONNX adapter does not acquire that claim. These are additive Kotlin interfaces;
native JNI package names, method signatures, handle tables and backend ABI
versions are unchanged.

Captions consume `SttPcmInput`, which reuses a direct scratch buffer only when the
engine supports that input, and otherwise forwards the existing `AudioChunk`.
The realtime library session can lend its pooled direct buffer to any engine
implementing the contract and releases that borrow even if probing, RMS or
inference fails. Benchmark cancellation uses the instance cancellation contract.
Model creation, model-specific language/options and thread tuning remain explicit.

Legacy/cloud caption ingress now uses `CaptionAudioQueue`, with a 16,000-sample
budget including in-flight inference. Recorder read sizes cannot enlarge that
budget. Accepted data is copied once and remains ordered; overload retires the
timeline with a visible error instead of replacing already accepted PCM. Normal
source end drains accepted input before provider finalization; Clear, pause and
replacement discard waiting input and join the old owner. Generation plus queue
identity prevent an old ingress error from failing a replacement session.
The modern `LiveSpeechProcessor` retains its separate bounded event/sample path.

Whisper decode failures now persist in its engine instance, including failures
on the asynchronous worker thread. A failed `whisper_full` cannot produce empty
successful batch/final JSON or discard a streaming window as if it decoded.
Polling and later pushes expose the same failure; explicit reset/new batch starts
a fresh recognition timeline. Automatic language now transcribes in its initial
call instead of requesting detection only; upstream detection work still runs.
There is no forced-speech retry of legitimate empty output. Reset also clears
the cooperative cancellation token. Legitimate silence remains
an empty successful transcript; explicit cancellation remains cancellation.
The separate native Whisper ring retains its existing 960,000-sample safety cap,
but reaching it now rejects the incoming frame and fails visibly before changing
the audio timeline. It no longer removes the oldest accepted samples. An in-flight
decode cannot publish after that overload failure; reset keeps the loaded model.

Translation and TTS do not become properties of this STT abstraction. The existing
caption translation route still owns source evidence, Whisper's deliberate
English pivot, provider-integrated target output and optional independent text
translation. Translator/voice snapshots, separate credential scopes, bounded
speaker queues and final-text identities are preserved. No silent model/provider
fallback, model download or file-transcription product feature was added.

`SttPcmInputTest` exercises capability-based dispatch with the same engine identity
on both input paths, signed samples, endian/offset/count semantics, buffer reuse,
fallback metadata, errors and cancellation. `CaptionAudioQueueTest` uses a blocked
consumer to exercise in-flight budget accounting, overload, copied data, ordered
draining, cancellation and engine failure. Emulated-recorder contract tests cover
failed reset/finalization and cleanup. The sanitizer-backed Whisper regression
links the production engine against deterministic C API stubs and covers decoder,
automatic language, worker, tail, reset, cancellation and cleanup outcomes
without model files.

Benchmark interpretation remains explicit: modern speech profiles run accelerated
50 ms PCM replay, while legacy Whisper/Vosk candidates use full-clip batch.
Batch completion is not first-partial latency. These checks do not measure
simultaneous ASR/MT/TTS contention, thermal behavior or device audio quality.

## Native performance follow-up — 2026-10-18

The independent STT → optional translation → optional TTS route and its handle,
generation and cancellation owners remain unchanged. The
[source audit](native-performance-research-2026-10-18.md) records engine/runtime
comparisons and deferred optimizations.

Modern local speech now uses `LiveSpeechProcessor.awaitChange` to notify the
caption consumer immediately after text/state changes. A conflated revision
never replaces its separate final-event queue. The 100 ms timeout refreshes
metrics; destructive `snapshot()` runs outside it. Already completed finals are
promoted before a later failure is reported, while the failed partial remains
suppressed. Clear/reset, close and failure wake waiters without changing native
session ownership. Legacy Whisper's published-text poll changed from 700 ms to
100 ms; it still performs no inference. `SpeechSession` bulk-copies accepted
PCM16 into its existing reusable direct buffer with unchanged byte order and
sample offsets.

Whisper's worker now wakes on input revisions instead of paying an unconditional
200 ms wait. Partial cadence uses the source sample clock across ring discards
and reset. Its configured 1–8-thread budget is respected. Streaming `audio_ctx`
uses 20 ms encoder positions after the stride-2 convolution, with the existing
224-position minimum and 16-position padding; batch retains full context.
Shorter streaming context still needs model-quality comparison on the owner
device. Automatic language no longer produces a detection-only first partial.

Offline Sherpa segmentation directly analyzes whole 20 ms frames, retains its
utterance allocation and uses fixed pre-roll storage. Only trailing gate-silent
audio beyond 200 ms is removed from decode input; internal pauses and source
timestamps remain intact. Energy gating can mistake quiet speech for silence,
so quiet endings need owner checks. Window/silence thresholds and offline
versus cache-aware model capabilities remain explicit.

`TextModel` owns one persistent two-thread, `poll=0` pool shared by prompt
prefill and generation. Non-sliding-window models may retain a matching prompt
prefix; changed tokens and previous generated output are removed, and the last
prompt token is decoded again for fresh logits. Failed removal or decoding
clears state. Sliding-window models retain full prefill. This is computation
reuse within the existing translator, with no conversation history or route
change. Marian keeps owned decoder outputs across token steps and releases
partial/dummy outputs safely. Invariant views/logits are allocated once per
caption; graph allocations and model computation remain ORT's responsibility.

Exact-model testing subsequently found experimental Hy-MT2 Q2_K metadata
declaring an ordinary `$` token as EOS while its chat template ends assistant
output with a different control token. Ignoring that marker caused completed
answers to continue to the output cap. The Hunyuan adapter now verifies the
exact control marker and stops on its ID, while rejecting ordinary tokens
mislabelled as EOS. It does not stop on text substrings or parse caption text
as special tokens. Missing/invalid named markers fall back only to valid control
EOS/EOT; no safe terminator produces an explicit setup error. Gemma's EOG
behavior and all handle/cancellation boundaries
remain unchanged. [Diagnostic record](native-performance-research-2026-10-18.md#real-model-diagnosis-a-missed-hunyuan-end-token).

Final validation completed after the Hunyuan fix with Temurin JDK 17.0.20 and Homebrew LLVM:

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
./gradlew :common-jni:externalNativeBuildDebug :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
CXX=/opt/homebrew/opt/llvm/bin/clang++ bash common-jni/src/main/cpp/speech/tests/run_speech_tests.sh
CXX=/opt/homebrew/opt/llvm/bin/clang++ bash common-jni/src/main/cpp/marian/tests/run_marian_tokenizer_tests.sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home CXX=/opt/homebrew/opt/llvm/bin/clang++ bash common-jni/src/main/cpp/common/tests/run_common_utils_tests.sh
```

The full Gradle test, both QA Kotlin compilations, native Debug build and both
QA APK variants passed; release verification passed all 10 APKs. ASan/UBSan
passed 76 speech/segmenter checks, 144 production-Whisper stub checks, 1,785 final
`TextModel` checks, Marian tokenizer/decoder checks and the common suite,
including real-JVM JNI checks. All six `SpeechDeliveryTest` cases passed in
debug and release, including a queued final followed by a failure. Translation
and both speech plugins were rebuilt/staged for ARM64, ARMv7, x86 and x86_64;
the 12 plugin binary hashes and source snapshots were verified.

The installed Apple Clang sanitizer hung before `main()` on this host. Homebrew
LLVM ran the same ASan/UBSan scripts with sanitizer coverage retained. As a
negative control, restoring only the old Whisper wait predicate fails the
dispatch regression. Actual Q2 smoke requests now complete and preserve `$5`,
but malformed Arabic still makes Q2 experimental. The controlled official-Q4
host comparison reduced warm median request time from 445 to 270 ms; prefix
reuse changed one faithful wording choice. Host fixtures establish
state/ownership behavior, and this small CPU comparison does not establish
Android speed or general model accuracy. Owner follow-up is quiet speech and streaming
quality, live caption/translation/read-aloud coexistence, Clear/Stop/restart and
a sustained session with the chosen models.
