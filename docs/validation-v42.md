# Hearth 0.22.0 / code 42 — interface languages

20 September 2026. v82 QA. Native code, model weights and inference backends are unchanged.

## Changes and scope

[Localization guide](localization.md). English, Arabic and Simplified Chinese resource
sets cover 1,088 entries across the main screens, setup, settings, model/download
controls, captions, conversation, text/camera/reading translation and voice controls.
The app-language selector persists independently from speech and translation choices.
Arabic uses RTL; localized language names participate in picker search. Existing enum
IDs, selection keys, endpoints and model IDs remain unchanged. Actual backend/provider
errors retain their original text. AppCompat supports the per-app locale mechanism
and service contexts resolve the same localized resources.

## Automated validation

Final commands passed:

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin \
  :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
```

Six app test variants contain 292 cases each, zero failures/errors. Existing skips:
one per Play variant and two per FOSS variant. Common JNI Debug/Release: 74 cases
each, zero failures/errors/skips. Three new localization cases verify complete resource
keys, duplicate/blank rejection, identical format arguments and usable nested diagnostic
messages without Android. Existing speech/translation capability tests still pass.

Final build log: `/tmp/hearth-v42-final-gates.log`. Artifact log:
`/tmp/hearth-v82-artifacts.log`. Both QA APKs passed packaged native-library hashes,
16 KiB alignment and flavor permission checks. No new native host suite is required
because native files did not change. `git diff --check` passed in final review.

## Phone evidence

Samsung SM-S908E / Android 16, Play QA installed over the existing QA app. The journey:

- Select Arabic inside Appearance & navigation; verify selected radio and translated controls.
- Force-stop/relaunch and verify Arabic persists.
- Check Arabic captions, cloud Easy setup, Back to chooser and Downloads.
- Select Simplified Chinese; check typed translation, cloud Easy setup and conversation.
- Switch back to English and verify captions.
- Restore the original Follow phone setting; leave device-wide language untouched.

No inference, capture, paid provider request, key edit or model download is part of
this UI journey. Existing conversation content remains in its original languages.
Screenshots and exact UI assertions: `/tmp/hearth-v82-phone.log` and
`/tmp/hearth-v82-{arabic-language,arabic-captions,arabic-setup,arabic-downloads,chinese-language,chinese-typed,chinese-setup,chinese-conversation}.png`.
Arabic layouts and Chinese setup/conversation are visually reviewed for direction,
readability and clipping. This is selected-screen evidence, not exhaustive UI coverage.

## Limits

- Simplified Chinese is provided; a separate Traditional Chinese translation is not.
- Some internal diagnostics and third-party model descriptions remain English.
  External errors and user content intentionally remain verbatim.
- Restart an already-open native View-based reading overlay after changing app
  language to refresh all its labels. Compose caption windows observe locale changes.
- Pre-Android-13 locale persistence/service behavior has not been physically tested.
- No full spoken TalkBack journey or native-speaker editorial review was performed.
- FOSS receives host/build/artifact checks; it is not installed over the owner's cloud QA setup.

## Delivery

Both files are release-optimized, debug-signed QA APKs for ARM64 Android 9+ and replace
one another under `com.sal7one.transiber.qa`. Delivery uses a private draft; source
remains local until the owner chooses to push it. Remote main does not match these
binaries; do not publish the draft or use its generated source archives before pushing
and targeting the matching source commit.

APKs in `/tmp/apk-serve`; SHA-256:

```text
c2c518693a2b92e176589255d814cee189f4989a68f80e211efef3b8e95c2ec4  hearth-v82-app-languages-cloud.apk
9ef9ce3850a0c64d649361b1a2c77ab6b93a07d852d718bba55cf6884c02cd3c  hearth-v82-app-languages-offline.apk
```
