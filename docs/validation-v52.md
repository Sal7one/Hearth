# 0.22.10 QA validation — translate over the source app

**Hearth Translate** no longer passes selected/shared text to `MainActivity` on
launch. `TranslateSelectionActivity` is a floating dialog-themed Activity with
the existing `TypedTranslationController` and saved text-translator route. It
shows the source app behind the result, translates immediately, and offers
source/target language choices supported by the selected translator. Close,
Back, or tapping outside dismisses it. Copy returns to the source app; an
editable `ACTION_PROCESS_TEXT` selection also offers **Replace selection** via
`RESULT_OK` and `EXTRA_PROCESS_TEXT`. Read-only selections never show Replace.
The full translator opens only when the user chooses that action after an
error.

The Activity keeps standard launch behavior, so Android's text editor can
start it for a result in its task. It is excluded from Recents and has no
separate persistent task affinity. No `SYSTEM_ALERT_WINDOW` permission is
needed for this Activity. Android documents
[floating dialog activity themes](https://developer.android.com/reference/android/R.style),
the [process-text result contract](https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT),
and [task/launch behavior](https://developer.android.com/guide/components/activities/tasks-and-back-stack).

Build checks passed on 24 September 2026:

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa --offline --quiet`
- `python3 scripts/verify-release.py` — Play QA 44,751,974 bytes; FOSS QA
  36,822,169 bytes; both 16 KiB aligned with the expected permissions.

Phone behavior remains unverified. The owner performs the device checks below;
no device was driven by the coding agent.

Owner device checks:

1. In a browser or message, select text and choose **Hearth Translate**. The
   source app should remain visible behind a compact translation window.
2. Change both languages; confirm unsupported directions show the real
   route error rather than a fabricated translation.
3. Copy, Back, Close, and outside tap should return to the source app.
4. In an editable text field, **Replace selection** should replace only the
   selected text. It must not appear for browser/read-only selection.
5. Share text from an app that has no process-text menu action. It should use
   the same floating window. Repeat with the FOSS APK and a local translator.
