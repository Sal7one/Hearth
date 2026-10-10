# Hearth 0.23.9 review and evidence

Scope: four Android CPU architectures, universal APKs, Android TV launcher and
a visible landscape home action. Baseline: public main f30900c. No paid provider
requests, physical-phone operations or model-weight changes.

## Checks completed on the Mac

- Common JNI externalNativeBuildDebug: all four ABIs compile.
- All four private speech and translation runtimes rebuilt/staged; existing vendor
  AAR digests verified before extracting every ABI. Final source hashes match recipes.
- Required Gradle test + Play/FOSS QA compilation pass. Play QA: 455 tests,
  1 skipped; FOSS QA: 455 tests, 8 skipped; common JNI: 96 tests. No failures/errors.
- Common native utility suite: 18 utility programs plus the real-JVM JNI
  helper test pass under ASan/UBSan with system clang. Speech suite: 60 checks pass.
- Python package tests: 7 pass; native ABI/source-snapshot tests: 3 pass.
- Ten QA APKs built; final delivery verification passes after landscape changes.
- Android 14 ARM64 TV: both flavors install/start with split and universal APKs;
  Marian/local translation and all speech exports load in a 64-bit process.
- TV overlay capture test passes (one instrumentation test): the real overlay
  window shows bilingual fixture text, Appearance and Audio settings; selecting
  Male changes its configuration. Five visually inspected TV captures are in
  `docs/screenshots/tv/`. No inference or voice engine is started by the fixture.
- Linux/KVM x86 and x86_64 API-28 guests and the ARMv7 API-30 native-bridge guest
  pass split/universal installation for both flavors and native JNI loading.
  The strengthened Vosk C-API probe passes on all three in
  [run 38009152415](https://github.com/Sal7one/Hearth/actions/runs/38009152415).
  ARMv7 native-bridge execution is compatibility evidence, not a hardware benchmark.
- Strengthened the Vosk probe: its `hasVosk()` flag only reports compilation,
  so the test opens/destroys a JNI handle and expects `Model path empty` only
  after the real local C-API loader resolves every required symbol. This checks
  the DSO without globally exposing its legacy C++ runtime or loading weights.

No inference/accuracy, thermal or paid-provider benchmark is implied by loading
a native library. Physical Haier MatrixTV EE testing remains with the owner.

## Issues found and fixed during this work

ARMv7's optional llamafile kernel failed:

```text
error: use of undeclared identifier 'vld1q_f16'
error: use of undeclared identifier 'vld1_f16'
```

It uses AArch64 FP16 intrinsics. Disable that optional kernel for ARMv7; keep the
portable GGML implementation. All four final builds pass.

The self-instrumenting library test APK originally had no modern target SDK and
was blocked by Android TV Play Protect:

```text
Failure [INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed for file:///data/app/vmdl792233866.tmp]
INSTRUMENTATION_FAILED: com.sal7one.common_jni.test/androidx.test.runner.AndroidJUnitRunner
```

Declare target SDK 36 for the library test APK and install it with the test flag.
Play Protect was not disabled. Both split and universal native tests subsequently pass.

The TV home screenshot exposed the portrait image hiding all card text/actions.
Use a bounded horizontal card on wide screens with scrollable text/actions. An
initial Compose compile caught receiver scoping:

```text
'val maxWidth: Dp' cannot be called in this context with an implicit receiver. Use an explicit receiver if necessary.
'val maxHeight: Dp' cannot be called in this context with an implicit receiver. Use an explicit receiver if necessary.
```

Compute the wide-layout decision in the BoxWithConstraints scope before entering
the card's content scope. Final compilation passes; the Android 14 TV capture shows
the title, description and action together.

This Mac has no Homebrew clang installation at the historical path:

```text
[common-tests] FATAL: C++ compiler not found (set CXX=)
/opt/homebrew/opt/llvm/bin/clang++: No such file or directory
```

A system-clang ASan/UBSan hello-world probe passed; both full host suites then
passed with CXX=clang++. No failed compiler invocation is counted as coverage.

Intel emulation on Apple Silicon requires software CPU emulation. The official
Intel emulator initially reported:

```text
Software CPU emulation (-accel off) is not supported. A hardware CPU accelerator is required (or set ANDROID_I_WANT_MY_TCG=yes to force TCG software emulation).
```

The opt-in starts TCG but the guests do not provide responsive execution probes
within a useful window. Use accelerated Linux/KVM jobs for the execution gates.
Mac-only build/ELF checks are not substituted for those results.

The screenshot fixture initially failed its voice-selector assertion:

```text
java.lang.AssertionError
Voice controls were not reachable by scrolling
```

The displayed screenshot had scrolled while UiAutomation's cached hierarchy
still held pre-scroll nodes. Use bounded shorter scrolls and clear the test
automation's node cache between them. The final capture test passes, including
the Male selection assertion. This is a fixture automation correction, not an
ASR or runtime inference result.

The broader Linux workflow exposed two host-test portability issues:

```text
FAIL 92: stt::JsonUtils::getBool(line, "truncated")
SUMMARY: AddressSanitizer: 22981 byte(s) leaked in 496 allocation(s).
```

The pipe test's 2000-byte fixture exceeded macOS PIPE_BUF but not Linux's.
Derive the test size from fpathconf so it actually tests truncation on either OS;
the production writer is unchanged. The leak report's 76 allocation stack groups
all begin at HotSpot's os::malloc (74) or os::strdup (2). Suppress only those
VM-owned allocators for the real-JVM test process; all other utility programs
keep their full sanitizer checks. A Linux-only native malloc leak negative control
must still fail with a LeakSanitizer report under the same suppression. The Mac
common/speech suites and required Gradle checks pass after these test changes.
Linux validation of the scoped suppression/negative control is pending in the
new independent Native host checks workflow; it is not yet counted as a pass.

## Cleanup and final execution record

Removed the three Intel/native-bridge AVDs, their new system images, the unused
ARM64 API-28 image, the downloaded Intel emulator and six new disposable ABI
build directories. Free space rose from 63 GiB to 87 GiB before TV cleanup.
The Android 14 TV AVD/system image is also removed after retaining the five
screenshots and passing capture log; free space is now 97 GiB (about 34 GiB
reclaimed since the pre-cleanup reading). No owned emulator remains running.
The accelerated emulator execution gates pass at 891b843. Their native-loading
tests include the stronger Vosk check; the earlier ARM64 TV capture/installation
used the preceding probe against the same ARM64 libraries. The final full-host
workflow result is recorded after the scoped sanitizer fix is verified.
Existing SDK/NDK, debug keystore, model folders and physical-device sessions are preserved.
