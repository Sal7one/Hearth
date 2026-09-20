# Language capability ownership

Audited 20 September 2026, Hearth 0.21.4. Language display names are not evidence
that a model supports a language. UI pages consume shared capability owners instead
of creating their own shortlists. Unknown capabilities stay unknown.

| Route | Capability owner | UI consumers and limits |
|---|---|---|
| OpenAI live translation | `CaptionLanguages.openAiTranslation`, `CloudSpeechLanguages` | Easy setup and caption/overlay settings share ISO-name suggestions and explicit 2–3 letter codes. The provider validates the requested code. No documented language discovery endpoint or output enum was found. |
| Local ASR | `SpeechProfile.capabilities`, Whisper vocabulary inspection, installed Vosk model | Caption source picker exposes model/runtime force-language support. English-only and model-fixed paths remain constrained. |
| Cloud ASR | `CloudSpeechLanguages` plus selected adapter mode | Central model-specific metadata; Scribe's truncated shortlist was expanded. Known transcription, Deepgram and Soniox lists remain snapshots, not live discovery. Unknown custom models do not inherit them. |
| Local text translation | `TranslationCatalog` / `TranslationOptions` | Captions, conversation/face-to-face, typed translation, camera/reading and model settings reuse model language/direction data. Unknown model IDs no longer inherit HY languages. |
| ML Kit translation | `PlatformTranslation.languages` | Play reads `TranslateLanguage.getAllLanguages()` from its installed SDK, normalizing `tl` to shared `fil`. FOSS returns an empty set and cannot advertise downloaded packs. |
| Cloud text translation | `CloudTranslationProtocol` discovery and `ConversationTranslationSettings` | Google, Azure, DeepL and LibreTranslate expose their discovered source/target sets; direction restrictions are preserved. Missing discovery does not manufacture a global language set. |
| Paddle OCR | `OcrModelCatalog` pinned reader metadata | Camera and reading source selection can choose the compatible reader. Latin, Arabic-script and East Slavic readers now include documented coverage instead of small UI subsets. Manga/Meiki remain Japanese-specific. |
| Android TTS | Installed Android engine voice discovery | Existing device voice capabilities remain authoritative. |
| Local custom TTS | Voice model metadata | The model's supported languages/voices remain constrained. |
| Remote custom TTS | Checked server capabilities | Language/voice metadata is hidden while endpoint/key edits are unchecked; changing roots cannot carry the old key. |

## Connection lifetime

Translation discovery captures one endpoint/region/key snapshot. Saving creates a
new generation and clears old languages. A result can be stored only if that same
generation remains current. Forget invalidates it as well. Saved credentials are
reused only for the same validated API root. Remote voice setup disables edits while
checking and hides previously discovered capabilities when the draft changes.

## Intentional limits

Fixed local weights cannot discover new language support by adding names to a
picker. Their model catalogs remain versioned metadata. The legacy English audio
translation / English-to-Arabic model path retains its real restrictions. Model
imports and runtime contracts were not loosened. Provider language snapshots must
still be maintained when those providers change; this release does not invent a
universal discovery API. Existing text-provider base-code normalization and regional
wire mappings remain unchanged. Showing an OpenAI suggestion is not a guarantee of
successful inference or quality. Provider errors remain visible.

No new model weights, native inference backend or paid API requests were used in
this pass. Broader documented Paddle/Scribe coverage is not a per-language phone
accuracy result. See [validation](validation-v41.md).

## Sources

- [OpenAI translation session events](https://developers.openai.com/api/reference/resources/realtime/translation-client-events): output language is a string, not a published enumeration.
- [OpenAI realtime translation guide](https://developers.openai.com/api/docs/guides/realtime-translation): session semantics and examples.
- [ML Kit language API](https://developers.google.com/android/reference/com/google/mlkit/nl/translate/TranslateLanguage): SDK language discovery.
- [PaddleOCR multilingual model table](https://www.paddleocr.ai/latest/en/version3.x/algorithm/PP-OCRv5/PP-OCRv5_multi_languages.html): model/script coverage. Latin-script Serbian/Kurdish are distinct from other script readers.
- [Scribe language coverage](https://elevenlabs.io/docs/overview/capabilities/speech-to-text) and [realtime language parameter](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime): model-specific language metadata and ISO code input.
