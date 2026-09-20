# Hearth 0.21.3 / code 40 — Easy setup navigation

20 September 2026. v80 QA. No native, inference, provider or model changes.

## Changes

The wizard previously handled system Back locally while the app header left its
page entirely. Saved child state could reopen a local/cloud branch. MainActivity
now owns the saveable `EasySetupStep`, and both Back actions use its transition:
confirmation → form → chooser → caller. Fresh explicit setup entries reset the
wizard; temporary Downloads navigation preserves the current step. Full Settings
remains an explicit switch into advanced settings. API keys remain unsaved drafts.

Home's setup entry has a collapse action and an expandable left-edge tab. Its
boolean lives in `hearth-home` SharedPreferences and defaults to expanded. Both
controls have 48 dp targets and accessibility labels. Setup remains available in
Settings and quick Settings. Existing service selection and theme remain intact.

## Automated validation

`./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa` passed. Six app suites: 279 cases each,
zero failures/errors; existing skips: Play one, FOSS two per variant. Common JNI
Debug/Release: 74 cases each, no failures or skips. Four added step tests cover
local/cloud back chains, valid completion and serialized restoration.

`python3 scripts/verify-release.py` passed both QA APKs, including native hashes,
16 KiB alignment and flavor permissions. No native host run needed for this UI change.

## Phone evidence

Samsung SM-S908E / Android 16, final Play QA installed over the previous QA app.
Header and Android Back both return local/cloud forms to the chooser, then Home.
Opening setup from full Settings returns to Settings after backing out of the chooser.
Explicit Home from Cloud followed by a fresh setup entry shows the chooser.
Applying the already-installed local preset reaches confirmation; header Back
returns to the local form, and Android Back then returns to the chooser.
Collapsed and expanded Home states each survived force-stop/relaunch, and both
screenshots were visually inspected. No model download or paid cloud call ran.

The connected phone uses three-button system navigation: Android Back dispatch
was exercised using KEYCODE_BACK. Gesture animation itself was not tested. Cloud
completion transitions have host coverage; no credentials were changed to test
cloud saving. TalkBack labels/target sizes are implemented, but a full spoken
TalkBack session was not performed.

## APKs

- `hearth-v80-setup-navigation-cloud.apk` — SHA-256 `1c15100c515034eb607c0d22148692820912ba1be96061ccf3cd679eef990072`.
- `hearth-v80-setup-navigation-offline.apk` — SHA-256 `b0e88b936bf4b14e73a2999c31a0f82567f637f60eaf3d1a75b05225f62aa019`.

Private draft delivery only; source remains unpushed. Push the matching source and
update the draft target before publishing matching source archives.
