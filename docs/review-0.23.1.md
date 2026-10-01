# Hearth 0.23.1 review and validation

Reviewed on 2026-10-01 against public main `5ffe0be`. This review covers
`8f24e50` (caption windows), `7109c32` (read aloud), `429c134` (orientation),
and `5ffe0be` (TV controls). The existing `v0.23.0` tag points to `1358490`,
before these four commits. Version 0.23.1 includes them and the fixes below.

## Findings and changes

| Area | Defect found | Resolution and coverage |
| --- | --- | --- |
| Caption tuning | The picker retained the initial saved value; selecting a preset from custom values could use a missing preset. | Collect saved tuning continuously and recognize custom values safely. Both QA variants compile. |
| Rotation | Assigning `Unit` to a `StateFlow<Unit>` emitted no rotation signal; the caption overlay reused its old geometry. | Use a changing revision, reload the orientation's geometry, and preserve live pause/settings state. `OverlayOrientationTest`. |
| Live preferences | The active caption overlay only read saved settings once. | Observe the store while visible, apply changed settings, and cancel the observer when hidden. `OverlayOrientationTest` covers preserved session state. |
| Local voices | Supertonic calculated gender mapping elsewhere but did not apply it to playback. System gender filtering could exclude the requested language or ignore a valid saved voice. | Apply the mapped Supertonic voice and constrain system fallback to offline voices in the requested language. `OfflineVoicePolicyTest` and existing voice-mapping tests. |
| Cloud read aloud | Unbounded pending work, mutable voice/volume shared between lines, swallowed errors, uncancellable waiting, and stale playback after Stop. | Add a bounded sequential queue with generation cancellation, immutable line preferences, cancellable HTTP, asynchronous playback, bounded responses, and file cleanup. `CaptionSpeechQueueTest`, `CloudTtsClientTest`. |
| Optional read aloud | Disabling the option left the current speaker running; an old recognition callback could enqueue voice work after session changes. | Release the speaker when disabled or changed and check session generation before speaking. Legacy/default settings keep read aloud off. `SpeakerSettingsTest`. |
| TV controls | Controls immediately closed its own settings sheet; explicit Play and Pause both toggled; phone captions claimed media keys from the underlying player. | Keep Controls open, make Play/Pause commands idempotent, restrict media sessions/key handling to TV, and release the session on Stop. `TvControlsTest`. |

No native source, model archive, speech runtime binary, or pinned model hash was
changed. The Phonon 2 archive remains on `v0.23.0` at its existing pinned URL and
SHA-256; it must not be recompressed or replaced in place.

## Checks actually run

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa` — **PASS**, final build successful in 1m 55s.
- Each app flavor/build test suite: 382 tests, zero failures/errors; Play skips
  one flavor-specific check and FOSS skips five. `common-jni`: 89 tests,
  zero failures/errors/skips per build type.
- Common native host suite with `/usr/bin/clang++` and ASan/UBSan — **PASS**,
  all 19 utility/JNI test programs. Speech host suite — **PASS**, 60 checks.
- `python3 scripts/speech/test_package.py` — **PASS**, seven tests.
- `python3 scripts/verify-release.py --build-type qa` — **PASS** for both APKs,
  including packaged native libraries, 16 KiB alignment and flavor permissions.
- `git diff --check` — **PASS**.

The handoff's Homebrew compiler path is absent on this machine. The initial
attempts failed verbatim with:

```text
[common-tests] FATAL: C++ compiler not found (set CXX=)
common-jni/src/main/cpp/speech/tests/run_speech_tests.sh: line 6: /opt/homebrew/opt/llvm/bin/clang++: No such file or directory
```

Both suites were then rerun successfully with the installed Apple CLT compiler.
No claim is made about the unavailable Homebrew toolchain.

## Release and remaining validation

Version: **0.23.1**, Android version code **74**. The Play QA APK supports
connected features; the FOSS QA APK has no network permissions. Both are
debug-signed QA test artifacts, not store-signed production packages.

This session did not run adb, device benchmarks, real-TV tests, paid provider
calls, the Phonon converter, or another native runtime rebuild. The previous
agent's S25/Phonon evidence is separate from this review.

Owner device checks remain: caption recognition/translation with read aloud off
and on; Stop/Silence during voice preparation and playback; portrait/landscape
geometry; changing saved settings while the overlay is running; microphone
feedback with physical speakers; and TV D-pad/media behavior. Phonon speed,
thermal behavior and long-session memory are still unmeasured here. The TV
launcher listing/banner remains future work.
