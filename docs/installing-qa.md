# Installing Hearth 0.25.1

The initial `release-*-unsigned.apk` files cannot install on Android. They have
been replaced in the GitHub draft. Download a fresh **`qa`** APK.

- **Galaxy S25 Ultra / S22 Ultra:** `hearth-0.25.1-play-qa-arm64-v8a.apk`.
- **TV or unknown architecture:** `hearth-0.25.1-play-qa-universal.apk`.
- **Offline build:** choose the corresponding `foss-qa` APK. Play and FOSS replace
  one another within the QA package; they do not install side by side.

Keep your existing app installed. These APKs use package
`com.sal7one.transiber.qa`, version 0.25.1/code 85, and the same certificate as
the published 0.23.10 QA APK (code 83). Android can update that installation
without deleting app-private settings, history or models. An installation from
a different signing key or package is a different case; do not uninstall to
work around a conflict before preserving its data.

These are release-optimized, non-debuggable **QA test artifacts signed with the
existing debug certificate**, not production-signed APKs. Android 9 or newer is
required. The universal build includes ARM64, ARMv7, x86 and x86_64. OpenCL is
optional.

Verified signing certificate SHA-256:

`7a9256582e2627e5b715ef2648373e52d62c512127f40b2aff3ccbcc787d5792`

SHA256SUMS verifies the exact downloadable files. The application source/tag
is unchanged by this packaging correction. The original source archive may
mention the superseded unsigned preparation; use these installation instructions
and the updated GitHub draft notes for the files to install.
