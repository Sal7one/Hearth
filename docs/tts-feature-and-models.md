# Standalone text to speech and model expansion

The **Text to speech** Home card opens a dedicated text → audio feature. No microphone,
speech recognizer, translator or overlay session is needed. Paste up to 5,000 characters,
choose a language and engine, then Speak. Stop and leaving/backgrounding the feature
cancel work. Native, Android and self-hosted playback use the existing three-request
bounded queue; the cloud route uses the existing bounded cloud queue. Errors remain errors.

The independent draft, language, engine, voice, gender, speed, quality steps and loudness
survive returning to the card. These preferences do not change the caption or conversation
voice selection. **Save text** exports UTF-8 through Android's document picker. It exports
text, not an audio recording. There is no automatic transcript upload or cloud TTS request.

## Working routes

| Route | Actual implementation | Options | Network policy |
| --- | --- | --- | --- |
| Android voices | Installed Android TTS engine; enumerated offline voices | Engine, language/region, voice, gender preference, speed, loudness | Both distributions; only voices marked offline are offered |
| Supertonic 3 | Existing owned JNI/ORT native runtime | 31 languages; F1–F5/M1–M5; 2–12 quality steps, speed, loudness | Both; verified imports in FOSS, pinned downloads in Play |
| Your server | Existing authenticated Hearth voice bridge + PCM16/WAV validation | Startup-advertised language/voice; gender where advertised; speed, loudness | Play only; user-configured HTTPS |
| Cloud | Existing OpenAI/OpenRouter/Gemini/ElevenLabs/custom provider adapter | Configured model/voice, gender mappings, loudness | Play only; explicit Speak sends text and may incur provider charges |

Android engine discovery has a 15-second timeout, drops late callbacks, and shuts its
probe service down after obtaining voices. It runs only when Android is selected.
An explicit saved voice is preserved while discovery is pending or fails. A
successful probe reports a missing choice without silently changing it; choosing
another engine/language explicitly resets the voice choice.
Playback creates its own engine connection. An uninstalled explicitly selected engine
fails before synthesis; a saved voice that disappears also fails, rather than selecting
an arbitrary different-language voice. Android's service initialization/fallback policy
is part of the platform API; installing an APK alone is not proof that its model loaded.

## Real additional families

**Kitten Nano 0.8 INT8** and **Kokoro 82M v1.0** now have first-party self-hosted adapters
in `scripts/voice/hearth_voice_server.py`. They are not claimed as embedded Hearth model
downloads. The same script still supports Qwen3-TTS Base, Chatterbox Multilingual V3 and
Fish Speech. The new adapters download model files at fixed publisher revisions and
advertise only supported voices/languages. Each should use its own Python environment.

| Family | Checkpoint / size information | Language contract | License / implementation decision |
| --- | --- | --- | --- |
| Kitten Nano 0.8 INT8 | 15M parameters, publisher describes ~25 MB model | English only, eight named voices plus default/female/male aliases | Apache-2.0 model; existing Python ONNX frontend, pinned revision |
| Kokoro v1.0 | 82M parameters; not a measured Android RAM budget | Up to nine locale routes across eight languages, selected at startup; no Arabic | Apache-2.0 weights; one shared model across tokenizer pipelines |
| Piper | Per-language ONNX/VITS voices | Defined by each installed model | Compatible external Android TTS engines; model licenses vary |
| Pocket TTS | Publisher describes CPU-oriented 100M family | Must validate the selected model's actual language coverage | Candidate for an owned native adapter; not advertised as an embedded Hearth download |
| KittenTTS 2 | New 1.7B family; 506 MiB embedding-quantized weights | Publisher describes 20 languages including Arabic | Different runtime and Stellon Labs Community weight license; not confused with Apache-licensed Nano 0.8 |

For Android-local Kitten/Piper/Kokoro, compatible third-party Android TTS engines can be
installed separately and selected in the new engine picker. The feature links to the
[sherpa Android engine guide](https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html)
and each family documentation in Play; FOSS only lists names and can open Android's own
speech settings. These integrations use Android's TTS contract, not a new bundled sherpa
runtime, and do not claim every external engine APK covers all four Hearth ABIs.

## Reproduce the new self-hosted adapters

Set your own `HEARTH_VOICE_API_KEY` as documented in [voices.md](voices.md), then put
an authenticated trusted HTTPS proxy in front of the localhost bridge.

Kitten **0.8** is deliberately installed from its versioned wheel; do not substitute the
new `kittenml` package/KittenTTS 2 checkpoint. It also needs its upstream eSpeak phonemizer
runtime. In an isolated Python environment:

```sh
python -m pip install 'https://github.com/KittenML/KittenTTS/releases/download/0.8/kittentts-0.8.0-py3-none-any.whl'
python scripts/voice/hearth_voice_server.py kitten
```

Pinned Kitten model revision: `84781d74e29ee25217551556398b42f80593a813`.
The adapter constructs the published ONNX frontend with the checkpoint's voice aliases
and speed priors. Default/female selects Bella, male selects Jasper. Unsupported
languages fail before model generation.

Kokoro's adapter was inspected against upstream code revision
`dfb907a02bba8152ca444717ca5d78747ccb4bec`:

```sh
python -m pip install 'git+https://github.com/hexgrad/kokoro.git@dfb907a02bba8152ca444717ca5d78747ccb4bec'
python scripts/voice/hearth_voice_server.py kokoro --device cpu --kokoro-languages en,zh
```

Pinned model/voice revision: `f3ff3571791e39611d31c381e3a41a3af07b4987`.
Language tokenizers and their dependencies initialize before `/capabilities` advertises
them. Supported startup codes: `en,en-GB,es,fr,hi,it,pt-BR,ja,zh`. French has no male
voice in this selected inventory; a male French request fails explicitly. Explicit voice
IDs cannot cross language pipelines. Pipeline audio chunks are concatenated in order
with finite/output bounds; an empty generator or missing audio is an error.

## Evidence and remaining work

The request policy and server adapter tests verify limits, fixed revisions, upstream
call arguments, language/voice isolation, regional tokenizer routes, preserved audio
chunks and empty-output errors. They do not measure model quality or device speed.

```sh
python3 -m unittest discover -s scripts/voice -p 'test_*.py'
```

An embedded Kitten/Kokoro/Pocket adapter still needs its own tokenizer/G2P contract,
owned cancellable native API, pinned package, all-four-ABI builds and real-model tests.
Do not reuse Supertonic's Unicode indexer or invent a generic ONNX manifest for these
architectures. New benchmarks must separate model load, first audio and total synthesis,
and must measure concurrent ASR without feeding read-aloud back into playback capture.

Primary sources checked 2026-10-11:

- [Kitten Nano INT8 model card](https://huggingface.co/KittenML/kitten-tts-nano-0.8-int8)
- [Kitten source / current model and license distinction](https://github.com/KittenML/KittenTTS)
- [Kokoro model card](https://huggingface.co/hexgrad/Kokoro-82M)
- [Kokoro voices and per-language limitations](https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md)
- [Kokoro language pipeline and shared-model API](https://github.com/hexgrad/kokoro/blob/dfb907a02bba8152ca444717ca5d78747ccb4bec/kokoro/pipeline.py)
- [Pocket TTS official source](https://github.com/kyutai-labs/pocket-tts)
- [Sherpa Kitten Android/C++ integration](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kitten.html)
- [Android TextToSpeech API](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
