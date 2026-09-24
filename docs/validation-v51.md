# 0.22.9 QA validation

The Android selected-text entry is now a real exported Activity, labeled
**Hearth Translate** at both the Activity and intent-filter level. It accepts
`ACTION_PROCESS_TEXT` with `text/plain`. The same Activity accepts
`ACTION_SEND` with `text/plain` or `text/html`, so apps whose selection menu
does not list third-party actions can use **Share → Hearth Translate**. Both
paths open Type to translate with the received text and start the selected
translation route. Missing text is reported instead of opening an empty result.

This fallback is required by Android's discovery model: AOSP's text editor
[queries installed PROCESS_TEXT activities](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/widget/Editor.java),
but Android's [package-visibility guidance](https://developer.android.com/training/package-visibility/use-cases#text-selection)
requires the *source app* to declare a matching `<queries>` entry to show
third-party text actions. Hearth cannot add that declaration to another app.
Android's [Sharesheet receiving guide](https://developer.android.com/develop/ui/compose/sharing/receive)
documents `ACTION_SEND` text targets as the supported fallback.

Validation: `./gradlew test :app:compilePlayQaKotlin
:app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa --offline`
passed. `git diff --check` and `python3 scripts/verify-release.py` passed.
The packaged manifest was inspected and contains the exported real Activity,
both actions, both text MIME types and the label. The verifier checked both
flavors' permissions, native libraries and 16 KiB alignment. No native code
changed. No phone or paid-provider test was performed.

| QA artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Play | 44,730,282 | `6dcbc868ede1811cc3970e25ec28fc0f5ec23f60b222d7b0485eac962988fcb9` |
| FOSS | 36,802,093 | `e319c6391972ab3c173ef6416fe25953c23c91e56997493082c8fb1b6c57bc5a` |

Owner device check: install 0.22.9 QA and select text in a browser or message.
Check **More** for **Hearth Translate**; if absent, tap **Share** and choose
**Hearth Translate**. Confirm it opens with the exact selection, translates
using the chosen route, and Back returns to the source app. Repeat with one
app where the previous 0.22.8 build failed and with the FOSS APK.
