# 0.5.2 / versionCode 13 — open language pickers at the current selection

The shared LanguagePickerContent now initializes its lazy list at the selected
language, accounting for the explanatory note row. This applies to app spoken and
target language dialogs, overlay language settings and ML Kit’s pack picker.
Searching starts at the top of filtered results; clearing the search returns to the
selected language. Ordinary scrolling does not trigger another jump. An unavailable
selection or empty search result falls back to the start of the list.

This is a UI-only fix. Model capabilities, saved language values, inference and
native runtimes are unchanged. Required tests, both QA compiles and APK builds,
release verification and the device check are recorded below after execution.

Validation: `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa` PASS (268 tasks, 97 executed /
171 up-to-date). Both APKs passed scripts/verify-release.py. No native changes.

Authorized Samsung SM-S908E / Android 16 check: selected Swedish in the app’s
spoken-language picker, dismissed and reopened it. Swedish was visible and its
radio row was checked. Searching Arabic displayed the matching result; clearing
that query returned the list to checked Swedish. Restored the prior Russian
selection afterward; capture stayed stopped. Overlay and ML Kit use this same
component; no separate overlay capture session was run for this UI-only change.

0.5.2 is installed. Play APK: 32,841,338 bytes; foss: 24,475,807 bytes.
