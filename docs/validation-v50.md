# 0.22.8 QA validation

Android's selected-text menu now offers **Hearth Translate** in both flavors.
The action opens Type to translate with the selected text and starts the
currently selected translation route. The existing `text/plain` share path
still opens the same screen without automatically sending the text. Back from
the selected-text screen returns to the source app; Hearth does not replace
the source app's text. The menu entry depends on the source app exposing
Android's standard text-selection actions.

Validation: `./gradlew test :app:compilePlayQaKotlin
:app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa --offline`
passed. `git diff --check` and `python3 scripts/verify-release.py` passed.
The verifier checked both flavors' packaged selected-text action, permissions,
native library inventory and 16 KiB alignment. No native code changed. No
phone or paid-provider test was performed.

| QA artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Play | 44,727,894 | `4236e5eea8da4d6e094704d5c9b64054ce7235cf543e2c0a5869179df8e27f04` |
| FOSS | 36,800,717 | `4510a1ed702297edfc648d82b54df8c23710829111fc2fae2ba7e68ddf8ca0ad` |

Owner device check: select text in a browser or message, tap **Hearth
Translate** (possibly under the menu's More action), and verify the selected
text appears in Type to translate and is translated with the chosen route.
Change the source and target languages, then use Back to return to the
original app. Repeat once with Hearth already open and once with the FOSS APK.
