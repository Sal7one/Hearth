# Hearth 0.22.1 / code 43 — cloud translator selection

20 September 2026. v83 QA. No native runtime, model weights, endpoint or key storage changed.

## Cause and fix

Advanced caption engine selection previously changed the speech engine while retaining
`textTranslationProviderId=local` (or another explicit translator). That override takes
precedence over integrated speech translation. The local model named in the destination
picker therefore represented the actual retained route, not merely a cosmetic label.

`selectCaptionEngine` is now shared by advanced engine chips and the Cloud speech
connection selector. Explicitly choosing OpenAI/Soniox streaming selects its integrated
translation route and clears the older override. Local model IDs/files remain remembered;
a separate translator deliberately selected afterwards still takes precedence. CC mode
stays CC until the user enables Translate. ASR-only connections retain their separate
translator. Opening settings alone does not change configuration.

The caption translator chooser names the integrated cloud provider and explains that no
local model is required, in all three interface languages. The compact speech label uses
the streaming connection name instead of the unrelated saved batch model name.

## Checks

Final commands passed:

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin \
  :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
```

Six app suites: 296 cases each, zero failures/errors; existing skips one per Play
variant and two per FOSS variant. Common JNI Debug/Release: 74 each, zero failures,
errors or skips. Four new regression cases exercise integrated-provider selection,
preferences round trips, target-picker agreement, CC → Translate, a later deliberate
local override and ASR-only connections. Existing explicit-override tests continue to pass.
Both QA artifacts pass native hash, dependency, 16 KiB alignment and flavor permission
verification. No native source changes require new native host checks.

Logs: `/tmp/hearth-v43-gates.log`, `/tmp/hearth-v83-artifacts.log`.

## Phone journey

Play QA on Samsung SM-S908E / Android 16. Select Cloud → Streaming · OpenAI in Speech
settings, then open the Live captions destination picker. Verify OpenAI's capability
note and absence of a Hy-MT note; repeat after process restart. Verify the compact
streaming label and the named OpenAI cloud translation chooser in advanced captions.
The picker still scrolls to the saved language; the test scrolls up to inspect its note.

UI log: `/tmp/hearth-v83-phone.log`; screenshots:
`/tmp/hearth-v83-cloud-target-picker.png`, `/tmp/hearth-v83-cloud-translator.png`.
The journey leaves OpenAI streaming selected, with the existing target preserved.
No keys are edited, no downloads or capture are started, and no paid request is made.

Limits: routing, persistence and visible provider identity are tested; live server
translation quality and billing are not tested here. FOSS is built/host-tested and
artifact-verified rather than installed over the owner's cloud QA package. A plain
batch/ASR-only provider is not promised arbitrary integrated translation.

## Delivery

Both QA files are ARM64 Android 9+, release-optimized and debug-signed under
`com.sal7one.transiber.qa`; they replace one another. Delivery is a private GitHub
draft. Source remains unpushed; remote main does not match these binaries. Push matching
source and update the draft target before public publication or using source archives.

`git diff --check` passed. APKs in `/tmp/apk-serve`; SHA-256:

```text
5758043b7d89c40eaeef248948e1b590b20860f4baa6a6f55e69f9c094b5dd25  hearth-v83-cloud-translation-default-cloud.apk
777377c6b6c201e4cc1c87c5219c4962937fc72699c03c21d6a25e49f90b04c0  hearth-v83-cloud-translation-default-offline.apk
```
