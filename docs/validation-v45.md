# Hearth 0.22.3 / code 45 — pipeline isolation (v85)

Date: 2026-09-21. Scope: [feature ownership audit](pipeline-ownership.md), local
translator selection isolation, typed-work retirement, overlay handoff and voice terminal reporting.

## Automated validation

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin`: PASS.
  All six app variants: 309 cases each, zero failures/errors; existing skips:
  one per Play variant, two per FOSS variant.
- Common-JNI debug/release unit tests: 75 cases each, zero failures/errors/skips.
- `:common-jni:externalNativeBuildDebug`: PASS.
- Both `assemblePlayQa` and `assembleFossQa`: PASS (minified, test-signed).
- `scripts/verify-release.py`: PASS. Pinned runtimes and OCR assets, packaged
  dependencies, 16 KiB ELF alignment, JNI visibility and flavor permissions verified.
- Native common host suite: six utilities PASS under ASan/UBSan, including the new
  LeaseRegistry isolation/retirement test. Speech: 50 checks PASS. OCR: 14 checks
  PASS. Voice bounds and Vosk local symbol/API loader checks: PASS.
- Gitleaks: tracked change scan and existing 57-commit history clean; the existing
  single historical prose false-positive exclusion remains unchanged.

The new tests reproduce typed blank-input ownership retention, stale work after a
setup error, Clear during allocation and same-language idle model retention.
A handoff regression verifies that the next workload waits for model cleanup and
cancelling a waiting screen cannot release another owner. JNI
bridge tests exercise two owners with overlapping work and independent cancellation.

## Device validation

Connected Samsung SM-S908E / Android 16, final Play QA APK installed successfully.
This is a different handset from the owner's reported SM-S938B.

Using the real UI, selected HY-MT1.5 Q8 for typed translation and TranslateGemma for
camera. Restarted the app between pages: each retained its own choice, while captions
still displayed OpenAI cloud translation. Conversation displayed the same HY-MT1.5
choice as typed translation; reading setup displayed the same TranslateGemma choice
as camera. Both local groups were restored to Hy-MT2, with captions still on OpenAI.
No account request, capture, model inference or history
change was initiated in this configuration-only check.

Previous tests already cover queue overload, stale caption/OCR revisions, lifecycle
cancellation and provider errors. This pass does not prove every installed model,
paid provider, simultaneous workload or low-memory condition on hardware. Native
production sources and vendored binaries were not changed.

## Artifacts

- `hearth-v85-pipeline-isolation-cloud.apk`: SHA-256
  `8a006311af434349d0fb2bbb691dd77f0f0342dd5f7571098e6bf9713b929475`
- `hearth-v85-pipeline-isolation-offline.apk`: SHA-256
  `1f64e2d9d470881342fac9c05492294614f4c324fa0e338d97a921fce873441b`

ARM64; Android 9+; QA package `com.sal7one.transiber.qa`. Both flavors replace one
another. These are test artifacts; the public release is unchanged.
