# Hearth 0.18.1 / code 33 — three-button visual home

20 September 2026. UI-only correction following owner feedback: replace the
six-row feature directory and descriptions with Listen, Talk and Translate.
Original vector illustrations replace the small icon badges. Talk has two visual
choices; Translate has three. The gear remains the only settings entry on Home.

## Gates

`./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa`, followed by artifact verification.
App suites: 252 cases per variant, zero failures/errors, with existing expected
skips (one Play, two FOSS). Common JNI debug/release each have 74 passing cases.
No native/runtime, permissions or provider code changed; native inference suites
were not rerun. This reversible presentation change adds no mirrored UI unit tests.

Home buttons use flexible heights and a scroll fallback for smaller windows/large
text. Decorative art has no separate focus target; buttons expose descriptive
accessibility labels. This does not claim a complete TalkBack/large-font audit.

## Phone checks

Samsung SM-S908E / Android 16, cloud QA installed over the existing app. Home
shows exactly Listen, Talk and Translate plus the toolbar gear. All three large
buttons fit on this device without scrolling. Inspected the Home and Translate
chooser screenshots. Talk → Face to face, Translate → Text, Translate → Screen
and Listen opened their intended screens; returning Home restored the three
buttons. Screen opened setup without starting capture. A broken long-word wrap
in the Talk sheet was caught on the phone and corrected by labelling its option
Chat, while retaining the descriptive accessibility label and Conversation page.

The final APK's Talk sheet was re-inspected: Chat and Face to face fit cleanly.
Talk → Chat opened Conversation. Final Gradle gates passed in 2m 48s (268 tasks),
and both APKs passed flavor permission and native 16 KB alignment verification.
Cloud QA 0.18.1 is installed on the tested phone. No recording or screen-sharing
session was started by these checks.

## Artifacts

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| `hearth-v73-visual-home-cloud.apk` | 35,232,931 | `7302db960d4902a5019e06ed572b4965488857e8f11b224f6853d9bfb55afdca` |
| `hearth-v73-visual-home-offline.apk` | 27,397,885 | `2673985ffde1b57149442f2a69e1ab7821840da56c5fd2a61ab99291969737d4` |

Debug-signed, release-optimized QA builds. Files and phone screenshots remain
outside Git. A GitHub draft test delivery can host them for off-network testing;
the draft's source target must not be treated as matching these locally built
binaries until this revision is pushed.
