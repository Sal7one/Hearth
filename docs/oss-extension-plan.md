# Model expansion before and after OSS

Assessment: 2026-09-12, against the current standalone source (0.4.2).
Status: implementation update in 0.5.0; see validation-v11.md for evidence and limits.

The supplied report is a useful shortlist, but did not inspect this application.
Keep the working capture, overlay, key storage, import validation and native
ownership. Expand them in small feature slices. No benchmark framework, telemetry
or prerequisite plugin architecture. Existing gates and brief functional checks
remain required. A downloaded model is not a verified working backend.

## What already exists

| Report suggestion | Current implementation | Consequence |
| --- | --- | --- |
| Nemotron 3.5 streaming 0.6B | Pinned NVIDIA NeMo-Speech.cpp runtime and Q8 GGUF; streaming partials and explicit source language | Preserve this working route. sherpa ONNX/QNN would be another runtime/export for the same checkpoint. |
| Qwen3-ASR 0.6B | Pinned sherpa runtime, INT8 package, utterance windows, all 30 explicit language choices wired through JNI | Already available; do not describe it as native continuous decoding. The 1.7B profile is accepted but is not a phone performance claim. |
| HY translation | HY-MT1.5 and Hy-MT2 1.8B, each with Q4/Q6/Q8 pinned downloads | Hy-MT2 is already in the catalog. Smaller quantization is a separate task. |
| OpenAI live translation | Dedicated realtime translation client | Preserve the working setup. Its current callbacks deliver translated text without a separate original transcript. |
| Capabilities and stable text | SpeechCapabilities, SpeechTranscript IDs/revisions, final-only bounded translation queue | Extend existing types where a real new backend needs more information. |

Source: SpeechModels.kt, TranslationCatalog.kt, StreamingSttClient.kt,
OpenAiTranslateClient.kt, CaptionTranslationBridge.kt, text_model.h and the
speech/translation runtime-build.json provenance records.

Nemotron's current 28 normalized base-language choices cover 32 locales; the
report's locale count does not mean four missing base languages. Keep recognition,
language hints, translation directions and adaptation-only languages distinct.

## Implemented extension points in 0.5.0

Family-specific translation coverage/templates, separate cloud source/translation
updates, provider-specific source-language notes and a working contribution guide
now address the original gaps. See [model-contributing.md](model-contributing.md).
The remaining checklists distinguish code delivery from broader device/account checks.

## Implementation and remaining acceptance tasks

### RT-20 — A lightweight translation choice

User benefit: local captions with translation without loading another billion-
parameter model. This complements the smaller open-weight task RT-02/RT-03.

- [x] Add ML Kit translation as an optional play-only implementation of the
  existing translator contract; keep its dependency and model download delegation
  out of foss. Translation runs locally after language packs are available.
- [x] Give translator descriptors explicit source/target coverage and runtime
  identity; preserve existing HY IDs, preferences and verified downloads.
- [x] Show required language packs, download/remove actions, availability and
  attribution in the existing model UI. Do not promise SDK-managed packs can be
  imported/exported like our GGUF packages or live in our public models folder.
- [ ] Explain the English intermediate step for non-English pairs. Exercise
  Russian/Chinese to Arabic/English, missing packs, offline use after download,
  Clear, Stop and translator switching. Make no unmeasured speed/quality claim.

Google documents 50+ languages and downloaded local packs, with an English pivot
for non-English pairs and quality intended for casual translation.
[ML Kit documentation](https://developers.google.com/ml-kit/language/translation).

### RT-21 — Soniox captions with original and translated text

User benefit: one optional cloud connection can retain what was said alongside
the translation, useful in both captions and the planned conversation screen.

- [x] Extend the caption event boundary while retaining current client adapters:
  session generation, stable segment identity, revision, source/translation kind,
  language and independent finality. Represent absent original text honestly.
- [x] Add Soniox `stt-rt-v5`, encrypted BYOK configuration, one-way translation
  and CC-only mode. Read actual language/hint semantics from its protocol.
- [x] Handle replaceable interim tokens and committed tokens separately. Original
  and translation tokens are not one-to-one; use documented grouping/endpoints
  instead of inventing word alignment or translating already translated text.
- [x] Preserve Clear/Stop/reconnect behavior, reject old-session results, and keep
  both text streams readable with Arabic/RTL and TalkBack labels.
- [ ] Add focused parser/lifecycle tests and a brief authorized account check;
  document any missing live verification. No automatic second paid request.

[Soniox's protocol](https://soniox.com/docs/translation/stt-translation/rt-translation)
documents source and translation token tags in one connection, with independent
interim/final handling and no timestamps on translated tokens. Two-way mode can
follow with Conversation; it does not require a new main-screen control now.

### RT-22 — A contributor can add a model without rewriting the app

User benefit: more useful community integrations and truthful model selection.
Implement alongside RT-20/RT-21 using their real consumers, not ahead of them.

- [x] Document three contribution routes: another artifact for a supported
  family; a new local runtime/family adapter; a new cloud protocol adapter.
- [x] Extend existing descriptors only as needed: model/runtime revisions, file
  roles and hashes, architecture/template, quantization, license/model-card links,
  spoken languages, language-control mode, translation directions and streaming
  kind. Unknown capabilities stay unknown; no universal multilingual boolean.
- [x] Derive app/overlay choices and preflight checks from the same descriptors.
  Explain unavailable runtime/language choices before starting capture.
- [x] Keep bounded, versioned import manifests backward compatible. User-provided
  weights must match a supported adapter; ONNX/GGUF are containers, not a universal
  inference contract. Package metadata must not load arbitrary native plugins.
- [x] Link one working ASR adapter, translator and cloud parser as examples, with
  exact files to change, rebuild commands and focused tests. Reuse current tools.
- [x] Correct stale local-translation documentation about Qwen's source-language
  control and record licenses per exact artifact rather than all HY generations
  sharing a family-wide license statement.

Done when: the new working backend appears through normal Models/setup controls,
and a contributor can follow a short recipe without touching overlay geometry,
audio capture or encrypted key storage.

### RT-23 — Optional TranslateGemma quality alternative

- [x] Prove a pinned 4B conversion works with the selected llama.cpp build before
  adding a public download choice. Keep architecture validation explicit.
- [x] Implement its own template/tokenizer adapter, matching the publisher's
  structured source/target-language input. Preserve caption text as data and test
  control-token-like input, output stopping and cancellation.
- [ ] Add exact artifact hashes, license, coverage and actual file size. Exercise
  short translations while Nemotron is loaded and verify switching/cleanup.
- [ ] Offer it as a larger optional translator only after it works. Do not label
  it the smaller/faster choice or a requirement for OSS publication.

The [official model card](https://huggingface.co/google/translategemma-4b-it)
documents 55 languages and a specialized template. Parameter count alone does
not establish phone memory consumption or latency.

RT-23 evidence: the pinned conversion produced Russian → Arabic text in the native
host smoke test. Android runtime builds and APK verification pass. Sustained joint
phone inference remains unverified; it is a larger optional choice.

### RT-25 — Additional STT choices

- [x] Add Moonshine Tiny/Base English profiles through the existing sherpa runtime,
  package validation and picker. Label fixed English and utterance-windowed decoding.
- [x] Verify Tiny ZIP import and actual playback transcription on the phone.
- [x] Add ElevenLabs Scribe v2 Realtime with encrypted BYOK, explicit source hints,
  replaceable partials, final captions and the optional local translation bridge.
- [x] Test parser deduplication/errors and routing with focused host fixtures.
- [ ] Verify Scribe and Soniox with their own authorized live accounts.
- [ ] Exercise Moonshine Base on a phone before making device-performance claims.

### RT-24 — Optional new runtimes after the initial expansion

- [ ] Gemma 4 E2B via LiteRT-LM: implement text translation first; separately
  consider bounded audio clips with original/translated output. Keep streaming
  claims accurate and use runtime/device availability checks.
- [ ] Nemotron sherpa ONNX CPU first, then QNN if supported: use a separate
  compatible package/runtime identity, preserve the working NeMo-Speech.cpp
  option, and verify loading, language control and cancellation on the device.
- [ ] Add each only when its model/artifact/runtime combination works. Keep GPU,
  NPU and universal device support out of the default release promise.

[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) documents Android/Kotlin
and audio support. [Gemma audio guidance](https://ai.google.dev/gemma/docs/capabilities/audio)
uses mono 16 kHz float input with a 30-second limit; that is not evidence of our
continuous caption latency. [sherpa v1.13.5](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.5)
records Nemotron ONNX re-export and QNN export, not a demonstrated win over our app.

## Publication boundary

RT-20/21/22 implementations and RT-23/25 additions are in 0.5.0. RT-24 remains
optional. Gemini, conditional Android speech APIs and self-hosted Voxtral/Cohere
remain contribution candidates. Do not describe them as available in this APK.

Keep the current model choices and cloud configuration simple. New providers live
under setup; no new permanent tabs. Keep source text available when translation
fails. Existing queue limits, stale-result suppression and Clear behavior must
survive each integration. No promised sub-second target or large benchmark suite.

Current validation is recorded in [validation-v11.md](validation-v11.md).
