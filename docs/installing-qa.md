# Installing Hearth on Android

Download an APK from the [latest Hearth release](https://github.com/Sal7one/Hearth/releases/latest).
Choose **Play ARM64 APK** for most recent phones, including the Galaxy S25 Ultra and
S22 Ultra. Choose the Play universal APK from **Assets** for a supported TV or if
you are unsure which CPU your device uses. For a fully offline phone installation,
choose the corresponding **FOSS** APK and import models from local files.

Play enables model downloads and cloud or self-hosted providers. FOSS has no
network permissions. TVs use cloud speech and translation with installed system
voices, so FOSS cannot provide cloud speech or translation on a TV.

These are release-optimized QA builds for sideloading, signed with Hearth's
existing QA debug certificate. They require Android 9 or later. Device audio
capture requires Android 10 or later.

## Update an existing installation

Open the downloaded APK and follow Android's installation prompt. If prompted,
allow the browser or file manager you are using to install unknown apps.

Keep the existing app installed and install the new APK over it. The GitHub QA
builds share package `com.sal7one.transiber.qa`; Play and FOSS replace each other.
A compatible update preserves app-private history, settings and model files. An
APK from a different signing key cannot update the QA build. Check the signer
and package before uninstalling, since that removes app-private data.

## Verify the download

Download `SHA256SUMS` from the same release. Compare the hash of the downloaded
APK against the row for its exact filename using `sha256sum <file.apk>` on Linux,
`shasum -a 256 <file.apk>` on macOS or `Get-FileHash <file.apk> -Algorithm SHA256`
in PowerShell.

The published QA signing certificate SHA-256 is
`7a9256582e2627e5b715ef2648373e52d62c512127f40b2aff3ccbcc787d5792`.
