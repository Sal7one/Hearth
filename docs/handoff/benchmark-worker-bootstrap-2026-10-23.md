# RC5 benchmark worker initialization

This Android QA update is **0.30.0 / code 102**, tag **v0.30.0-rc.5**. It follows
the owner's report that the previous benchmark build still failed. The exact
phone error and installed version were requested; no device log was available
when the source audit began.

Fetching GitHub found no source newer than RC4 (`408638c`) on the development
branch or main. The local Chatterbox/Magpie/Oute worktrees contain older desktop
experiments; the current tree already carries reviewed desktop engine changes.
This release does not import those historical dirty worktrees or alter desktop.

## Confirmed defect and repair

The benchmark runs in `:benchmark_worker`, so it has its own Kotlin/JNI globals.
`MainActivity` calls `CommonJni.init(applicationContext)` in the UI process, but
RC4's worker only initialized flavor-specific clients. Vosk's initialization
uses `CommonJni.getPrivateModelRoots()` and `getModelStagingRoot()`; with no worker
context it deterministically returns `CommonJni.init(context) is required before
Vosk initialization` for an existing model directory.

`BenchmarkWorkerInitialization` now registers the worker's application context
and initializes common JNI before platform clients or model engines. Native
library failures propagate as real errors. This does not run preferences,
DataStore migration or startup model audits in the secondary process.

This explains a Vosk failure, not every possible model failure. Whisper has its
own library loading path. The audit checked the packaged worker's manifest,
public constructor and R8 retention, and confirmed Android Messenger supplies
the authenticated caller UID. No additional universal startup defect was found.

If every attempted model fails, the completion footer now shows the first
model's exact error. Both the results and other errors remain saved. A mixture
of successes and failures does not get a misleading global error. Cleanup
quarantine errors keep their existing priority and ownership protections.

The requested inference limit is unchanged: 20 seconds per complete sample per
pass, with separate bounded loading/reset/cleanup phases described in
[Model Lab](../local-benchmark.md). CPU/GPU placement and pinned integrity rules
are unchanged. Existing QA package/signing identity, models and settings remain
the upgrade target.

## Verification

The required project tests, Play/FOSS QA Kotlin compilation and both QA
assemblies passed in 5m34s. Each of six Android variants reports **896 cases**,
zero failures/errors, with 3 Play and 15 FOSS intentional skips. Both
`assemblePlayDebugAndroidTest` and `assembleFossDebugAndroidTest` passed in the
same invocation; the three real-process tests are compiled, not device-executed.

All ten QA APKs passed `scripts/verify-release.py`: current package/version and
signatures, ABI/ELF alignment, native/source pins and FOSS permission boundaries.
The ARM64 upgrade receipt checks RC4 code 101 against code 102 with identical
`com.sal7one.transiber.qa` identity and QA signer
`7a9256582e2627e5b715ef2648373e52d62c512127f40b2aff3ccbcc787d5792`.
The release validation archive contains full grouped JUnit XML, build logs,
upgrade identity and instrumentation compilation receipts.

Release asset names now include the RC number, such as
`hearth-0.30.0-rc.5-play-arm64-v8a-qa.apk`, so old downloads are distinguishable
on the phone. This does not change the Android package or signing identity.
The packaging tool verifies the actual copied/archive bytes and records the
tagged source SHA in its provenance. Uploaded asset sizes and SHA-256 digests
are checked against GitHub before the release link is delivered.

The added Android instrumentation class `BenchmarkWorkerProcessTest` uses the
real production service in an actual Android subprocess, without model weights
or network. It checks consecutive invalid Whisper requests, Vosk's private-root
bootstrap before rejecting an invalid private model, and cancellation after a
real remote binding followed by another request. These are lifecycle/error
tests, not successful model inference tests.

The instrumentation APKs can be built locally without using adb. They target
the default Debug test variants and are separate from the installable QA release.
They have not been executed on the owner's phone. Host tests still cannot prove
vendor process behavior, the Samsung popup's cause or actual GPU inference.

The owner device-tests. No adb, device automation or GitHub Actions are used.
