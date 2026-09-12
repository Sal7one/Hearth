# Optional local translation bridge

Qwen3-ASR and Nemotron produce original-language speech transcripts. Translation
is a separate text model; it never changes the ASR model's language capabilities.

## Use

1. Import/select Qwen or Nemotron in Models. Expand **CC language support** to
   see the speech model's supported languages.
2. Under **Local translation bridge**, choose a model/quantization. Q4_K_M is
   the smallest offered option (1080 MiB). Q6_K is 1406 MiB; Q8_0 is 1820 MiB.
   Weights are separate from the APK. Total RAM also includes both inference
   contexts and speech weights.
3. Download using the model button (network-enabled build), then tap **Install
   downloaded model** here or **Install translation model** in Downloads. New
   downloads go to `Downloads/Real time transiber/models` on Android 10 and later.
   Existing app-stored downloads also install directly; no export is needed.
   Alternatively import a publisher GGUF with the matching model selected. Exact
   pinned SHA-256 and size are required. The offline build only imports local files.
   Android 9 retains app storage without requesting broad storage permission.
4. Choose Arabic, English or Chinese as the target, then enable the bridge.
   CC appears immediately; translation attaches to its original line later.
   The bubble settings can disable/re-enable translation while ASR keeps running.
5. To compare another model, import/select it. The old translator is cancelled
   and released before the next one loads. Timings in the bubble include waiting
   for translation. They are not audio-to-display latency or an accuracy score.

Automatic routing uses the language reported by ASR. Unknown/mixed language shows
an actionable notice and retains CC. A user-selected spoken language overrides
that metadata for routing; it must be the language heard, not the target. Qwen
continues its automatic speech detection even when this routing hint is selected.

## Models and coverage

The current runtime supports the publisher's HY-MT1.5 1.8B and Hy-MT2 1.8B GGUFs
in Q4_K_M, Q6_K and Q8_0. Both are translation-specialized Hunyuan models. Their
catalogues publish mutual translation across 33 languages plus variants; the UI
lists the supported normalized language set. Chinese script variants share the
zh route here; there is no separate Traditional Chinese output selector.

Sources (publisher claims, not our independent multilingual benchmark):

- https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF
- https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF
- https://huggingface.co/Qwen/Qwen3-ASR-0.6B
- https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b

Qwen lists 30 languages plus 22 Chinese dialects. Nemotron exposes 28 base
languages (32 locales) in its transcription-ready and broad-coverage tiers.
NVIDIA's eight adaptation-only locales are excluded; those need fine-tuning.
A translation model supporting Thai does not make the shipped Nemotron model
Thai-capable. The source/target pair is validated again for each finalized line.

Hunyuan model weights use the publisher's community license, separate from this
app and the MIT-licensed llama.cpp runtime. Read the model card before use or
redistribution. The APK contains no model weights.

## Architecture and limits

- `TranslationCatalog`: pinned model identity, source/target sets, publisher
  hashes, URLs and family-specific prompts. No arbitrary GGUF is treated as a
  compatible translation model just because of its file extension.
- `LocalTranslationModels`: app-private atomic imports through `ModelIntegrity`.
- `LocalTranslationSession`: common JNI implementation of `SpeechTextTranslator`;
  owns one native handle, re-verifies its model before opening, and exposes cancel.
- `CaptionTranslationBridge`: independent serial worker, final utterances only,
  three queued lines plus one in flight. Each result is attached by line ID and
  bridge generation. Late results from a stopped/replaced bridge cannot attach.
- Native runtime: pinned llama.cpp, CPU/two threads, 2048-token context,
  384-token output cap, 20-second decode deadline. Context is cleared per line;
  user caption content cannot inject native special tokens into the chat envelope.
- Queue age cap: 20 seconds; caption cap: 2000 characters. Overflow, stale work,
  unsupported languages, load failures, empty or incomplete output are visible
  notices. Original CC continues; source text is never presented as translation.
- Model loading is lazy on the first line that needs translation. Old model cleanup
  finishes before the new one loads. Disabling the bridge unloads it without
  restarting Qwen/Nemotron. Switching ASR still uses the existing lifecycle.
- `libtransiber_translation.so` keeps llama/GGML symbols private and exposes only
  its JNI entrypoints. It does not link Vosk or the ASR plugins. No HTTP server,
  localhost bridge, provider key or cloud call is involved.

To add a different model family, implement its prompt/runtime adapter and validate
its weights and language directions. Do not advertise untested architectures as
compatible. The queue consumes the common `SpeechTextTranslator` interface.

## Rebuild

```sh
bash scripts/translation/build-runtime.sh android
python3 scripts/translation/stage-runtime.py
bash scripts/translation/build-runtime.sh host
build/translation-runtime/host/translation_smoke MODEL.gguf 'Translate the following segment into Arabic, without additional explanation.

The weather is pleasant today.'
```

llama.cpp revision and adapter hashes are recorded in
`common-jni/src/main/assets/licenses/translation/runtime-build.json`. Source
checkouts and weights stay in ignored build directories. Normal APK builds use
the bundled runtime and do not download llama.cpp or weights.
