# Sources and installation by model group

Updated 2026-09-23. These are supported adapters, not a claim that every
artifact/language pair was tested on a phone. Models → Speech / Translation / Cloud
contains the same source links and installation distinctions. App downloads are
available only in Play. FOSS remains offline; custom files can still be imported.

## Speech on this phone

| Choice | Publisher / files | Installation |
| --- | --- | --- |
| Nemotron 3.5 0.6B | [NVIDIA card](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b), [pinned Q8 GGUF](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/resolve/1c8deaecc64b91f034d73e08dd8b64625eb3395d/nemotron-3.5-asr-streaming-0.6b.q8_0.gguf) | Download & install in the app; the pinned GGUF is verified and installed automatically. |
| Qwen3-ASR 0.6B | [Qwen card](https://huggingface.co/Qwen/Qwen3-ASR-0.6B), [sherpa INT8 files](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2) | Download & install automatically; archive extraction happens on the phone. |
| Qwen3-ASR 1.7B | [Qwen card/files](https://huggingface.co/Qwen/Qwen3-ASR-1.7B) | Compatible custom package only; no verified ready-made download in this app. |
| Moonshine Tiny English | [Moonshine](https://github.com/moonshine-ai/moonshine), [sherpa Tiny files](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2) | 28.5 MiB publisher archive; automatic on-phone installation. |
| Moonshine Base English | [sherpa Base files](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-base-en-quantized-2026-02-27.tar.bz2) | 106.1 MiB publisher archive; automatic on-phone installation; inference not phone-tested here. |
| Whisper | [pinned whisper.cpp artifacts](https://huggingface.co/ggerganov/whisper.cpp/tree/5359861c739e955e79d9a303bcbc70fb988958b1) | The app now offers 27 verified `.bin` downloads: tiny, base, small, medium and large-v3-turbo; English-only variants where published; Q5, Q8 and F16 choices where available. Select family → checkpoint → quant in Models → Speech. |
| Vosk | [publisher language catalog](https://alphacephei.com/vosk/models) | Import an extracted, compatible model folder. Hearth does not offer an unverified Vosk download button. |

Catalogued downloads install automatically, including tar.bz2 extraction, raw
Nemotron GGUF packaging and Whisper GGML `.bin` files. Existing prepared ZIPs
remain importable. Downloads are saved under the device's `Downloads/Hearth`
folder; installed models are copied into app-managed model storage and remain
ready without an export/re-import step. Large downloads support pause/resume
when the selected storage provider supports seeking and the server validates the
saved range. The app checks required transfer/install space first. Developer
packaging instructions are in [model setup](models.md), for custom packages only.
Archive sizes are download sizes, not peak RAM or installed-model sizes.

## Translation on this phone

| Choice | Source | Installation |
| --- | --- | --- |
| ML Kit | [Google translation guide](https://developers.google.com/ml-kit/language/translation/android), [terms/privacy](https://developers.google.com/ml-kit/terms) | Explicit Wi-Fi language-pack download inside Models; SDK-managed storage. Play only. |
| HY-MT1.5 Q4/Q6/Q8 | [Tencent GGUF files/card](https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF) | Choose size/quant, download and install, or import exact pinned GGUF. |
| Hy-MT2 Q2/Q3/Q4/Q6/Q8 | [Tencent GGUF files/card](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF), [pinned community Q2/Q3 conversions](https://huggingface.co/mradermacher/Hy-MT2-1.8B-GGUF/tree/d760e9708bbded7cee9aa135b4f1dedb42ed2fc4) | Five verified quant choices appear together by file size. Q2 is about 777 MB and Q3 about 951 MB; they reduce download/storage cost, but have not been proven more accurate or faster on every phone. |
| TranslateGemma 4B Q4 | [Google original](https://huggingface.co/google/translategemma-4b-it), [supported conversion](https://huggingface.co/mradermacher/translategemma-4b-it-GGUF) | Download/install the catalog’s pinned 2.49 GB conversion. |
| Marian English → Arabic | [original](https://huggingface.co/Helsinki-NLP/opus-mt-en-ar), [ONNX conversion](https://huggingface.co/onnx-community/opus-mt-en-ar/tree/d0118f36228ba00fb7ec2306ab5824fcd81e5c03) | 235.5 MiB across four pinned files; one-tap download, verification and install. Native phone smoke and in-app download verified; wording quality needs bilingual review. |
| Marian Russian → English | [ONNX conversion](https://huggingface.co/onnx-community/opus-mt-ru-en/tree/92ef0d550ca96ebd9cd5d13aab6ad41854d99a3d) | 235.4 MiB across four pinned files; the same one-tap installer. Native phone smoke verified the pair, not a live caption cascade. |
| Marian Chinese → English | [ONNX conversion](https://huggingface.co/onnx-community/opus-mt-zh-en/tree/8e3032ebeebbacda779fe95efa64c03b962f83f3) | 241.6 MiB across four pinned files; the same one-tap installer. Native phone smoke verified the pair, not a live caption cascade. |

The catalog pins exact GGUF and Marian ONNX URLs, revisions, byte sizes and hashes; changing a file
name does not make an unsupported architecture compatible. The smaller Q2/Q3
Hy-MT2 artifacts are ordinary GGUF quantizations supported by the current
runtime. They are distinct from Tencent's special 1.25-bit STQ file, which still
needs a different kernel and is not an installable Hearth option. See
[local translation](local-translation.md) for terms, supported routes and limits.
The unsupported HY 2-bit SEQ artifact and unimplemented Gemma 4 runtime are not
presented as installable choices. Phone speed/quality evidence is in the
[local translation measurements](local-translation-benchmark-2026-09-13.md);
the [Mac host smoke report](benchmark-host-smoke-2026-09-23.md) records newer
Q2/Q4 and Whisper comparisons. Mac and phone results must keep their
backend/device labels.

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

## Camera OCR

Version 0.12.0 adds experimental Japanese reading adapters:
[Manga OCR ONNX](https://huggingface.co/onnx-community/manga-ocr-base-ONNX),
[Meiki detector](https://huggingface.co/rtr46/meiki.text.detect.v0) and
[Meiki readers](https://huggingface.co/rtr46/meiki.txt.recognition.v0).
Use the exact pinned variants listed in [adapter setup](manga-ocr-adapters.md);
other quantizations are not interchangeable. Model cards in the app include
complete-package installation and copyable revision-specific file links.

PaddleOCR uses a separate image adapter with pinned detector/reader files, bundled
dictionaries, and model-aware language selection. See [camera models, downloads
and extension contracts](camera-ocr.md). Speech packages do not change.

## Read aloud

Models → Voices or Setup → Voices & read aloud provides Android's installed voice
inventory, native Supertonic 3 downloads/import and self-hosted model source links.
See [all voice artifacts, languages, licenses and server setup](voices.md).
