# Settings Back navigation and presentation — 0.27.3 / code 93

Back from a page launched by the global settings sheet restores that sheet,
its scroll position, expanded inline options and original Home/feature context.
Version 0.27.3 additionally retains the actual Material3 presentation state while
a child page is visible, so Back does not replay the entrance animation. Only
an explicit new opening creates a fresh presentation; normal Close still hides it.
Model/download descendants unwind before the sheet returns. Nested settings
visits retain separate return frames. Explicit Home, tab changes, deep links and
native TV mode clear suspended returns.

`RetainedSettingsSheet` owns scroll/saveable content outside conditional sheet
composition. MainActivity supplies actual CaptionConfigStore data and defers the
sheet until that data is available, so a loading-only layout cannot clamp the
saved position. Read failures surface with retry; no default configuration is
substituted for a failed read. Caption, conversation, typed-translation and camera
sheets also keep their open state while visiting model/voice settings. External
overlay controls and screen-reading setup preserve their originating sheet.

## Original 0.27.2 checks

- Focused host gate: 12 tests, no failures/errors/skips. Ten tests cover settings
  return history, nested pages, origins, restoration and invalid saved state.
  Two Compose/Robolectric tests exercise the production retained-sheet owner,
  real modal layout, saved inline options and 500/600-pixel scroll restoration,
  including delayed data after saved-instance restoration.
- Full `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
  :app:compilePlayDebugAndroidTestKotlin :app:compileFossDebugAndroidTestKotlin
  :app:assemblePlayQa :app:assembleFossQa` passed in 2m35s.
  Log: `build/settings-back-0272-full-gates.log`.
- Each of six app test configurations reports 686 tests and zero failures/errors.
  The existing Play skips remain 2 per configuration and FOSS skips 11; repeated
  flavors/build types do not count as additional distinct test cases.
- Both QA editions passed `python3 scripts/verify-release.py` across all ten
  ABI/universal APKs: signatures, current package/version, native hashes, ABI/ELF,
  16 KiB alignment and permissions. FOSS remains offline.
- Existing standalone AAR distribution verification passes for all eight unchanged
  SDK artifacts. No model, native runtime or SDK inference code changed.

The Android UI regression source compiles for both editions and covers
system/toolbar Back from Home, Conversation and Translation; a scrolled sheet;
child recreation; and the full Settings page's scroll position. It has not been
executed on a device or emulator. It is guarded for disposable phone emulators;
no adb or owner device was used.

## CI verifier compatibility follow-up

The first native-emulator CI run stopped before starting an emulator: Android
build-tools 37 final reports `V2 Signer: certificate SHA-256 digest:` where older
tools report `Signer #1 certificate SHA-256 digest:`. The same signed APK verifies
with both tools. A scripts-only follow-up accepts the explicit unrotated V1, V2
and V3.0 signer labels while continuing to require a successful verifier exit and
complete certificate fingerprints. Source Stamp/public-key digests, malformed
hashes and verifier failures remain rejected; unfamiliar output is reported.

All 14 APK-delivery host tests pass. Actual build-tools 36.0.0, 37.0.0 and
37.0.0-rc2 return the same delivery certificate, and the full ten-APK verification
gate passes with 37.0.0 selected explicitly. The required Gradle test/compile gate
also passes. This follow-up changes no app code or uploaded release bytes; the
signed `v0.27.2` tag continues to identify the settings fix at `1b1a27c`.

## 0.27.3 CI and release follow-up

Eight production Compose sheet regressions pass: scroll/inline state, delayed
settings restoration, first-frame expanded presentation, new-opening animation,
nested feature presentation, normal Close, and both global/nested navigation
during an unfinished entrance. Together with ten return-history cases, these
form 18 navigation regressions. The return-frame tests freeze the animation clock
and lay out one frame; they do not wait for an entrance animation to finish.
Controlled negatives reproduce Hidden on global return and nested content below
the screen (896 px instead of 96 px); restoring the fixed source passes all eight.
No private Material3 API, reflection or animation delay is used. All six app
test configurations report 692 tests and no failures/errors (the existing
Play skips remain 2 per configuration and FOSS skips 11). Both editions also
compile the Android UI regression suite.

The full test/compile gate, both QA assemblies and common-JNI test APK assembly
passed in 2m53s. Log: `build/settings-presentation-0273-full-gates.log`.

The self-instrumenting common-JNI APK now includes ONNX Runtime without adding a
second copy to the consumer AAR. Its preflight checks all four CPU architectures;
valid, missing-runtime, duplicate-member and wrong-ABI host cases pass. The SDK
backpressure test uses a deterministic native-cancellation handshake, preserving
the bounded queue and teardown assertions. Production SDK/native code is unchanged.
CI retains test reports and installation logs for subsequent failures.

## Distribution and unchanged baseline

The owner delivery is the official tagged GitHub release, with 22 grouped assets.
Every APK remains directly downloadable. SDK, optional JSON setups and evidence
stay in separate ZIPs. BUILD-PROVENANCE.json and SHA256SUMS bind the exact tagged
commit, embedded APK revision, native pins, package contents and remote uploads.

The [model-runtime validation baseline](validation.md) remains valid for the
unchanged STT/translation/TTS runtimes and model packages. Existing experimental
quality, device/GPU performance, listening and Ming hosted-inference limitations
remain unchanged. This patch establishes navigation/restoration behavior, not
new inference quality or device-performance results.
