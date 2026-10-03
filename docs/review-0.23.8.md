# 0.23.8 conversation turn reliability

## Confirmed causes and limits

`ConversationController` waited with `withTimeoutOrNull(60_000)`, then stopped
the microphone and started translation. That explains an unfinished turn ending
at one minute even while the user stayed on the same page. The timer is removed:
Finish ends recording; backgrounding/cancellation and real engine errors still
stop and release the owned session.

The reported translation trace is an abort: in the pinned llama implementation,
`GGML_STATUS_ABORTED` is 1 and `llama_decode` returns 2 for an aborted computation.
It does not establish an allocation failure. The exact cause of that phone-side
abort has not been reproduced. Native source, runtime binaries, model files and
decode/deadline limits are unchanged in this release.

The selected speech model is NVIDIA Nemotron 3.5 ASR Streaming 0.6B, Q8_0;
MiLMMT-46 1B Q4_K_M is the separate text translator. These are the existing
catalog selections, not model replacements.

## Changes

- A turn accumulates stable finalized caption IDs independently of the engine's
  120-line display tail. Corrections replace an existing ID. It retains at most
  32000 text characters and 1024 finals, with an explicit limit error that keeps
  already accepted speech.
- The shared Kotlin `TextTranslationChunks` helper submits ordered, Unicode-safe
  requests on one caller-owned translator. Conversation uses at most 320
  characters per local request and 4000 per cloud request, with part progress.
  It returns a complete translation only if every part succeeds, preserves real
  errors with their part number and does not retry a provider automatically.
- Coroutine cancellation is checked around each request. A JNI abort arriving
  after that cancellation becomes an interrupted turn. Speech and translator
  resources are still drained/closed before releasing the workload lease.
- The list reveals a new card and follows its measured bottom as text grows,
  including a card taller than the viewport. App scrolling no longer disables
  following. Manual reading pauses following; returning to the bottom or tapping
  Latest messages resumes it.
- Translation progress is localized in English, Arabic and Chinese. QA version
  is 0.23.8 / code 81.

## Checks run on the Mac, 2026-10-03

- Focused command:
  `./gradlew :common-jni:testDebugUnitTest --tests 'com.sal7one.common_jni.translation.TextTranslationChunksTest' :app:testPlayQaUnitTest --tests 'com.sal7one.transiber.conversation.*'`
  — **BUILD SUCCESSFUL in 29s**. Log: `/tmp/hearth-0238-focused.log`.
- Required tests and both APKs:
  `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa`
  — **BUILD SUCCESSFUL in 3m 28s**. Log: `/tmp/hearth-0238-final-gradle.log`.
- App JVM suites: **455 cases per flavor/build**, zero failures/errors; existing
  skips are 1 per Play variant and 8 per FOSS variant. All six new accumulator
  and follow-policy cases pass without skips in debug, QA and release.
- Common-jni JVM suites: **96 cases per debug/release**, zero failures/errors/skips.
  The seven new chunking cases cover multilingual boundaries, surrogate pairs,
  numeric punctuation, limits, request order/progress, empty/failing parts and a
  native-like abort arriving after cancellation.
- `python3 scripts/verify-release.py --build-type qa` — **PASS** for both APKs,
  including native/vendor hashes, 16 KiB alignment and flavor permissions.
  Log: `/tmp/hearth-0238-verify-release.log`.
- `aapt2 dump badging` confirms both APKs are **0.23.8 / code 81**.
- `git diff --check` — **PASS**.

### Real MiLMMT host probe

The reported Arabic input is 291 characters, below the new local request bound.
The pinned MiLMMT-46 1B Q4_K_M file (806057408 bytes, SHA-256
`74d38ba75108d455326e9deeaf9ab01bb266dfa665eae9c4aa84e84485d4fdf9`)
translated that whole input successfully through the existing cached CPU smoke
runner: load **719.338 ms**, translation **2956.15 ms**. Two manually divided
217/73-character probes also succeeded in **2056.18/994.454 ms**; runner exit 0.
The runner uses the existing raw MiLMMT prompt and two CPU threads, with GPU
layers disabled. Its llama pin is `64e9bceb2c3a856efed96feda784a50947049feb`;
the cached text-model source matches the staged source hash.

This checks a real model on the Mac, not Android JNI integration, phone latency,
ASR accuracy or the original phone abort trigger. Raw user text/results remain
in private temporary logs and are not included in the repository.

## Device acceptance still open

No adb/device control, paid provider calls or physical microphone/UI checks were
run. Native suites were not repeated because native code/binaries did not change.
Owner checks: speak for over 90 seconds without leaving the page, then Finish;
confirm a new/growing card follows, scrolling back holds position and Latest
messages restores following; exercise a longer translated turn and cancellation
during translation, then start another turn without stale text or errors.

QA APKs are debug-signed and delivered in a GitHub draft. Existing releases and
pinned model archives are retained.

```
640b7edf64826bcdd1f009945596215200d6d9b8cb9dd8240aa93bdd3018daaf  hearth-0.23.8-play-qa.apk
379e49790e0c28acc3d5a1416fbeffd97ca86245145e73ff2ac038db0269dcb3  hearth-0.23.8-foss-qa.apk
```
