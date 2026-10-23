# Android benchmark worker recovery and per-sample deadlines

Prepared for **Hearth 0.30.0 / code 101**, tag **v0.30.0-rc.4**, on
`codex/hearth-desktop-kmp`. The release tag identifies the reviewed source commit;
the validation archive records its full SHA and APK identities. This document
supersedes the operational advice in the [historical RC3 deadline handover](benchmark-deadline-2026-10-23.md).
The current user-facing behavior is described in [Model Lab](../local-benchmark.md).

## Report and confirmed source defects

The owner reported that every benchmark failed, Android repeatedly offered a
clear-cache popup, and the comparison stopped with:

> Benchmark worker termination could not be confirmed; comparison stopped to avoid
> overlapping native work. Restart Hearth before running more local models.

The source audit confirmed three independent problems. These findings do not
depend on guessing that the phone simply needed a longer inference deadline:

1. RC3 applied one 20-second deadline to a complete model/backend/language attempt:
   staging, model verification/loading, every corpus sample, all three or more
   measured passes, result transfer and cleanup. The normal bundled language set
   contains roughly 40–48 seconds of audio per pass; three accelerated-replay
   passes therefore represent about two minutes of input. Rejecting that complete
   workload at 20 seconds did not measure a 20-second live input limit.
2. Shutdown depended on `ActivityManager.runningAppProcesses` containing the
   worker PID. When a known worker PID was absent from that optional inventory,
   the parent skipped its PID kill. It also sent authenticated `EXIT` only when
   the PID was unknown. A live worker with a missing inventory row could therefore
   miss both shutdown paths, leaving the parent waiting for Binder death.
3. Cancellation could occur between binding, authenticated handshake and sending
   `RUN`. The previous lifetime path treated this idle/startup race as potentially
   active native work, even when no `RUN` had been sent. It then discarded the
   connection on failure while retaining `LocalWorkGate`, leaving no in-app way
   to confirm a later shutdown and release the gate.

RC3 also deliberately used `Process.killProcess` after successful candidates and
from normal service teardown. This release removes those routine forced deaths.
The exact cause of the reported vendor clear-cache popup was not captured from
the device, so it must not be presented as proven. A source-level fix and host
simulation are not a substitute for the owner's Android process/device test.

## Timing protocol 4

The user-selected **5 / 10 / 20 seconds** remains a hard ceiling for one complete
input sample in each measured pass. It is never renewed for each streamed frame,
partial transcript, token, chunk or progress message. Recognition finalization is
part of that input's deadline. Speech now feeds the same 800-sample / 50 ms frames
used by live capture, without an artificial playback delay.

| Operation | Deadline | What starts a new budget |
| --- | --- | --- |
| `STARTUP` | 30 seconds | Beginning a new authenticated worker job |
| `LOAD` | 120 seconds | Starting model verification and explicit model loading |
| `INFERENCE` | Selected 5, 10 or 20 seconds | Starting one full recording, translation or TTS sample |
| `RESET` | 10 seconds | Resetting a speech session between samples or passes |
| `CLEANUP` | 10 seconds | Closing owned native sessions and joining TTS work |
| `RESULT` | 10 seconds | Preparing and writing the bounded private result |

Parent-side bounded private-file staging occurs before the worker startup timer.
The request still has the existing sample-count, PCM duration, JSON-size, identity,
canonical-path and integrity checks. `STARTUP` bounds binding, handshake, private
request reading and worker platform initialization. It is not a network download
deadline. Successful total attempt time may exceed 20 seconds because loading and
each individual sample have distinct budgets.

Both the parent watchdog and an independent scheduled Java worker timer track
explicit operation boundaries using elapsed-realtime clocks. The parent validates
phase order and bounds the total boundary count from the sample/pass counts. A
stuck native call cannot extend its timer by publishing status text. Parent phase
messages use the worker's operation-start timestamp rather than granting extra
time when a UI-thread callback is delivered late.

Some providers still initialize lazily. `ArticleReader` / shared TTS and ML Kit
translation can perform native client/model initialization during their first
actual sample. That work remains inside the first sample's chosen inference
deadline, and runtime metadata explicitly describes the deferred initialization.
Do not claim that all TTS or ML Kit loading has moved into `LOAD`, or that warmed
performance guarantees a cold first request will qualify.

Reset time is bounded separately and excluded from the per-sample compute time.
It is not silently allowed to run forever. Completed rows retain model load time,
first-pass and warmed sample timings, actual backend evidence and total attempt
elapsed time. Interrupted work has no partial speed or reference-quality score.

Protocol **4** and the phase/deadline configuration fingerprint separate these
runs from RC3's whole-attempt protocol **3** and earlier foreground protocols.
Historical results remain readable. Resume only skips successful, fully measured
rows with the same current protocol, artifact, input, decoder/language setting,
device/app identity, pass count, configuration and confirmed backend.

## Worker release and forced shutdown

`BenchmarkWorkerService` remains a non-exported `:benchmark_worker` process under
the app's UID. Messages require the random job UUID/token, matching UID and
expected parent Binder. The native worker still avoids a second startup audit or
preference-migration writer. The parent snapshots translation dialect and voice
approval; package verification and provider errors remain enforced.

Normal completion is now:

1. The runner closes native sessions, including `closeAndJoin()` for TTS.
2. The worker writes its private result and sends authenticated `FINISHED`.
3. The parent reads the result and requests `RELEASE`.
4. The worker sends authenticated `RELEASED`, clears that job's metadata/death link
   and resets to idle.
5. Only after this acknowledgement, or confirmed process death, does the parent
   detach, remove private job files, save an accepted result and advance.

An idle process can be cached and reused by Android, or destroyed normally. It is
not deliberately SIGKILLed after each successful model or in ordinary
`onUnbind`/`onDestroy`. A result marked `cleanup_error` requires process exit rather
than accepting an idle acknowledgement. The worker uses ordinary bound-service
scheduling (`BIND_AUTO_CREATE`) and normal CPU priority. RC3's background CPU and
`BIND_WAIVE_PRIORITY` scheduling are removed so benchmark timing is not handicapped
relative to an explicit live session; no `IMPORTANT` / `ABOVE_CLIENT` priority
promotion is added.

Hung or unconfirmed native work follows a different path. The parent always asks
its authenticated endpoint to exit regardless of process-inventory availability.
The worker uses `Runtime.halt(0)` to exit without waiting for Java shutdown hooks or
native finalizers. Parent death and the independent worker deadline also stop
active work. Only if graceful self-exit cannot be confirmed does the parent use a
PID kill fallback, and that fallback still requires the current live Binder plus
an exact PID/UID/process-name match in Android's inventory. The main/UI PID is
never eligible. Death proof checks the linked Binder callback and live Binder
status; a generic service-disconnection callback alone is not proof of death.
It never calls synchronous `pingBinder()`, which could itself block past the
shutdown timeout. Unexpected shutdown errors retain quarantine unless native
cleanup or death was already proved.

Cancelling before `RUN` is sent releases an idle binding safely: that endpoint
cannot own native benchmark work. Late callbacks remain authenticated. Stopping
after `RUN` instead requires native cleanup proof or process death before another
local model may start.

## Safe recovery and diagnostics

If worker shutdown cannot be confirmed, the controller saves a failed diagnostic
row, stops the comparison and retains the exact worker connection, private job
files and `LocalWorkGate` lease in quarantine. It makes one automatic confirmation
attempt. The footer shows the actual error, **Retry cleanup**, and **Confirming
cleanup…** while a retry is active. It no longer routes the user to an unrelated
"stop active session" help dialog or silently loses the recovery handle.

Repeated recovery actions are serialized. Recovery only retries release/death
confirmation; it does not rerun inference or force-clear the work gate after an
arbitrary delay. A confirmed release removes private job files and closes the
retained lease. Until then, a new local model/benchmark remains blocked to prevent
overlapping native work. Recordings, model selections and already saved results
are retained. Starting another benchmark remains an explicit user action after
cleanup succeeds.

On Android API 30 and newer, `BenchmarkWorkerExitDiagnostics` reads the public
historical exit record for the exact worker PID, process name and run timestamp.
It distinguishes native/Java crash, low memory, ANR, excessive-resource use,
initialization failure, self-exit and OS signal when Android supplies that record.
Older or vendor Android builds can omit or delay it; the UI then says Android did
not provide an exit reason rather than guessing an out-of-memory crash. Deadline
errors preserve their operation and actual safety budget: a 120-second loading
timeout is not mislabeled as a 20-second inference timeout.

A timed-out or crashed artifact/backend is skipped across later languages,
decoder variants and voice styles for that queue. Other requested backends remain
eligible. A returned sample-deadline result triggers the same policy as a parent
watchdog exception. Failed/interrupted rows cannot win speed or quality rankings.

## Changed source areas

- `BenchmarkWorkerDeadline`, `BenchmarkWorkerWatchdog`, `BenchmarkSampleBudget`:
  bounded phase clocks and complete-sample inference limits.
- `BenchmarkIsolatedRunner`, `BenchmarkWorkerService`, `BenchmarkWorkerProtocol`:
  authenticated idle release, native-death proof, cancellation races and retained
  recovery ownership.
- `BenchmarkWorkerSession`, `BenchmarkRunController`: queue/session boundary,
  disqualification policy, quarantine, serialized recovery and local-work lease.
- `BenchmarkWorkerExitDiagnostics`: actual public Android exit evidence.
- `LocalBenchmarkRunner`, attempt/metadata helpers: native operation boundaries,
  live-sized input frames, deferred-init disclosure and protocol-4 identity.
- `LocalBenchmarkScreen`, `BenchmarkRunFooter`, results details and EN/AR/ZH
  strings: per-sample wording, stage budgets and persistent recovery controls.

## Verification ledger

The final source passed `./gradlew test :app:compilePlayQaKotlin
:app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa` in 4m24s.
Each of the six Android variants reports **893 cases**, zero failures/errors,
with 3 Play and 15 FOSS intentional skips. The coordinated focused benchmark
run also passed both QA variants. Logs and complete JUnit XML archives are
included in the validation asset.

`python3 scripts/verify-release.py` passed all ten QA APKs: current identity and
signatures, ABI/ELF alignment, native/source pins and permission/FOSS boundaries.
ARM64 upgrade checks compare RC3 code 100 with code 101 under the same
`com.sal7one.transiber.qa` package and unchanged QA certificate
`7a9256582e2627e5b715ef2648373e52d62c512127f40b2aff3ccbcc787d5792`.
The default four-ABI selection remains synchronized; TTS remains one aggregate
module with ARM64 support. Native and desktop sources are unchanged in this fix.

The local release receipt and GitHub draft identify the exact uploaded bytes;
the package tool rechecks signatures and pins after committing/tagging. No
Android native inference or vendor crash-popup acceptance is claimed by these
host/APK checks.

The new host regressions cover the real controller with a fake IPC/session
boundary: unconfirmed automatic recovery retains ownership; an explicit confirmed
retry releases it; concurrent retries do not overlap; returned deadline results
skip later language attempts for the same artifact/backend while other backends
remain eligible; and loading-timeout errors retain their actual phase/budget.
Compose regressions cover persistent error/recovery controls, disabled Run during
quarantine, retry-in-progress feedback, per-sample wording and saved limits. Phase
and worker-lifetime tests cover hard deadlines and authenticated release/death
semantics. These are host tests, not on-phone JNI/GPU/Android Binder execution.

No adb/device driving or GitHub Actions runs are part of this work. The owner
device-tests. Acceptance should include a small known-good model beside a slow
model, CPU and GPU paths, Stop before/after binding and during loading/inference,
background/return, the multilingual corpus, deferred-init TTS/ML Kit, and a failed
shutdown followed by Retry cleanup. Confirm that completed models advance without
repeated vendor crash/cache popups and that an actual stuck native sample is
disqualified at the selected maximum. A real vendor popup or driver failure still
needs its device evidence; host success cannot prove it absent.
