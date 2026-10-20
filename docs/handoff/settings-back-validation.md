# Settings Back navigation fix — 0.27.2 / code 92

Back from a page launched by the global settings sheet now restores that sheet,
its scroll position, expanded inline options and original Home/feature context.
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

## Executed checks

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

The new Android UI regression source compiles for both editions and covers
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

## Distribution and unchanged baseline

The owner delivery is the tagged GitHub draft, with 22 grouped uploaded assets.
Every APK remains directly downloadable. SDK, optional JSON setups and evidence
stay in separate ZIPs. BUILD-PROVENANCE.json and SHA256SUMS bind the exact tagged
commit, embedded APK revision, native pins, package contents and remote uploads.

The [model-runtime validation baseline](validation.md) remains valid for the
unchanged STT/translation/TTS runtimes and model packages. Existing experimental
quality, device/GPU performance, listening and Ming hosted-inference limitations
remain unchanged. This patch establishes navigation/restoration behavior, not
new inference quality or device-performance results.
