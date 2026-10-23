# Hard limits for Android model benchmarks

Included with the combined **0.30.0 / code 100**, **v0.30.0-rc.3** candidate and
the [Koharu processing improvements](manga-performance-2026-10-23.md). The owner
changed the requested limit from 45 to **20 seconds maximum**. Setup offers
5 / 10 / 20 seconds, persisted through the existing DataStore-backed preference
adapter; older larger values and restored screen state normalize down safely.

The deadline covers the entire model/backend attempt: input staging, pin checks,
load, all measured passes, result transfer and cleanup. Attempts run sequentially
in `:benchmark_worker`, a non-exported bound service in the same app UID. Bounded
private-file IPC retains exact PCM/reference/model/decoder/backend identities.
No provider credentials or model weights are copied into IPC metadata. The parent
retains `LocalWorkGate`; the worker never starts preferences/audit migration.
Experimental voice consent and translation dialect are snapshotted by the parent,
and normal package integrity checks still run in the worker. Play initializes
ML Kit in the worker explicitly; FOSS rejects that route without linking the SDK.

The parent deadline and an independent Java worker timer both terminate the owned
process. Parent Binder death and Stop do the same. PID, UID, process name and Binder
identity are checked before targeting a worker; the UI process is excluded. Death
must be confirmed before another model starts or the parent appends a result.
Unconfirmed termination stops the queue and retains the parent local-work lease,
with a restart message. Worker memory and dispatch priority are lower than the UI.

A timeout or native-process failure disqualifies that artifact/backend across the
remaining queue, including later languages and decoder variants. Other requested
backends remain eligible. Interrupted work has no partial timing or quality score.
Rows preserve input/protocol identity so a last timeout cannot hide prior successful
models by creating a different reference-normalization cohort. JSON/CSV retain
termination reason, cap and elapsed time. New timing protocol **3** and the deadline
in configuration hashes prevent reuse/comparison with older foreground experiments.

Recordings are not all a couple of seconds: normal bundled clips are approximately
4–9 seconds; optional long clips reach 17.1 seconds. Custom/Saudi clips accept
0.5–30 seconds. Setup displays actual PCM durations and total repeated workload.
The cap is per complete model attempt, so larger suites need faster throughput.
This is a strict device qualification setting, not a model-card speed claim.

Host coverage includes blocked-native simulation, exact deadline, cancellation,
success/failure reaping, fatal unconfirmed death, remaining staging budget, IPC
audio/reference/pin round trips, path/identity rejection, 20-second cap escapes,
resume isolation, no-score/disqualified export, saved preference restoration and
duration display. Required `./gradlew test :app:compilePlayQaKotlin
:app:compileFossQaKotlin` and both QA assemblies passed on the final source.
All six Android variants report **871 cases**, zero failures/errors, with 3 Play
and 15 FOSS intentional skips. All ten QA APKs passed signature/identity,
ABI/alignment, native/source metadata, permissions and FOSS offline checks.
The unchanged native/desktop sources retain the Manga handover's host,
real-model and actual packaged-launcher evidence. No adb or GitHub Actions were used.

Owner acceptance: update the existing QA app with Play ARM64. Benchmark a known
slow model beside a small one, verify the former is disqualified at the chosen
cap and the latter starts after it. Repeat with CPU/GPU comparisons and language
sweeps, background/return, Stop during loading/inference and restored 5/10/20
preferences. A real Android process/GPU-driver test remains owner verification;
the host harness does not execute an Android child process.
