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
  languages and now forwards explicit selections through sherpa stream options. Nemotron advertises
  28 languages and passes supported hints through `SpeechOptions` to its native
  locale mapping. New profiles should advertise only what their adapter implements.
- `common-jni/.../translation/TranslationCatalog.kt`: each model declares source
  and target sets. The local translation picker consumes these sets (37 for current
  HY models); the existing translation bridge still checks each source/target pair.
- `app/.../caption/CaptionLanguages.kt`: maps the active app engine/provider mode to
  those capabilities. The UI and engine initialization use the same effective
  source policy, resolved against the actual selected model before start. Unsupported saved hints remain saved, are identified in the UI,
  and are not sent to an incompatible adapter. Qwen translation uses the selected
  supported source, or detected metadata in Auto mode.
- `TranslationTarget` is now a language-code value, not a three-entry enum. New
  translation models can add target codes without editing each UI or JNI binding.
  Old ENGLISH/ARABIC/CHINESE preferences remain readable; new targets persist by code.

`SpeechSourceLanguage(code, canForce)` separates recognition coverage from manual
control per language. Pickers include only actionable choices. Automatic-only and
fixed-language paths show a plain value and model-specific explanation beside the
field, with no dropdown of unclickable languages. Unknown cloud models do not
inherit Whisper's languages. Known cloud model paths use a conservative common
language set; this is not an exhaustive claim about every provider's coverage.

Local Whisper reads the actual GGML header off Main before exposing choices:
51864 vocabulary is English-only, 51865 has the earlier multilingual vocabulary,
and 51866 includes Cantonese. Renaming an English model cannot enable other
languages. Vosk's language is fixed by its installed model. OpenAI live translation,
cloud audio translation and the current AssemblyAI adapter accept no source
language override; their source picker stays closed. Deepgram Nova-3 sends the
chosen source code and retains `multi` for Auto.

Qwen had an app-imposed Auto restriction even though our pinned sherpa source
already reads per-stream `language`. Version 0.4.2 removes that restriction through
all layers: profile validation → normalized ISO code in SpeechRuntime → native
validation → `qwenLanguageName` → `SherpaOnnxOfflineStreamSetOption` before decode.
The prompt receives the required English language name, while translation receives
the original ISO code. Both 0.6B and 1.7B profiles expose the same 30-language
coverage; this is not a new performance claim for the larger model.

Sources:
- [Pinned Qwen decoder, BuildSourceIds and stream language](https://github.com/k2-fsa/sherpa-onnx/blob/210f340bcfdfd5b9ad6b24245e77a934d6c28f1b/sherpa-onnx/csrc/offline-recognizer-qwen3-asr-impl.cc)
- [Pinned C stream-option API](https://github.com/k2-fsa/sherpa-onnx/blob/210f340bcfdfd5b9ad6b24245e77a934d6c28f1b/sherpa-onnx/c-api/c-api.h)
- [OpenAI live source-language hints](https://developers.openai.com/api/docs/guides/realtime-transcription#add-transcription-context)
- [OpenAI file transcription language configuration](https://developers.openai.com/api/docs/guides/speech-to-text#supported-languages)

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
