# Installing Hearth 0.25.4

Download a signed **`qa`** APK from the 0.25.4 GitHub draft. It contains the
optional original captions while translating and uses the existing QA signing identity.

- **Galaxy S25 Ultra / S22 Ultra:** `hearth-0.25.4-play-qa-arm64-v8a.apk`.
- **TV or unknown architecture:** `hearth-0.25.4-play-qa-universal.apk`.
- **Offline build:** choose the corresponding `foss-qa` APK. Play and FOSS replace
  one another within the QA package; they do not install side by side.

Keep your existing app installed. These APKs use package
`com.sal7one.transiber.qa`, version 0.25.4/code 88, and the same certificate as
the published 0.23.10 QA APK (code 83), corrected 0.25.1 QA draft (code 85),
and 0.25.3 QA draft (code 87).
Android can update those installations
without deleting app-private settings, history or models. An installation from
a different signing key or package is a different case; do not uninstall to
work around a conflict before preserving its data.

These are release-optimized, non-debuggable **QA test artifacts signed with the
existing debug certificate**, not production-signed APKs. Android 9 or newer is
required. The universal build includes ARM64, ARMv7, x86 and x86_64. OpenCL is
optional.

Verified signing certificate SHA-256:

`7a9256582e2627e5b715ef2648373e52d62c512127f40b2aff3ccbcc787d5792`

SHA256SUMS verifies the exact downloadable files. The source archive matches the
0.25.4 tag. This draft offers no unsigned APKs; those were the cause of the
initial 0.25.1 installation failure and have been removed from that draft.

In overlay settings, choose **CC/translation → Translation only**, then enable
**Show original while translating** if desired. The switch is off by default
and saved across launches. Original text is replaced when its translation arrives.
On TV, open overlay controls → **Text → Translation only** for the same switch.
