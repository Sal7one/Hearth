# Android ABI audit and 0.28.1 RC1 handover

Android version 0.28.1/code 95; tag `v0.28.1-rc.1`; branch
`codex/hearth-desktop-kmp`. Installable QA builds preserve the existing `.qa`
application ID and debug/test signing certificate from code 94. The owner chose
QA explicitly and performs all device/CPU/GPU/listening tests. No adb/device
interaction was performed. The GitHub release is a draft pending that testing.

## Actual issue and repair

The initial root ABI list did not fully control CMake or AAR prebuilt contents.
Invalid selections silently became all four ABIs, native task selection could
disagree with explicit CLI choices, and ONNX had no filter. Most importantly, an
actual ARM64-only common-JNI
AAR still contained ARM64, ARMv7, x86 and x86_64 native folders. APK split filtering
hid that library-level discrepancy. The native archive switch test caught it.

`build.gradle.kts` now resolves explicit `hearthAbis`, legacy `hearth.abis`, IDE
`android.injected.build.abi`, then all four defaults, in that order. Invalid/empty
explicit lists fail; a blank IDE device hint means no device/default ABIs. A
conflicting explicit selection and IDE hint also fails: AGP uses that hint
independently and otherwise silently skips native compilation even when its
only-target-ABI flag is false. Remove the injected hint for release/CLI builds,
or match both lists and sync Gradle. Every Android module consumes that result.
Native CMake filters are
explicit; root packaging excludes remove unselected prebuilt/compiled native
folders from archives. TTS uses only the ARM64 intersection and clears prebuilt
source roots/disables CMake on other ABIs. Its aggregate CMake entry point rejects
unsupported Android ABIs even if a stale IDE request bypasses Gradle filtering.

`verifyAndroidAbiConfiguration` captures finalized AGP DSL values and verifies
app/common-JNI/TTS/ONNX filters before preBuild/native tasks. Its JSON report is
`build/reports/android/abi-configuration.json`; it includes selected/native/archive
ABIs, packaging exclusions and JNI directories. This is a configuration guard;
the standalone switch check additionally inspects actual built archive bytes.

Keep one `:hearth-tts` project, existing JNI package `com.sal7one.common_jni`, and
the shared `:hearth-onnx-runtime`. The family directories remain source folders.
No inference algorithm, GPU kernel, native handle contract, bounded audio queue,
model integrity rule, archive limit, preference store or network policy changed.

## Reproduction

Use JDK 17 for Gradle and the configured JDK 21 Android host tests. Build helpers
and staged pinned native runtimes must already be present.

```sh
python3 scripts/verify-android-abi-config.py --build-switches
./gradlew -PhearthAbis=arm64-v8a,armeabi-v7a,x86,x86_64 --max-workers=2 \
  test :app:compilePlayQaKotlin :app:compileFossQaKotlin \
  :common-jni:externalNativeBuildDebug :hearth-tts:externalNativeBuildDebug \
  :app:assemblePlayQa :app:assembleFossQa \
  :common-jni:assembleDebug :common-jni:assembleRelease \
  :hearth-tts:assembleDebug :hearth-tts:assembleRelease \
  :hearth-onnx-runtime:assembleDebug :hearth-onnx-runtime:assembleRelease
python3 scripts/verify-release.py
python3 scripts/verify-tts-aars.py
python3 -m unittest discover -s scripts -p 'test_*.py'
```

The switch runner covers ten valid configurations, four invalid lists, three
conflicting IDE/CLI selections, direct CMake rejection for all three unsupported
TTS ABIs, five real AGP native task graphs,
and actual ARM64 → x86 → ARM64 common-JNI/TTS/ONNX archive builds without clean.
Every extracted native header must match its ABI. Restore/rebuild all-ABI archives
after the switch test; its last actual archive build is ARM64.

Both verification entry points reject Python `-O`/`PYTHONOPTIMIZE`, so their
assertion-based gates cannot silently be disabled. The model-handoff host test
was updated from an obsolete 15-heading assumption to checking every current
validated model label and unique model ID, retaining source/integrity validation.

## Verification ledger

- Required Gradle tests + both QA Kotlin compilations + native debug builds + both
  QA assemblies and all six Debug/Release JNI/TTS/ORT AARs: PASS (final gate:
  568 tasks, 14s; initial full QA build: 530 tasks, 3m28s).
- App: 701 tests per variant across both flavors and debug/QA/release, no failures
  or errors; existing skips remain (Play 3, FOSS 12 per variant).
- Common JNI: 136 tests each debug/release, no failures/errors/skips.
- Merged TTS: 68 tests each debug/release, no failures/errors; two existing skips.
- Common and speech C++ host suites: PASS with LLVM 22 ASan/UBSan.
- Five real JNI bridges against isolated fake runtime fixtures: PASS. These are
  contract/ownership tests, not model inference quality measurements.
- OpenCL dynamic loader: ten checks PASS; missing driver surfaced without abort.
- Python host checks: 44 PASS. Actual no-clean archive switch checks: PASS.
- All ten QA APKs: signatures, version/package, ELF machine/class, alignment,
  private exports, runtime hashes/source snapshots, optional GPU linkage and FOSS
  permissions: PASS. ARM64 QA certificates match the pre-rebuild artifacts;
  version code advances 94 → 95.
- Debug and Release archives: common JNI/ORT contain all four ABIs; TTS contains
  ARM64 only. Release SDK distribution boundaries: TTS 14 native libraries and
  shared ORT four, expected classes/consumer rules, no weights/app-code leakage.
- All six desktop regression suites: PASS after the root Gradle changes.

The release packager re-runs APK verification and validates the upgrade receipt
against current signer certificates before writing the draft assets. It requires
clean committed source and an exact tag, writes APK hashes/source identity and
ABI configuration into `BUILD-PROVENANCE.json`, bounds the evidence archive,
checks generated ZIP integrity and every archived APK hash, and atomically
publishes its output directory.

## Distribution and remaining owner tests

Only two ARM64 APKs, one ZIP of eight other-ABI APKs, one validation ZIP and one
checksum file are release assets. Other-ABI ZIPs include their own APK checksums.
The validation ZIP includes source/tag/version/signing identity and local evidence.
The private signing key, local.properties, generated builds and model weights
remain uncommitted.

Commits/tag pushes use `[skip ci]`. Repository workflows were checked: automatic
triggers are push/pull_request, so GitHub's documented skip directive applies.
No workflow dispatch/run/poll commands are used; creating an unpublished draft
does not publish the release or start owner device tests.

Remaining: actual phone CPU/GPU placement and latency, recognition/translation
quality, Arabic/English male/female voice listening, live overlay/file imports,
Android permissions/audio capture and driver-specific behavior on S25/S22.
A selected GPU option and packaged GPU DSO are not proof of device inference.
Use the release validation test matrix and retain the model ID, backend and error
or benchmark export when reporting a failure.
