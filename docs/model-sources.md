# Sources and installation by model group

Updated 2026-09-13 for 0.8.2. These are supported adapters, not a claim that every
artifact/language pair was tested on a phone. Models → Speech / Translation / Cloud
contains the same source links and installation distinctions. Network actions are
available only in play. All imported files still pass their existing validation.

## Speech on this phone

| Choice | Publisher / files | Installation |
| --- | --- | --- |
| Nemotron 3.5 0.6B | [NVIDIA card](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b), [pinned Q8 GGUF](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/resolve/1c8deaecc64b91f034d73e08dd8b64625eb3395d/nemotron-3.5-asr-streaming-0.6b.q8_0.gguf) | Download & install in the app; the pinned GGUF is verified and installed automatically. |
| Qwen3-ASR 0.6B | [Qwen card](https://huggingface.co/Qwen/Qwen3-ASR-0.6B), [sherpa INT8 files](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2) | Download & install automatically; archive extraction happens on the phone. |
| Qwen3-ASR 1.7B | [Qwen card/files](https://huggingface.co/Qwen/Qwen3-ASR-1.7B) | Compatible custom package only; no verified ready-made download in this app. |
| Moonshine Tiny English | [Moonshine](https://github.com/moonshine-ai/moonshine), [sherpa Tiny files](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2) | 28.5 MiB publisher archive; automatic on-phone installation. |
| Moonshine Base English | [sherpa Base files](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-base-en-quantized-2026-02-27.tar.bz2) | 106.1 MiB publisher archive; automatic on-phone installation; inference not phone-tested here. |
| Whisper | [whisper.cpp files](https://huggingface.co/ggerganov/whisper.cpp/tree/main) | Import GGML .bin; .en variants fix English. |
| Vosk | [publisher language catalog](https://alphacephei.com/vosk/models) | Download/extract model ZIP, import the extracted model folder. |

Catalogued downloads install automatically, including tar.bz2 extraction and raw
Nemotron GGUF packaging. Existing prepared ZIPs remain importable. Developer
packaging instructions are in [model setup](models.md), for custom packages only.
Archive sizes are download sizes, not peak RAM or installed-model sizes.

## Translation on this phone

| Choice | Source | Installation |
| --- | --- | --- |
| ML Kit | [Google translation guide](https://developers.google.com/ml-kit/language/translation/android), [terms/privacy](https://developers.google.com/ml-kit/terms) | Explicit Wi-Fi language-pack download inside Models; SDK-managed storage. Play only. |
| HY-MT1.5 Q4/Q6/Q8 | [Tencent GGUF files/card](https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF) | Choose size, download and install, or import exact pinned GGUF. |
| Hy-MT2 Q4/Q6/Q8 | [Tencent GGUF files/card](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF) | Same verified download/install flow. |
| TranslateGemma 4B Q4 | [Google original](https://huggingface.co/google/translategemma-4b-it), [supported conversion](https://huggingface.co/mradermacher/translategemma-4b-it-GGUF) | Download/install the catalog’s pinned 2.49 GB conversion. |
| Marian English → Arabic | [original](https://huggingface.co/Helsinki-NLP/opus-mt-en-ar), [ONNX conversion files](https://huggingface.co/onnx-community/opus-mt-en-ar/tree/main) | Legacy folder import; required tokenizer and merged-decoder files are described in the app. |

The catalog pins exact GGUF URLs, revisions, byte sizes and hashes; changing a file
name does not make an unsupported architecture compatible. See
[local translation](local-translation.md) for terms, supported routes and limits.
The unsupported HY 2-bit SEQ artifact and unimplemented Gemma 4 runtime are not
presented as installable choices.

## Cloud providers

Cloud models need a provider account/key, not a local weight download.

- [Soniox v5](https://soniox.com/docs/api-reference/stt/websocket-api): original captions and integrated translation.
- [ElevenLabs Scribe v2](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime): speech recognition; optional local translation bridge.
- [OpenAI realtime](https://platform.openai.com/docs/guides/realtime): existing live speech/translation routes.
- [Deepgram](https://developers.deepgram.com/docs/models-languages-overview): existing streaming recognition.
- [AssemblyAI](https://www.assemblyai.com/docs/api-reference/overview): existing streaming recognition.
- [OpenAI-compatible batch audio](https://platform.openai.com/docs/guides/speech-to-text): configurable endpoint/model; browse speech models using the account’s endpoint.

The cloud screen shows the selected provider’s key and documentation. Optional
batch/voice endpoint settings are collapsed for streaming connections. Soniox and
Scribe live-account verification remains pending, as recorded in validation-v11.md.


Catalogued native downloads now use `SpeechDownloads` for exact lengths, SHA-256
pins and roles. `PublisherSpeechPackage` creates the existing verified internal
manifest after bounded extraction. No user needs to create it or run a script.

The Moonshine assets used here were uploaded on 27 February 2026, as shown by the
GitHub release assets API. The `asr-models` release is a long-lived collection;
its creation date is not the model date. These are short-segment sherpa adapters,
not an assertion of support for the latest persistent Moonshine streaming decoder.

## Cloud text translation

Conversation and Face to face additionally support Google Cloud, Microsoft Azure,
DeepL and LibreTranslate. See [connections, official sources and setup](conversation-cloud-translation.md).
These services translate text; they are separate from the speech model catalog.
