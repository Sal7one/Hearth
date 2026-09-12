# Language pickers and extending coverage

The app and overlay share `CaptionLanguageFields` / `LanguagePickerContent`.
Spoken language is available in both original CC and translation modes. Translation
always needs an explicit output language; Auto applies to speech recognition only.
App pickers search native names, English names and language codes. The overlay uses
a scrollable list without opening a keyboard over another app. Opening it temporarily
expands the bubble to at least 440 dp, clamped to the viewport; closing it restores
the saved reading height and settings scroll position.

## Small capability definitions, not another manifest format

- `common-jni/.../language/LanguageCatalog.kt`: display names, decorative flags,
  search and RTL metadata. This is not a whitelist of model support. Unknown new
  codes get locale labels or the code itself. A flag is a visual cue, not a dialect
  restriction; TalkBack ignores it and reads the language text.
- `common-jni/.../speech/SpeechModels.kt`: existing profile capabilities distinguish
  recognized languages from accepted source-language hints. Qwen recognizes 30
  languages but its bundled sherpa C adapter accepts Auto only. Nemotron advertises
  28 languages and passes supported hints through `SpeechOptions` to its native
  locale mapping. New profiles should advertise only what their adapter implements.
- `common-jni/.../translation/TranslationCatalog.kt`: each model declares source
  and target sets. The local translation picker consumes these sets (37 for current
  HY models); the existing translation bridge still checks each source/target pair.
- `app/.../caption/CaptionLanguages.kt`: maps the active app engine/provider mode to
  those capabilities. The UI and engine initialization use the same effective
  source policy. Unsupported saved hints remain saved, are identified in the UI,
  and are not sent to an incompatible adapter. Qwen translation uses the detected
  source rather than a stale manual hint.
- `TranslationTarget` is now a language-code value, not a three-entry enum. New
  translation models can add target codes without editing each UI or JNI binding.
  Old ENGLISH/ARABIC/CHINESE preferences remain readable; new targets persist by code.

Whisper uses the bundled whisper.cpp language table; English-only weights remain
English-only (the picker explains this). Vosk selects language through the installed
model. OpenAI-compatible transcription hints are suggestions whose coverage depends
on the configured provider/model. The current OpenAI live translation and AssemblyAI
adapters have no input-language hint parameter. Live OpenAI output choices retain
this app's existing English/Arabic/Chinese coverage; this change does not claim new
cloud output coverage. Deepgram now receives the selected Nova-3 language, with
Auto retaining `multi`. No provider keys, network behavior in foss, or native binaries
were changed.

Before enabling hints for a new backend, implement and validate the adapter option,
update its capability set, and add a host check covering rejection of unsupported
hints. Before adding a translator, declare its actual directions and connect it to
the existing text-translator contract. ONNX/GGUF describe file formats: tokenizers,
decoders, tensor layouts and streaming state still need the matching runtime adapter.
See `models.md` and `local-translation.md` for package/runtime requirements.

## Accessibility

Picker rows use radio-button semantics and a minimum 64 dp height. Back/search
controls are named, flags are decorative, native/English text is available to the
accessibility tree, and font sizes follow system scaling. Picker titles expose a
pane title and heading. Overlay switches expose one labeled toggle each, sliders
have accessible labels, and the drag handle offers directional accessibility actions.
This does not constitute a full-app accessibility certification or an auditory
TalkBack test; see the release validation for actual device checks.

## Provider references checked September 12, 2026

- [Deepgram Nova-3 languages](https://developers.deepgram.com/docs/models-languages-overview)
- [OpenAI live translation configuration](https://developers.openai.com/api/docs/guides/realtime-translation)
- [OpenAI audio translation endpoint](https://developers.openai.com/api/reference/python/resources/audio/subresources/translations/methods/create)

Native support is checked against the bundled adapters, not inferred from newer
upstream model marketing. Selecting a language is not a universal latency guarantee.
