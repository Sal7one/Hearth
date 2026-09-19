# Hearth 0.13.1 / code 27 — installation permission fix

Date: 19 September 2026. QA builds remain debug-signed test artifacts, with the
same package/signing identity as previous QA versions. No user data reset.

## Report and diagnosis

The owner reported Play Protect's “App blocked to protect your device” dialog,
mentioning sensitive data and identity theft/financial fraud, from Samsung My Files.
Google's [developer guidance](https://developers.google.com/android/play-protect/warning-dev-guidance)
describes this exact Internet-sideloading warning and lists Accessibility service
access among its triggers. This is distinct from its separate malware warning.

Inspection of the **delivered v65 APK**, not just the source manifest, confirmed
`ReadingAccessibilityService` with `BIND_ACCESSIBILITY_SERVICE`. No SMS, notification
listener, or package-install access was declared. The optional shortcut service is
the likely trigger, but we do not have Google's per-APK verdict/reason details.
The previous device validation used ADB installation, which did not exercise the
owner's browser/My Files installation route.

## Changes

- Remove the optional Accessibility service, metadata, and associated code from
  both flavors. No change to application identity or signing to evade a verdict.
- Remove global Volume Up, exact scroll-distance, and scroll-count UI. Keep manual
  translation, draw-area capture, adjustable settled **Page changes**, notification
  Translate/Pause/Stop, history, OCR/translation and read aloud.
- Migrate saved distance/count modes to page changes, explaining the change in setup.
  Unknown values stay manual; retired volume/distance/count preferences clear on Start.
- Extend release verification to inspect the packaged **binary manifest**, including
  service binding permissions (which `aapt2 dump permissions` alone misses), for
  Accessibility, notification-listener, SMS and package-install access.
- No native adapter or model artifact changed. FOSS remains without network access.

## Validation

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa`: PASS, 2m52s.
- App: 208 tests per variant, 0 failures/errors, 1 expected flavor-conditional skip
  in each of six variants. Common JNI: 74 tests each for debug/release, all pass.
  Counts across variants are duplicate suites, not unique test coverage.
- Both QA APKs pass `python3 scripts/verify-release.py`, including final packaged
  manifest checks, native pins/dependencies, 16 KiB alignment, and no FOSS network.
  The new manifest check also rejected the previous APK containing the service.
- Native code and artifacts are unchanged; native/device inference results remain
  recorded in [v26](validation-v26.md), not claimed as rerun here.

### Browser / Play Protect / installer check

Connected device: Samsung **SM-S908E (S22 Ultra), Android 16**, distinct from the
owner's previously reported SM-S938B. Downloaded the cloud APK over LAN in Brave,
opened it from Brave Downloads, and used Android's package installer. No ADB APK
installation was used for this check (ADB only drove UI/read package metadata).

- Play Protect displayed **App scan recommended**. Chose **Scan app**, waited for
  Google's scan, and observed **This app looks safe — You can continue to install it**.
- Chose Install; Android displayed **App installed**. Package metadata confirms
  version 0.13.1 / code 27 and the Android package installer (previously shell).
- Opened Hearth and the reading setup: only **I tap** and **Page changes** are shown;
  no retired Accessibility/volume/scroll controls. Existing models/languages and
  translation selection remained intact. No capture session was started.
- Play Protect was left enabled. Brave's unknown-source install permission was
  temporarily granted for this installation and restored to **off**, verified in UI.
- Cloud APK scanned/installed on this phone; offline APK built and manifest-verified,
  but not separately submitted to Play Protect or installed in this check.
- Acceptance on the reporting phone is still unverified. The connected phone's scan
  is evidence for this artifact there, not a universal installation guarantee.

### Artifacts

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| hearth-v66-reading-cloud.apk | 35,094,515 | `4d75c1d41bd8fe2bde6998c2aae60d5d33f2e35c88b797c4d2b4fb20a14fd43c` |
| hearth-v66-reading-offline.apk | 27,309,841 | `ea3b9142de0521515f6945b550a0acb4785c90c225546bc8bb9ba24f6e3629cb` |

This permission reduction does not itself guarantee acceptance by Play Protect.
If the reporting phone still blocks the corrected APK with protection enabled,
use Google's official [appeal process](https://support.google.com/work/android/answer/15162069?hl=en)
after reviewing the artifact, signing and distribution. Do not disable protection
or describe an ADB install as a successful reproduction of the file-manager route.
