# Hearth 0.23.2 overlay audio controls

Reviewed and implemented on 2026-10-01 against public main `9120e31`.
Version 0.23.2 uses Android version code 75.

## Behavior

- The caption overlay gear opens Audio by default. Read-aloud on/off, TTS
  loudness, Default/Female/Male preference, engine selection and voice setup
  share the Speech settings component. Preferences can be prepared while off.
- Device media volume reads the current Android media stream, honors actual
  output steps/minimums and fixed-volume policies, and writes only after a
  slider gesture. Opening the sheet never changes or persists global volume.
  Hardware and route changes refresh only while the Audio tab is visible.
- TTS loudness is per-utterance gain. Device media volume is device-wide and
  can affect the TTS base level too; this is not an independent YouTube/TTS
  mixer. Actual volume-policy errors are displayed.
- Cloud speech now requests transient audio focus like shared voice playback,
  releasing it and cleaning its temporary audio file on completion or
  cancellation. Android/player policy controls ducking; there is no promised
  exact mixing ratio. Synthesized speech remains outside media playback capture.
- Settings expand temporarily and clamp to the viewport, preserving saved
  caption geometry after closing. CC/translation and Appearance have tabs.
- Neural voice readiness checks the style selected by the gender preference,
  consistently in the UI and caption playback. Start-time checksum validation
  is unchanged. Sparse test fixtures prove readiness does not bypass hashing.
- All new labels and explanations have English, Arabic and Chinese resources.

## Checks actually run

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa`
  — PASS, final build successful in 2m 4s.
- Every app flavor/build suite: 390 tests, zero failures/errors; Play skips one
  flavor-specific test and FOSS skips five. `common-jni`: 89 tests, zero
  failures/errors/skips per tested build type.
- Eight added tests cover volume control, saved caption geometry and requested
  voice readiness. The existing picker test also covers closing the full sheet.
- `python3 scripts/verify-release.py --build-type qa` — PASS for both APKs,
  including packaged runtime hashes, dependencies, 16 KiB alignment and flavor
  permissions.
- `aapt2 dump badging` — both APKs report 0.23.2/code 75, minSdk 28/targetSdk 36.
- `git diff --check` — PASS.

The first test run exposed an outdated compact-settings assertion:

```text
CaptionLanguagesTest.pickerExpansionIsTemporaryAndClamped
java.lang.AssertionError: expected:<320> but was:<1040>
Execution failed for task ':app:testFossDebugUnitTest'.
> There were failing tests.
```

The test now verifies the intentionally expanded settings height and the
restored compact reading height when settings close, retaining the viewport
clamp and persistence checks. The final full test run passed.

No native source/runtime or model archive was changed. Native host suites were
not repeated for these Android adapter/UI changes; previous validation is in
`review-0.23.1.md`. The pinned Phonon model remains on `v0.23.0` unchanged.

## Remaining owner device checks

No adb, real-device playback/capture, TV hardware, Bluetooth routing or paid
provider calls were run. Verify original media and read aloud together on the
phone: changing both sliders, switching gender/engine, Stop/Silence mid-speech,
hardware volume updates, rotation and closing settings. Inspect recognition
while Android ducks media; OS/player behavior can differ across routes.

QA APKs are debug-signed test artifacts. Distribution is a GitHub draft release;
publishing the release remains the owner's choice.
