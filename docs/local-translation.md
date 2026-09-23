# Optional local translation bridge

Qwen3-ASR, Nemotron and Moonshine produce original-language speech transcripts. Translation
is a separate text model; it never changes the ASR model's language capabilities.

## Use

1. Import/select Qwen or Nemotron in Models. Expand **CC language support** to
   see the speech model's supported languages.
2. Under **Local translation bridge**, choose ML Kit language packs (play only),
   a small Marian language pair, Hy-MT2, HY-MT1.5, or TranslateGemma. Marian
   offers English→Arabic, Russian→English, and Chinese→English only. It does
   not claim Russian/Chinese→Arabic or arbitrary language pairs. Hy-MT2 offers Q2_K (about
   741 MiB), Q3_K_M (about 907 MiB), Q4_K_M (1080 MiB), Q6_K (1406 MiB), and
   Q8_0 (1820 MiB). Smaller quants save storage; they are not presumed to improve
   speed or translation quality. TranslateGemma Q4_K_M is 2.49 GB.
   Weights are separate from the APK. Total RAM also includes both inference
   contexts and speech weights.
3. Download using the model button (network-enabled build). Catalogued
   downloads verify and install automatically; the Marian button fetches its
   four pinned publisher files in one action. New originals go to
   `Downloads/Hearth/models` on Android 10 and later.
   Existing app-stored downloads also install directly; no export is needed.
   Alternatively import a publisher GGUF with the matching model selected. Exact
   pinned SHA-256 and size are required. The offline build only imports local files.
   Android 9 retains app storage without requesting broad storage permission.
4. Choose a target supported by the selected translator, then enable the bridge.
   CC appears immediately; translation attaches to its original line later.
   The bubble settings can disable/re-enable translation while ASR keeps running.
5. To compare another model, import/select it. The old translator is cancelled
   and released before the next one loads. The bubble separates preparation,
   queue and inference time for each completed translation. These are not
   audio-to-display latency or an accuracy score.

Automatic routing uses the language reported by ASR. Unknown/mixed language shows
an actionable notice and retains CC. A user-selected spoken language overrides
that metadata for routing; it must be the language heard, not the target. Qwen and Nemotron also forward the selected supported language to their native
recognizers, bypassing automatic language selection. Moonshine fixes English.
Scribe and Deepgram can use the same bridge; choose the spoken language explicitly
when the cloud callback has no detected-language metadata. OpenAI and Soniox
integrated translation take priority over a remembered local bridge preference.

## Models and coverage

The current runtime also supports three pinned Marian ONNX language pairs,
each about 235–242 MiB on disk. See the [phone smoke](marian-phone-smoke-2026-09-23.md)
for the measured short-pass speed and a meaning-changing English→Arabic error.
Do not choose a pair based on speed alone.

The GGUF runtime supports the publisher's HY-MT1.5 1.8B GGUFs in Q4_K_M,
Q6_K and Q8_0, and Hy-MT2 1.8B in Q2_K, Q3_K_M, Q4_K_M, Q6_K and Q8_0. The
Q2/Q3 files are ordinary GGUF conversions pinned by exact revision and hash;
they use the same supported Hunyuan adapter. Both families are translation-specialized models. Their
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

HY-MT1.5 weights use Tencent Hunyuan community terms; Hy-MT2 uses Apache-2.0.
TranslateGemma uses Gemma terms. These are separate from this app and the
MIT-licensed llama.cpp runtime. Read the model card before use or
redistribution. The APK contains no model weights.

## Architecture and limits

- `TranslationCatalog`: pinned model identity, source/target sets, publisher
  hashes, URLs and family-specific prompts. No arbitrary GGUF is treated as a
  compatible translation model just because of its file extension.
- `LocalTranslationModels`: app-private atomic imports through `ModelIntegrity`.
- `LocalTranslationSession`: common JNI implementation of `SpeechTextTranslator`;
  owns one native handle, re-verifies its model before opening, and exposes cancel.
- `MarianTranslationSession`: common JNI adapter for a verified ONNX pair;
  advertises exactly one source→target direction. The legacy English→Arabic
  Whisper fallback selects only the pinned English→Arabic package, never an
  arbitrary installed Russian or Chinese model.
- `CaptionTranslationBridge`: independent serial worker, final utterances only,
  three queued lines plus one in flight. Each result is attached by line ID and
  bridge generation. Late results from a stopped/replaced bridge cannot attach.
- Native runtime: pinned llama.cpp, CPU/two threads, 2048-token context,
  384-token output cap, 20-second decode deadline. Context is cleared per line;
  user caption content cannot inject native special tokens into the chat envelope.
- Queue age cap: 20 seconds after preparation; caption cap: 2000 characters. Overflow, stale work,
  unsupported languages, load failures, empty or incomplete output are visible
  notices. Original CC continues; source text is never presented as translation.
- Model preparation starts with the bridge, before the first final caption. Startup
  loading time does not consume the first queued caption’s age budget. Old cleanup
  finishes before the new model loads. Disabling the bridge unloads it without
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

For six aligned reference samples with timings and automatic WER/CER/chrF++
scores, see [the macOS/phone benchmark instructions](local-benchmark.md#run-speech-and-translation-samples-on-macos).

llama.cpp revision and adapter hashes are recorded in
`common-jni/src/main/assets/licenses/translation/runtime-build.json`. Source
checkouts and weights stay in ignored build directories. Normal APK builds use
the bundled runtime and do not download llama.cpp or weights.

## ML Kit language packs (play only)

Expand **ML Kit**, select it, and download the spoken and target packs on Wi-Fi.
English needs no separate pack. Download/remove actions are explicit; inference
never starts a download. Missing packs report which languages are required while
original captions continue. Packs stay in ML Kit-managed app storage, not the
public GGUF download folder. The foss build excludes this SDK and option.

Translation uses Google Translate on-device models. Non-English pairs pivot through
English; this is the lightweight option, not a promise of specialist-model quality.
See [Google’s translation guide](https://developers.google.com/ml-kit/language/translation/android)
and [privacy disclosure](../PRIVACY.md).

## TranslateGemma and smaller HY artifacts

The catalog pins `mradermacher/translategemma-4b-it-GGUF` at
`35a7486e128b19642cdc72d7b91b21ba388aaf42`, Q4_K_M. The adapter accepts `gemma3`
explicitly and uses the conversion’s embedded TranslateGemma language instructions
inside Gemma turn tokens. The initial adapter exposes 35 normalized language codes;
this is not a tested quality matrix for every pair. This is a larger optional model.

Existing HY-MT1.5 and Hy-MT2 Q4/Q6/Q8 artifacts remain supported. The separately
published HY-MT1.5 2-bit SEQ artifact was tested and rejected by our pinned runtime
(tensor offsets are incompatible). Its publisher says the required llama.cpp
kernel is forthcoming. It is deliberately absent from the download choices;
that failure says nothing about the existing working HY quantizations.

Hy-MT2's separate [1.25-bit artifact](https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF)
is a different format from that older HY-MT1.5 SEQ package. Its publisher requires
the STQ kernel in [llama.cpp PR #22836](https://github.com/ggml-org/llama.cpp/pull/22836),
which remained open and unmerged when checked on 2026-09-13. Hearth's pinned
runtime does not include that kernel. The advertised 440 MB footprint is a
publisher claim for this special artifact, not the size of our Q4 model or a
measured Android performance result. It is an integration candidate, not an
available download option in Hearth.

Hy-MT2 Q2_K and Q3_K_M are catalogued from the pinned
[community conversion revision](https://huggingface.co/mradermacher/Hy-MT2-1.8B-GGUF/tree/d760e9708bbded7cee9aa135b4f1dedb42ed2fc4).
Their hashes and lengths were verified against the model repository. Smaller
artifacts may trade translation quality for storage; use the in-app quick
reference set and review each output before choosing one for live captions.

Nemotron now requests an endpoint after four seconds of decoded speech with a
nonempty transcript, in addition to natural endpoints. This gives the final-only
translator bounded phrases during continuous audio. It does not guarantee four
seconds of wall-clock latency under load. Original captions remain visible when
translation is unsupported, slow or fails.
