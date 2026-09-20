# Interface languages

Hearth 0.22.0 offers English (`en`), Arabic (`ar`) and Simplified Chinese (`zh`).
Settings → Appearance & navigation → App language contains Follow phone and the
three explicit choices. App preferences also exposes the same component. The choice
persists across process restarts. Unsupported phone languages use the English default.
This is the language of Hearth's controls, not an ASR language hint or a translation
language. Changing it never changes a saved provider, API key, model or language pair.

## Implementation

Android string resources are the source of UI copy: `values`, `values-ar` and
`values-zh` contain matching sets of 1,088 strings. `ui_strings.xml` contains screen
copy; `labels.xml`, `interface.xml`, `capabilities.xml` and `services.xml` cover shared
labels, navigation, capability explanations and service controls. Locale declarations
live in `res/xml/locales_config.xml` and the manifest. Chinese currently means
Simplified Chinese; there is no separate Traditional Chinese translation.

AppCompatActivity, AppCompat's locale storage and `AppLocale` connect the in-app picker
to Android's per-app language mechanism. `ContextCompat.getContextForLanguage` makes
localized resources available to service/application contexts on older Android versions.
`HearthTheme` and `UiLocaleProvider` supply RTL to Compose; reading overlay View
containers use the same locale's layout direction. Model IDs, URLs and credentials
remain unchanged; key connection inputs explicitly retain left-to-right text direction.
The implementation follows [Android's per-app language guidance](https://developer.android.com/guide/topics/resources/app-languages).

## Adding or editing copy

1. Add a descriptive resource name in all three locale directories. Reuse an existing
   resource when the meaning and grammatical context match; do not join translated
   fragments to construct a new sentence.
2. Use `val uiText = rememberUiText()` inside Compose and `uiText(R.string.name)` at
   display time. The resolver can be captured by non-composable click callbacks.
   Services use `context.uiText()`; do not cache a global Activity or translated label.
3. Use numbered format arguments such as `%1$s` and `%2$s`, with the same arguments
   in every translation. Escape literal `%` as `%%` in formatted strings. Keep Android
   XML escaping for apostrophes, quotes and markup.
4. Use `UiLabels` adapters for existing enums/catalog entries. Keep persisted IDs,
   protocol values, section selection keys and provider/model names stable. For domain
   messages that need host compatibility, use a deferred `UiMessage` with a diagnostic
   fallback, resolved only in the UI. Conversation status is an enum, not displayed text.
5. Run the standard tests and both flavor builds. `LocalizationResourcesTest` rejects
   missing/extra/blank translations, duplicate keys and mismatched format arguments.
   Inspect the affected screen in Arabic RTL and Chinese as well as English.

Language pickers retain native language names and add a localized secondary name.
Search also checks that localized name. Changing app language does not expand a model's
supported languages or pretend that a model supports automatic/forced language selection.

## Boundaries and review needs

Transcripts, translations, user-entered text, proper model/provider names, URLs and
external error messages are not translated by this layer. Some internal engine
status/diagnostic strings and third-party catalog descriptions still use their original
English text. Converting those needs structured status/message codes, not matching and
rewriting arbitrary error strings.

Existing native View-based reading controls are created in the current locale; restart
an already-open reading overlay to refresh all its labels after changing app language.
Compose caption controls observe locale changes. Notification controls are localized
when published/refreshed. These behaviors do not restart model inference implicitly.

Phone checks cover selected screens on Android 16, not every screen/state, older OS
versions or a full spoken TalkBack session. Native-speaker editorial review is still
welcome. See [validation evidence](validation-v42.md).
