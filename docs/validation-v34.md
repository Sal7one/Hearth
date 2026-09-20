# Hearth 0.19.0 / code 34 — floating service cards

20 September 2026. Home now presents six illustrated feature cards in a horizontal
pager, with a title, short description and direct action on each. The selected
service is stored by stable ID and restored to its index on Home. Settings do not
replace the remembered feature. The three-button chooser has been removed.

## Gates

`./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa` passed in 3m 6s (268 tasks).
App suites: 254 cases per variant, zero failures/errors, with existing expected
skips (one Play, two FOSS). Common JNI debug/release each have 74 passing cases.
New host coverage checks service identity restoration, unknown-value fallback,
feature routing and exclusion of settings destinations.

`python3 scripts/verify-release.py` passed for both APKs, including flavor
permissions and native 16 KB alignment. No native/runtime, permissions or provider
code changed; native inference suites were not rerun.

## Phone checks

Samsung SM-S908E / Android 16, cloud QA installed over the existing app. Inspected
the captions and conversation cards on the phone. Artwork, title, description
and action fit without vertical scrolling at the tested font size. A physical
horizontal swipe changed captions to Conversation (card 2 of 6). Next controls
advanced to Type to translate (card 4 of 6). Its action opened typed translation,
and Home restored card 4. Force-stopping and cold-launching the app also restored
card 4. No recording, inference or screen-sharing session was started.

Cards include a vertical scroll fallback for smaller windows and enlarged text.
Decorative artwork does not create extra accessibility focus targets; headings,
actions and position controls have native semantics. A complete TalkBack,
large-font, RTL and device matrix audit remains unverified.

## Artwork

Six generated illustrations are bundled as local WebP resources; no image network
requests are needed. Generation method, exact prompts and saved paths are in
[home-artwork.md](home-artwork.md). These are conceptual artwork, not screenshots.

## Artifacts

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| `hearth-v74-floating-cards-cloud.apk` | 35,955,653 | `9946c321188d058ab60c93a8254822c5d2330e4b28d86f99b38dc0d1b5f263fd` |
| `hearth-v74-floating-cards-offline.apk` | 28,122,967 | `902193d6cae1100b3bfd704eabb922494813594ddf43c0bdd84a69819854f42a` |

Debug-signed, release-optimized QA builds. APKs and phone screenshots remain
outside Git. A GitHub draft provides off-network test delivery; its remote source
target must not be treated as matching the binaries until this source is pushed.
