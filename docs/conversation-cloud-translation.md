# Conversation translation connections

Hearth 0.8.0 adds an independent text translator choice for **Conversation** and
**Face to face**. Open Conversation → Options → Translation, or Cloud connection →
Translation connections. This does not change the overlay translator. Speech still
uses the selected local model or existing cloud STT connection; only finalized text
is sent to the separately selected text translation provider.

| Provider | Credentials | Setup |
|---|---|---|
| Google Cloud Translation Basic v2 | Google Cloud API key with Translation API enabled | Default official v2 root; plain-text NMT request |
| Microsoft Azure Translator v3 | Translator resource key; region for regional/multi-service resources | Global official root by default; official Azure API, not a scraped Bing endpoint |
| DeepL | DeepL API Free or Pro key | Free: `https://api-free.deepl.com/v2`; Pro: `https://api.deepl.com/v2` |
| LibreTranslate | Depends on the server | Your HTTPS API root; optional key for self-hosted/keyless servers; libretranslate.com requires a key |

Use **Save & check languages**, then **Use provider**. A successful language fetch
is not a successful paid translation: Azure and Libre discovery can be public,
and quota/billing/translation errors still surface on the actual turn. Keys or
free tiers do not imply unlimited free usage. There is no silent provider fallback.
Changing an endpoint/key invalidates its capability cache until a fresh check.
Leaving a replacement-key field empty keeps the saved key; Remove saved key deletes
it and returns an active connection to on-device translation.

Language pickers use the selected provider's discovered source and target codes.
LibreTranslate's per-source target edges are preserved. The app currently shows
base languages: provider variants map to one supported wire code (for example
DeepL EN-US, PT-PT and ZH-HANS when advertised; Azure zh-Hans). This is not a full
regional-variant picker. Historical turns retain their actual source/target codes
when the current languages are swapped. Speech buttons also require the speech
model's declared recognition coverage; Type instead only requires translation.

## Extension boundary

- `CloudTranslationProtocol`: pure request/response codec and directional language
  data. Tests cover escaping, wire language mapping, Google/Microsoft/DeepL auth,
  Libre directed edges, invalid endpoints and malformed/error responses.
- `ConversationTranslationSettings`: per-provider configuration, checked capability
  cache and explicit selection. New translation keys use their own Android Keystore
  AES-256-GCM alias. It never deletes/regenerates an existing key on failure or writes
  plaintext key fallback files. Errors leave the existing speech key store alone.
- `CloudTextTranslator`: implements the existing `CancellableTextTranslator` boundary.
  Each turn snapshots its connection, opens one cancellable owner and closes it at
  completion/cancellation. No additional JNI library is needed.
- `TranslationHttpTransport`: 30-second call limit, 2 MiB response cap, no redirects,
  no automatic retries, no request/response logging. Coroutine cancellation and
  owner close cancel HTTP calls. Non-success responses include the status and exact
  body, with any echoed credential redacted. Empty/malformed translation is an error.
- `ConversationController`: preserves original text, attaches translation to the same
  stable turn and retains the existing microphone/workload ownership rules.

The `foss` build offers on-device translation only, has no network permissions and
blocks the client before it creates an HTTP call. Provider discovery and help links
are only available in the network-enabled build. No provider key is bundled with
Hearth or sent to model/download hosts.

## Official references checked 2026-09-13

- [Google v2 translate contract](https://docs.cloud.google.com/translate/docs/reference/rest/v2/translate)
- [Google API-key header authentication](https://docs.cloud.google.com/docs/authentication/api-keys-use)
- [Azure v3 translate](https://learn.microsoft.com/en-us/rest/api/translator/translator/translate?view=rest-translator-v3.0)
- [Azure language discovery](https://learn.microsoft.com/en-us/rest/api/translator/translator/languages?view=rest-translator-v3.0)
- [DeepL text translation](https://developers.deepl.com/api-reference/translate/request-translation)
- [DeepL v2 language discovery](https://developers.deepl.com/api-reference/languages/retrieve-supported-languages) is deprecated but still documented; monitor migration to v3.
- [LibreTranslate API usage](https://docs.libretranslate.com/guides/api_usage/)
- [LibreTranslate supported directions](https://docs.libretranslate.com/api/operations/languages/)
