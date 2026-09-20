# Hearth 0.21.0 / code 37 — Easy setup

20 September 2026. v77 QA. No native runtime or model artifact changes.

## Behavior

Home and Settings expose a two-step setup with illustrated Local/Cloud choices.
Local selects the installed Nemotron 3.5 model and Hy-MT2 Q4 translator, enables
translation, and shares the local translator with text/conversation/camera.
Only missing models are queued through the existing downloader. Cloud contains
OpenAI, OpenRouter and HTTPS compatible servers; OpenAI selects live translation,
while the other two select original-language captions. Full Settings remains
available. Opening the page alone changes no configuration.

Credentials are scoped to the selected provider and endpoint. Draft keys are not
saved in instance state. Only explicitly selected Custom + Batch speech may omit
a key; the HTTP client omits Authorization when it is empty. FOSS remains offline.

## Automated validation

`./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa`.

All six app suites: 265 cases each, zero failures/errors. Existing expected skips:
one per Play variant, two per FOSS variant. Common JNI debug/release: 74 cases each.
Six new host tests cover preset routing, preserved appearance/audio, credential
scope, valid server addresses, optional-key policy and actual HTTP headers/FOSS
blocking. Existing navigation coverage now includes page 15.

`python3 scripts/verify-release.py`: both final APKs pass native dependency/hash,
16 KiB alignment and flavor permission checks. FOSS has no network permission.

## Phone checks

Samsung SM-S908E, Android 16; upgrade retains installed models and settings:

- Two illustrated setup choices fit together on the chooser screen.
- Local setup recognizes existing Nemotron and Hy-MT2 installations.
- Use this setup selects both models, enables translation, confirms completion,
  and opens live captions with Nemotron and the saved Arabic destination.
- Cloud exposes exactly OpenAI, OpenRouter and Local server.
- OpenAI shows a masked key field, supported translation destinations, save action
  and key/pricing links. Server setup shows URL, speech model and optional key.
- Browsing provider choices did not save credentials or start provider requests.
- Final FOSS build disables the Cloud card, exposes both local import actions and
  omits model hyperlinks/download actions. Cloud/local build restored afterward.

These checks validate setup/navigation, not new inference quality or performance.
A fresh multi-gigabyte download, live cloud key validation and paid inference were
not performed. Existing downloader/import/runtime checks remain authoritative.
Full TalkBack, large-font, landscape and multi-device audits remain unverified.

## Delivery

Both QA APKs use `com.sal7one.transiber.qa` and replace one another. They are
release-optimized, debug-signed ARM64 test builds. Draft GitHub delivery contains
binary assets only; source remains local until the owner pushes it.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `hearth-v77-easy-setup-cloud.apk` | 36,038,181 | `46be115c531c9459e615b3979b80b55930bd70a308936fe8153cf0083fa850d1` |
| `hearth-v77-easy-setup-offline.apk` | 28,189,059 | `7a5b62fc5f4352ccee831dfa01823856e155221ae022a6bec7f1f942fb5fa2e2` |
