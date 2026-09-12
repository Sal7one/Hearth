# 0.5.3 / versionCode 14 — cloud connection spacing

Date: 2026-09-13. The shared cloud settings section now supplies its own layout:
2.dp top padding and 8.dp between labels, fields, buttons and provider sections.
This also applies when the section is embedded in advanced setup. Provider chips
and Save/Remove actions wrap rather than competing for width, with 4.dp between
wrapped rows. Provider documentation cards use 4.dp between text and actions;
model-picker detail lines use 2.dp spacing.

The change uses existing Material field/button minimum sizes. No inference,
network, credential-storage or JNI behavior changes. No new tests were added for
this layout-only adjustment; the repository’s required regression gates still run.

Validation: required Gradle tests, both QA Kotlin compiles and both QA APKs PASS
(268 tasks; 97 executed / 171 up-to-date). Both APKs pass the release verifier.
Authorized Samsung SM-S908E / Android 16 UI check: inspected the cloud page and
scrolled to its endpoint/model fields. Base URL ends at y=1444 and batch-model
field begins at y=1474: 30 pixels / 8.dp separation at the device density. Provider
chips, documentation text/buttons and key controls render without overlap. No
credential or provider settings were changed. Capture stayed stopped; 0.5.3 installed.
