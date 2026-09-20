# Hearth 0.22.1 public preview preparation

21 September 2026. Version code 43. This release brings the intervening private
preview work to the public PoC release channel. Application code matches the
[v43 routing fix](validation-v43.md); this publication change updates documentation
and actual device screenshots, without changing runtime behavior.

## Verification before the publication commit

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin` passed.
  Six app suites contain 296 cases each, with zero failures/errors; existing skips
  are one per Play variant and two per FOSS variant. Common JNI debug/release each
  contain 74 cases, zero failures/errors/skips.
- Common native utilities, speech (50 checks), Vosk adapter, OCR (14 checks) and
  voice bounds host suites passed. Python voice server tests (8) and speech package
  tests (5) passed. These do not establish every model's device performance.
- Gitleaks 8.30.1 scanned all 54 existing commits with no remaining findings.
  One historical prose false positive in validation-v42 is recorded by its exact
  fingerprint in `.gitleaksignore`. No rule or directory was excluded. Its current
  wording was also changed. No actual credential was found or exempted.
- No tracked ignored files. APKs, signing material, model weights and local SDK
  configuration remain outside source control.
- Current QA and the previous public 0.11.0 QA APK have matching signing certificate
  SHA-256: `8f64a7b77a2a6f5ed0cee231824ff0edf769fa7ae099bd7899c8888033a6b473`.
  Both flavors remain test-signed under `com.sal7one.transiber.qa`; production
  release builds remain unsigned. The two flavors replace each other.
- Nine full-resolution screenshots were captured on Samsung SM-S908E / Android 16.
  [Capture notes](screenshots/README.md) distinguish fresh local inference, stored
  synthetic examples and setup-only screens. No paid API request was made.

## Final publication gates

The public [release record](https://github.com/Sal7one/Hearth/releases/tag/v0.22.1)
records the final source commit, clean-checkout QA/release builds, packaged-artifact
verification, source-archive secret scan, hosted CI status and APK checksums. These
are performed after this documentation commit; the preceding checks alone are not
claimed to prove a clean-checkout build or hosted CI success.

## Scope and remaining limits

This remains an experimental PoC. Fresh screenshot inference covers a synthetic
English-to-Arabic example, not multilingual quality, sustained thermal behavior,
all cloud accounts or a broad TalkBack audit. Prior versioned notes retain their
actual device/provider coverage. The older demo video is explicitly labeled 0.8.2.
The publication request authorizes pushing reviewed source and a public preview;
old private drafts are not evidence that their generated source archives match
those draft binaries. Use the new versioned public release for matching source.

## Hosted runner setup correction

The first publication run stopped before compilation because the pinned Android
setup action defaults to `tools platform-tools`; Google's SDK repository no longer
provided `tools`. Explicitly request only `platform-tools` from that action. The
following SDK step still installs the pinned platform, build tools, NDK and CMake.
No test or verification gate is removed. The release links the resulting run.
