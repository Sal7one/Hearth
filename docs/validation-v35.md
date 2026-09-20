# Hearth 0.20.0 / code 35 — colorful cards and simpler controls

20 September 2026. The Home carousel remains in place. This change extends the
visual design and simplifies the working screens, with colored translucent cards,
blurred local artwork, dedicated setup sheets, and a persisted Minimal backup.

## Scope

Captions, typed translation, conversation, camera, screen reading, shared settings,
downloads and manual read-aloud controls. Existing models, providers, inference
pipelines, permission requests and FOSS restrictions remain. Typed/camera model
links enter the appropriate library section. Reading setup refreshes its selected
OCR profile when returning from setup. No native source or runtime dependency was
changed; native inference/performance suites were not rerun for this UI change.

## Automated checks

`./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa`.

App suites: 254 cases per variant, zero failures/errors, with the existing expected
skips (Play one, FOSS two). Common JNI debug/release: 74 cases each, no failures.
The relevant behavior already has provider, voice selection, navigation and
controller tests. Presentation-only components were checked through phone journeys
rather than tests that copy their widget trees.

## Phone checks

Samsung SM-S908E, Android 16, cloud QA installed over the previous QA app. Exercised
captions settings, typed translation settings, conversation/history controls,
camera settings, screen reading/settings, and download Folder/From link sheets.
Shared Local/Cloud tiles expose the correct categories; Cloud omits Camera/OCR.
All these settings sheets have an explicit Close action. No caption, OCR or
translation inference, file download, audio recording, TTS playback or screen
sharing session was started; the camera preview was inspected without capture.

Color & blur and Minimal were selected in Appearance. The choice survived a cold
launch. Typed translation and its settings were checked at font scale 1.3; the
phone's previous font scale was restored. This is limited layout evidence, not a
complete TalkBack, switch access, RTL, tiny-window or all-provider-form audit.

Visual inspection caught inherited black text on transparent cards and a double
input border. Explicit foreground colors and a single input panel correct those
issues. Foreground text/camera content is never blurred. Background artwork uses
Compose blur on Android 12+; older versions keep the color wash without blur.
GPU, battery and sustained thermal performance of the effect remain unmeasured.

Final gates passed in 1m 58s (268 tasks). Both APKs passed flavor permission and
native 16 KB alignment verification. APKs and phone screenshots stay outside Git. Draft GitHub binaries
must not be published as a matching source release until the local source revision
is pushed and the release target is updated.

## Final evidence

The corrected typed input has one border; card labels and icons use explicit theme
foreground colors. Conversation settings retained the same scroll position after
close/reopen, verified against the same visible control's screen bounds. Color &
blur remained selected after a cold launch. Main feature actions and setup links
were exercised on the phone. Light and dark visual checks are included in the
local screenshot bundle; the phone's original theme selection was restored.

## Artifacts

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| `hearth-v75-colorful-ui-cloud.apk` | 36,000,969 | `cf0ab985fdd1346da5d517ad05385e66ba27b7654885a334109e1a676a95e17d` |
| `hearth-v75-colorful-ui-offline.apk` | 28,165,599 | `4ee02bb9dbcbd720bca5eace695ff5d8a9a78933239a2a1ce9fc00d718e1a4ba` |
