# VoiceStudio ↔ Hearth: engines, models and live-pipeline research

Reviewed 2026-10-18. VoiceStudio evidence is pinned to
[`d819ff54d93017bf4a5b34b605833162163c9e3c`](https://github.com/debpalash/VoiceStudio/tree/d819ff54d93017bf4a5b34b605833162163c9e3c),
using the actual adapter implementations, catalog and Android client. Hearth
references point to the local implementation. This is a source comparison,
not a latency, accuracy, voice-quality, battery or memory benchmark. A catalog
entry establishes a configured integration, not that its dependencies or
weights are installed, nor that it runs on Android. Upstream benchmark and
language-count headlines are not used as measurements here.

## Assessment

Hearth is already designed around continuous Android capture and live overlay
delivery. VoiceStudio's general ASR interface is oriented toward files and
timestamped segments; its genuine incremental recognition uses a separate
sherpa-onnx path. VoiceStudio offers a much wider desktop TTS registry, while
Hearth has more local translation families and independently configurable
recognition, translation and playback stages. The useful transfer is more
precise capabilities and lifecycle boundaries, not replacement of Hearth's
pipeline with VoiceStudio's file API.

The most concrete additional model candidate is a small **online Zipformer**
for English or Chinese. It must be a new streaming model/profile, with an
Android runtime and pinned assets; neither an arbitrary ONNX import nor a
Parakeet TDT checkpoint becomes incremental merely because it accepts a chunk.

## Start at NDK / common-jni

| Boundary | Hearth | VoiceStudio | Consequence |
| --- | --- | --- | --- |
| Phone inference | `common-jni` is an Android library, NDK `27.0.12077973`, C++17, with JNI in `com.sal7one.common_jni`. The build declares four Android ABIs; a runtime still needs an actual library for the current ABI. | The Android dictation controller records PCM and sends it to a selected backend over OkHttp WebSocket. It calls readiness first, then consumes partial/utterance/summary events. It does not load a local recognition model in this path. | VoiceStudio's Android client is evidence for a remote protocol, not an Android NDK implementation of its desktop engines. |
| Existing STT core | Kotlin `SttEngine` → JNI/router → C++ `IEngine`, with implementations for whisper.cpp and Vosk. The generic `OnnxEngine` refuses initialization because its model-specific inference is unimplemented. | Python `ASRBackend.transcribe(audio_path, ...) -> dict`; output is deliberately untyped Whisper-shaped chunks/segments. | Compare actual adapters; the words “ONNX” and “engine registered” do not establish working recognition. Preserve Hearth's immediate unsupported-runtime errors. |
| New speech families | `SpeechRuntime` verifies a package, validates profile/options and creates an owned `SpeechSession`. JNI passes bounded direct PCM buffers and returns ordered events with `utteranceId`, `revision`, `isFinal`, language and accepted sample count. | General ASR is a blocking file call. Sherpa live handlers bypass that abstraction to create decoder streams and feed waveform frames. | Hearth already has the stronger live contract. Keep the event timeline rather than collapsing it into a text string or dictionary. |
| Runtime isolation | `speech_session.cpp` loads `libhearth_qwen` for Moonshine/Qwen/Omnilingual/Phonon and `libhearth_nemotron` for Nemotron with `dlopen(RTLD_NOW | RTLD_LOCAL)`. It verifies ABI version, struct size, runtime ID, exact source revision and required entry points. Successful runtime libraries remain loaded; each session owns/destroys its instance. | Most runtimes use Python/native dependencies. Some engines run in killable subprocesses; audio.cpp is a parent-managed C++ server; OmniVoice GGUF invokes a desktop executable. | Preserve symbol isolation, pinned versions and handle ownership. Desktop subprocess/binary support supplies no Android ABI or JNI compatibility evidence. |
| Cancellation and lifetime | `SpeechSession.close()` invalidates immediately, then waits safely for the serialized in-flight native call before destroying its handle. Cancellation which loses a native result poisons that session until reset/close. | `run_transcribe_guarded` explicitly acknowledges that a timeout cannot kill an in-process native thread; its capacity remains reserved until exit. A separate faster-whisper sidecar can be killed. | A coroutine timeout alone is not permission to destroy/reuse native memory. Do not add overlapping retry calls against the same model. |
| Audio ownership and backlog | Local speech accepts mono 16 kHz PCM with contiguous sample offsets and bounded per-call sample/event counts. `LiveSpeechProcessor` separates recorder callbacks from inference through a bounded queue. | Android capture is 16 kHz mono PCM16 in 3,200-byte reads and refuses a WebSocket send queue beyond 1 MiB. Server live handlers decode off the asyncio event loop. | Keep explicit backpressure and discontinuity errors; do not allow a recorder callback to run model inference or silently discard samples. |

Hearth sources: [Gradle / NDK](../common-jni/build.gradle.kts),
[native build](../common-jni/src/main/cpp/CMakeLists.txt),
[legacy interface](../common-jni/src/main/java/com/sal7one/common_jni/engine/SttEngine.kt),
[generic ONNX guard](../common-jni/src/main/cpp/onnx/onnx_engine.cpp),
[speech session](../common-jni/src/main/java/com/sal7one/common_jni/speech/SpeechSession.kt),
[native loader and timeline](../common-jni/src/main/cpp/speech/speech_session.cpp),
[ABI](../common-jni/src/main/cpp/speech/backend_abi.h),
[live processor](../common-jni/src/main/java/com/sal7one/common_jni/speech/LiveSpeechProcessor.kt).
VoiceStudio sources: [Android dictation controller](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/mobile/android/features/src/main/kotlin/com/voicestudio/mobile/features/dictate/DictationController.kt#L13-L122),
[ASR contract and timeout ownership](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/asr_backend.py#L150-L247),
[file interface](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/asr_backend.py#L424-L490),
[subprocess ASR](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/subprocess_asr.py),
[audio.cpp wrapper](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/engines/audiocpp/__init__.py),
[GGUF executable wrapper](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/engines/omnivoice_gguf/backend.py).

## What “streaming” actually means

| Execution lane | What happens to incoming audio | Partial/final behavior | File versus overlay fit |
| --- | --- | --- | --- |
| VoiceStudio general ASR | At the common boundary, an adapter receives an audio file path. The legacy capture socket appends received bytes to `audio_chunks`; every configured interval it transcribes a snapshot of the accumulated recording. Defaults are a 2 s interval and 64,000-byte minimum for partials. EOF runs full-buffer transcription. | Partial text replaces the current hypothesis; final is a session summary. “Silence timeout” on this path measures time since received audio, not voice activity within continuously arriving PCM. | Strong fit for file/segment transcription. The socket transports live audio but does not make these model adapters incremental. Re-decoding a growing recording is increasingly expensive. |
| VoiceStudio sherpa offline | Parakeet TDT v2/v3 and Whisper tiny use `OfflineRecognizer`. Re-decode the current utterance approximately every 0.8 s. An RMS gate checks about 0.6 s trailing silence, commits the utterance and removes those samples. | Recomputed partials, utterance finals and an EOF summary. The recovery audio tail is bounded, but the active utterance buffer has no fixed maximum-duration cut in this handler; uninterrupted speech can continue growing it. | Better live behavior than session-wide re-decoding, but still offline-model inference repeated over a window. |
| VoiceStudio sherpa online | Zipformer/Paraformer models use `OnlineRecognizer`. A per-session stream receives each PCM frame; `is_ready` / `decode_stream` drain decoder work and endpoint detection resets the stream after an utterance. The recognizer/model is reused warm. | Partial text as decoder output changes; utterance finals at endpoints; EOF pads/drains trailing audio and returns a summary. | The actual incremental lane. Appropriate research for continuous overlay ASR. |
| Hearth whisper.cpp | Capture is appended to an audio ring buffer. One native worker re-decodes a bounded tail for provisional text and promotes older slices into completed segments. Scratch buffers are reused; partials require new audio and a cadence gate. Separate streaming parameters omit token-timestamp work for partials. | Window-recomputed partials; promoted completed text; final merges already streamed segments with the remaining tail. | An overlay-oriented streaming adapter around an offline model, not a cache-aware acoustic decoder. Also has batch API. |
| Hearth Vosk | A retained recognizer incrementally accepts audio, publishes partial JSON, and commits completed recognizer utterances. | Decoder partials and utterance finals. | Existing incremental low-latency local lane; language is fixed by the installed model. |
| Hearth Moonshine / Qwen3-ASR / Omnilingual CTC / Phonon | Profile declares `UTTERANCE_WINDOWED`, and no partial-results claim. Silence and maximum-window settings bound utterance decoding. | Final text per utterance window. | Useful for captions when a final-at-window cadence is acceptable. A transducer architecture alone does not change this adapter's declared behavior. |
| Hearth Nemotron 3.5 | Profile declares `CACHE_AWARE` and partial results. Right-context frames and endpoint/window controls are explicit. | Incremental partials plus utterance finals. | Existing multilingual incremental lane. Right context is model lookahead, not total end-to-end caption latency. |
| Hearth cloud | `RemoteWhisperEngine` uses utterance requests; `StreamingCloudEngine` delegates Soniox, ElevenLabs Scribe, Deepgram, OpenAI and AssemblyAI streaming protocols. These are separate execution paths. | Batch/windowed provider results versus provider partial/final stream events. | Preserve their distinct semantics, provider errors, session generations and final-text ownership. VoiceStudio's reviewed ASR registry has one file-oriented remote OpenAI-compatible adapter, rather than equivalent provider-specific live adapters. |

VoiceStudio evidence: [general cadence](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/api/routers/capture_ws.py#L72-L85),
[growing-buffer implementation](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/api/routers/capture_ws.py#L397-L505),
[sherpa online](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/api/routers/capture_ws.py#L830-L975),
[sherpa offline](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/api/routers/capture_ws.py#L977-L1163).
Hearth evidence: [Whisper worker](../common-jni/src/main/cpp/whisper/whisper_engine.cpp),
[Vosk recognizer](../common-jni/src/main/cpp/vosk/vosk_engine.cpp),
[profile capabilities](../common-jni/src/main/java/com/sal7one/common_jni/speech/SpeechModels.kt),
[speech backend windowing](../common-jni/src/main/cpp/speech/backends/qwen_backend.cpp),
[Nemotron backend](../common-jni/src/main/cpp/speech/backends/nemotron_backend.cpp),
[cloud batch](../app/src/main/java/com/sal7one/transiber/byok/RemoteWhisperEngine.kt),
[cloud stream](../app/src/main/java/com/sal7one/transiber/byok/StreamingCloudEngine.kt),
[cloud mode catalog](../app/src/main/java/com/sal7one/transiber/byok/CloudConfigStore.kt).

## ASR families and checkpoints: one-to-one

VoiceStudio's YAML catalog contains the following specific ASR artifacts.
Runtime adapters also accept configured repositories; that does not make every
repository a curated, tested checkpoint. Catalog size fields are download
estimates and are not peak inference memory. The table uses the runtime path
to classify behavior rather than copying catalog speed descriptions.

| Family / runtime | VoiceStudio exact curated checkpoint(s) | Hearth counterpart and material difference |
| --- | --- | --- |
| Whisper / CTranslate2 | `Systran/faster-whisper-large-v3`; `deepdml/faster-whisper-large-v3-turbo-ct2`; `Systran/faster-distil-whisper-large-v3`; `Systran/faster-whisper-medium`; `Systran/faster-whisper-small`; `Systran/faster-whisper-base`. Shared by `faster-whisper`, `faster-whisper-isolated` and `whisperx`. | Hearth uses whisper.cpp GGML `.bin` imports, not CTranslate2 directories. Tiny/base and other valid whisper.cpp checkpoints are an import choice; there is no equivalent six-entry curated download matrix. English-only `.en` and multilingual model capabilities must remain distinct. |
| Whisper / WhisperX | Uses the same catalog artifacts as above, with optional wav2vec2 forced alignment after transcription. Alignment failure falls back to raw timestamps. | Hearth's Whisper timestamps are decoder/token evidence, not a separately aligned word timeline. Live partials deliberately avoid token-timestamp work. Forced alignment could improve file timestamps but would add model/runtime cost to an overlay; no integration is justified by this review. |
| Whisper / MLX | `mlx-community/whisper-large-v3-mlx`; `mlx-community/whisper-large-v3-turbo`; `mlx-community/whisper-tiny-mlx`. Adapter runs `mlx_whisper.transcribe` on a file. | No Hearth MLX runtime; these are Apple Silicon artifacts. Shared model family does not make the weight format Android-compatible. |
| Whisper / PyTorch | Catalog: `openai/whisper-large-v3`. Adapter default separately names `openai/whisper-large-v3-turbo` via `OMNIVOICE_PYTORCH_ASR_MODEL`. | No Hearth PyTorch/Transformers ASR runtime. Catalog entry and actual configured/default checkpoint must be shown separately. Whisper translation capability is checkpoint-dependent; “Whisper engine” does not guarantee English translation for turbo. |
| Parakeet TDT / NeMo | `nvidia/parakeet-tdt-0.6b-v3`; `nvidia/parakeet-tdt-0.6b-v2`. File `model.transcribe([audio_path], timestamps=...)`. The code permits CUDA/CPU, although the YAML lists CUDA; install hints report a Transformers dependency conflict and no safe shared-environment install path. | Hearth Phonon is a different checkpoint converted into a sherpa offline transducer package. Nemotron is a different streaming family. Do not present either as the same model. No direct NeMo Python dependency belongs in the Android app. |
| Parakeet TDT / MLX | `mlx-community/parakeet-tdt-0.6b-v3`. File transcribe on Apple Silicon. | No matching MLX runtime. Neither “TDT” nor a fast file result establishes cache-aware overlay decoding. |
| Moonshine | `UsefulSensors/moonshine-base`; `UsefulSensors/moonshine-tiny`. Adapter invokes `moonshine_onnx.transcribe(audio_path, model=...)` or `moonshine_voice.Transcriber.transcribe_file`. Basic output has plain text and coarse full-file bounds, no word timestamps. | Hearth has pinned sherpa Moonshine tiny/base English v2 packages from 2026-02-27, using `UTTERANCE_WINDOWED`. These are explicit model/runtime identities, not a port of a newer Moonshine streaming API. VoiceStudio's adapter docstring's latency claim is not a measurement of either Hearth model. |
| Parakeet TDT / sherpa offline | `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8`; `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8`. Encoder/decoder/joiner/tokens roles; `OfflineRecognizer`. | Phonon shares an offline transducer execution pattern, not these weights. These are additional file/window models, not the most direct way to improve streaming overlay latency. |
| Zipformer / sherpa online | `csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20`; `csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`; `csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23`. INT8 encoder/decoder/joiner/tokens; `OnlineRecognizer`. | Not currently in Hearth's speech profile catalog. The small English/Chinese variants are a concrete incremental-ASR experiment with narrower language coverage than multilingual Nemotron. Need a new model-specific native adapter, pinned artifacts and owner measurements. |
| Paraformer / sherpa online | `csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en`. INT8 encoder/decoder/tokens; `OnlineRecognizer`. | Not currently integrated. Another true-streaming zh/en candidate; do not treat the offline Paraformer or generic ONNX format as interchangeable. |
| Whisper / sherpa offline | `csukuangfj/sherpa-onnx-whisper-tiny`, default dictation model. INT8 encoder/decoder/tokens; `OfflineRecognizer`. | Hearth already has whisper.cpp; this adds another Whisper runtime rather than a new incremental architecture. Compare measured memory/latency before carrying duplicate runtime and model costs. |
| SenseVoice / FunASR | Adapter exists with default `iic/SenseVoiceSmall` and FSMN-VAD; it is not an ASR entry in the reviewed YAML catalog. `generate` receives a file and returns normalized segments. | No direct counterpart. Not evidence that Hearth is missing a drop-in streaming model. Language/emotion/event tokens require a model-aware adapter. |
| Remote OpenAI-compatible | `openai-compat-asr` sends a file to a configured transcription server. A self-hosted Qwen3-ASR/FunASR/SenseVoice server is a possible backend; this is not a direct local Qwen integration. | Hearth already supports OpenAI-compatible batch speech and several provider-specific live protocols. It also has local Qwen3-ASR 0.6B INT8 and a 1.7B custom-package profile; the latter has no verified direct publisher download and phone performance is unverified. |
| Vosk | No Vosk adapter in the reviewed VoiceStudio ASR registry. | Hearth already has an incremental Vosk lane and imported language-specific models. Keep its model-fixed language contract; no invented auto-detection metadata. |
| Omnilingual CTC | No counterpart in VoiceStudio's registry/catalog. | Hearth's 300M v2 INT8 profile is experimental and utterance-windowed. Current adapter coverage explicitly offers en/ar/ru/zh with no language-forcing input; wider publisher coverage is not asserted by the app contract. |
| Phonon 2 | No counterpart in VoiceStudio's registry/catalog. | Hearth's English INT8 offline transducer package is a specific conversion. Raw `.bps` weights are not directly importable. |
| Nemotron 3.5 ASR | No counterpart in VoiceStudio's registry/catalog. | Hearth has a pinned 0.6B Q8 GGUF package and a separate cache-aware runtime; partial results, supported source languages and right context are explicit. Device speed remains unverified by this research. |

VoiceStudio sources: [complete checkpoint catalog](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/config/models.yaml#L62-L264),
[runtime registry and install limitations](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/asr_backend.py#L2715-L2765),
[checkpoint translation check](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/asr_backend.py#L398-L422),
[WhisperX alignment](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/asr_backend.py#L1033-L1112),
[NeMo/MLX/Moonshine adapters](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/asr_backend.py#L1708-L2041),
[sherpa exact roles and online/offline tags](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/sherpa_dictation.py#L80-L236),
[recognizer construction](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/sherpa_dictation.py#L404-L483),
[FunASR and remote adapters](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/asr_backend.py#L2259-L2675).
Hearth sources: [speech profiles](../common-jni/src/main/java/com/sal7one/common_jni/speech/SpeechModels.kt),
[publisher sources and import distinctions](../app/src/main/java/com/sal7one/transiber/models/ModelSources.kt),
[model package verification](../common-jni/src/main/java/com/sal7one/common_jni/speech/SpeechModelPackage.kt),
[language/model detection](../app/src/main/java/com/sal7one/transiber/caption/CaptionModelLanguages.kt),
[overlay engine choices](../app/src/main/java/com/sal7one/transiber/caption/CaptionOverlayConfig.kt).

## TTS: wider registry does not mean native streaming

VoiceStudio's base TTS contract returns a complete mono tensor from
`generate(text, **kwargs)`, plus output sample rate and language/capability
metadata. Its WebSocket stream splits text into sentences, fully generates
each sentence, then sends that sentence's PCM in delivery chunks before
generating the next sentence. That improves time to the first sentence versus
a full-document render; it does not establish incremental audio-token output
from every registered engine. Hearth's read-aloud player likewise synthesizes
bounded text parts sequentially and plays their PCM; Android system TTS is
managed by the platform. Neither should advertise per-token streaming from
transport chunking alone.

| VoiceStudio family / registered engine | Actual configured artifacts / runtime distinction | Hearth comparison |
| --- | --- | --- |
| OmniVoice: `omnivoice`, `omnivoice-subprocess` | `k2-fsa/OmniVoice`; PyTorch model, with optional process isolation of the same engine. | No local OmniVoice integration. A future self-hosted voice must expose Hearth's explicit capability/speech protocol; a model family name is insufficient. |
| OmniVoice GGUF: `omnivoice-gguf` | Separate `Serveurperso/OmniVoice-GGUF` quant map and `omnivoice-tts` desktop binary, with hardware probing. Not the same runtime as Python OmniVoice. | No Android build/integration established. Do not import the desktop binary or confuse it with Hearth's unrelated translation/Nemotron GGUF runtimes. |
| Supertonic: `supertonic3` | `Supertone/supertonic-3`, explicit revision, CPU ONNX in a sidecar. | Closest direct match. Hearth already owns native Supertonic 3, pinned core/style files and explicit languages. Its production API emits 44,100 Hz mono PCM. Model warmth can be studied, but retaining weights beside STT/MT needs a measured phone memory budget. |
| KittenTTS: `kittentts` | `KittenML/kitten-tts-mini-0.8`; fixed English preset voices, no cloning. | A potential smaller English option, not an Arabic-capable replacement for Supertonic. No Hearth Android adapter is established by the Python wheel. |
| PocketTTS: `pockettts` | CPU, per-language models for en/fr/de/pt/it/es, reference-based cloning, subprocess. | Possible self-hosted option. No Arabic coverage in this adapter and no proven Android port. |
| VoxCPM2: `voxcpm2` | `openbmb/VoxCPM2`, reference cloning and text voice design. Effective backend selection uses a subprocess wrapper when its dedicated environment exists, otherwise the in-process adapter. | Additional desktop/self-hosted capabilities. Do not silently widen Hearth's local custom-voice promises. |
| MOSS: `moss-tts-nano`, `moss-tts-v15` | Nano catalog artifact `OpenMOSS-Team/MOSS-TTS-Nano-100M`; v1.5 is a separate large opt-in model/runtime in its own environment. | Two distinct models, not a single lightweight drop-in. No Android support established. |
| CosyVoice: `cosyvoice` | `FunAudioLLM/Fun-CosyVoice3-0.5B-2512`; selects its dedicated-environment subprocess when installed, otherwise the in-process adapter. | No local counterpart. Remote engine experiment only until Android runtime/model support is demonstrated. |
| GPT-SoVITS: `gpt-sovits` | `lj1995/GPT-SoVITS` catalog; actual adapter talks to an external `api_v2.py` service and explicitly requests non-streaming generation. | Existing Hearth self-hosted architecture can support an adapter server without embedding its full desktop dependency stack. No universal wire compatibility is implied. |
| MLX-Audio: `mlx-audio` | Seven curated choices: `mlx-community/Kokoro-82M-bf16`, `csm-1b-8bit`, `Qwen3-TTS-12Hz-1.7B-VoiceDesign-4bit`, `Dia-1.6B`, `Chatterbox-TTS-4bit`, `MeloTTS-English-v3-MLX`, `Llama-OuteTTS-1.0-1B-4bit`. Capabilities/languages depend on that choice; custom repository selection also exists. | Apple Silicon runtime, not Android. Hearth's legacy TTS enum contains `KOKORO`, but an enum/factory slot does not establish a shipped Kokoro model or production voice route. |
| sherpa-onnx: `sherpa-onnx` | Although the label names many upstream engines, the reviewed implementation specifically constructs `OfflineTtsVitsModelConfig(model.onnx, tokens.txt)` with a speaker ID. | Do not infer broad upstream sherpa TTS support from this adapter. Hearth's legacy native TTS fleet has guarded backend flags; production shared read-aloud uses the separate Supertonic/system/self-hosted routes. |
| Other opt-in registrations | `indextts2` uses the IndexTTS 2.5 integration; `dots-tts`, `confucius4-tts` have separate environments/checkpoints; `audiocpp` serves Breeze-TTS-2 via a native desktop server. They are registry entries beyond the base YAML TTS list. | Valid research candidates for a user-owned server, with exact per-model language/voice capabilities. No Android readiness or performance conclusion follows from registration. |

Hearth also has independent cloud voice configuration for OpenAI, OpenRouter,
Gemini, ElevenLabs and custom OpenAI-compatible services. Those credentials,
models and capabilities are separate from STT configuration; a native engine
refactor must preserve that choice and the self-hosted voice protocol.
See [cloud voice configuration](../app/src/main/java/com/sal7one/transiber/byok/CloudVoiceConfig.kt).

VoiceStudio sources: [base contract](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/tts_backend.py#L215-L566),
[registry and lazy engines](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/tts_backend.py#L3295-L3415),
[effective subprocess selection](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/tts_backend.py#L3765-L3805),
[curated MLX models and language guards](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/tts_backend.py#L2526-L2597),
[VITS-specific sherpa implementation](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/tts_backend.py#L3231-L3288),
[sentence render](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/api/routers/tts_stream.py#L174-L225),
[WebSocket delivery loop](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/api/routers/tts_stream.py#L369-L455),
[PocketTTS](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/engines/pockettts/__init__.py),
[Supertonic pins](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/engines/supertonic3/constants.py),
[TTS checkpoint catalog](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/config/models.yaml#L299-L396).
Hearth sources: [shared player](../app/src/main/java/com/sal7one/transiber/voice/VoicePlayer.kt),
[pinned voice catalog](../app/src/main/java/com/sal7one/transiber/voice/VoiceCatalog.kt),
[native Supertonic ownership](../hearth-tts-supertonic/src/main/java/com/sal7one/common_jni/voice/SupertonicVoice.kt),
[remote protocol](../app/src/main/java/com/sal7one/transiber/voice/RemoteVoiceProtocol.kt),
[legacy TTS build gates](../common-jni/src/main/cpp/CMakeLists.txt).

## Translation: preserve independently selectable stages

| Capability | VoiceStudio | Hearth |
| --- | --- | --- |
| Small offline MT | Argos with explicitly installed directed language packs, running through CTranslate2. | Marian / OPUS-MT ONNX packages: en→ar, ru→en, zh→en, with explicit ru→en→ar and zh→en→ar cascades. ML Kit is a separate platform route where available. |
| Multilingual local model | Catalog explicitly includes `facebook/nllb-200-distilled-600M`; weights must be installed before selection. | Pinned GGUF catalog: TranslateGemma 4B Q4_K_M; HY-MT1.5 1.8B Q4_K_M/Q6_K/Q8_0; Hy-MT2 1.8B Q2_K/Q3_K_M/Q4_K_M/Q6_K/Q8_0; experimental MiLMMT-46 1B Q4_K_M/Q5_K_M. Model-specific prompts, exact identities and directed pair validation are part of the contract. |
| Direct translation services | Registry: Google web endpoint, MyMemory, DeepL, Microsoft, Google Cloud, Amazon, plus OpenAI-compatible LLM. Some are optional dependencies, not installed services. | Discovered capabilities and explicit directed-pair validation for Google Cloud, Azure, DeepL and LibreTranslate, plus the existing speech/translation provider paths. Self-hosted translation remains a separate choice. |
| Recognition-integrated translation | Whisper task `translate` is gated by actual checkpoint; other ASR families default to no translation. | Preserve Whisper's available English translation and provider-integrated live translation. For ordinary local ASR, source-language text feeds a selected independent translator. A selected target must never be passed as the speech source language. |
| Reuse across experiences | Translation registry is primarily consumed by the desktop translation/dubbing routes. | Shared translator selection serves conversation, face-to-face and typed text; captions can select their text translator independently. Shared voice settings then choose system/local/self-hosted playback. |

VoiceStudio sources: [translation registry](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/translation_engines.py#L51-L193),
[translator implementation](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/translator.py),
[translation APIs](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/translation_apis.py).
Hearth sources: [translation model catalog](../common-jni/src/main/java/com/sal7one/common_jni/translation/TranslationCatalog.kt),
[shared options](../app/src/main/java/com/sal7one/transiber/translation/TranslationOptions.kt),
[Marian cascade](../app/src/main/java/com/sal7one/transiber/translation/MarianCascade.kt),
[cloud protocol and language graph](../app/src/main/java/com/sal7one/transiber/translation/CloudTranslationProtocol.kt),
[shared translation hub](../app/src/main/java/com/sal7one/transiber/translation/TranslationHub.kt),
[caption translator selection](../app/src/main/java/com/sal7one/transiber/translation/CaptionTranslatorChoice.kt).

## Improvements justified by this research

1. **Describe execution separately from API shape.** “Accepts chunks,” “returns
   partials,” “retains decoder state,” “supports batch files” and “supports word
   alignment” are separate facts. Preserve Hearth's existing windowed versus
   cache-aware profiles and make any shared adapter expose those facts without
   changing persisted engine/provider choices.
2. **Use an owned session boundary for common live operations.** Normalize
   accept/drain/finish/reset/close and final events at the caption boundary,
   while keeping legacy native, speech-plugin and cloud implementations behind
   their adapters. Do not require translation or TTS to become a property of
   a particular STT engine. Keep generation checks, exact-once finals and
   independent translation/voice routing intact.
3. **Separate availability, installed weights and load readiness.** VoiceStudio
   explicitly preloads to surface a real native/dependency failure before
   processing many segments. Hearth should keep truthful ABI/package errors
   and avoid treating a registered model, successful download or filename as
   a ready recognizer. No silent fallback to another engine/language.
4. **Preserve bounded hot paths.** Retain Hearth's recorder/inference queue
   bound, sample-continuity checks and bounded utterance windows. Do not copy
   the general VoiceStudio growing-recording re-decode loop. A no-pause live
   session must have bounded inference work and memory.
5. **Evaluate a small online model with evidence.** English Zipformer 20M and
   Chinese Zipformer 14M are narrow, concrete candidates. A useful experiment
   records first-partial and final latency, real-time factor, maximum queue
   depth, dropped/discontinuous audio, memory and accuracy on identical owner
   recordings. No model/default change until the Android adapter, pinned
   package and owner-device results exist.
6. **Separate cold load from warm inference measurements.** Consider warm
   model reuse only with exclusive session ownership and explicit eviction;
   retaining simultaneous ASR, translator and TTS models may exceed phone
   memory. Desktop GPU/VRAM routing rules cannot determine Android settings.
7. **Keep errors as errors.** Do not port VoiceStudio handlers that log a
   partial/flush failure and continue with empty text. In particular, its
   sherpa EOF flush catches can replace an exception with `""`; Hearth's
   contract requires an actual provider/runtime failure to remain visible.
   Text-equality suppression at EOF is also not a substitute for utterance
   identity: a person can intentionally say the same sentence twice.

Relevant upstream lifecycle evidence: [model load boundary](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/asr_backend.py#L469-L490),
[separate TTS load and generation budgets](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/tts_backend.py#L279-L300),
[sherpa warm recognizer and EOF behavior](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/api/routers/capture_ws.py#L842-L935).

No upstream code, models, binaries or dependencies were imported for this
comparison. No device control or provider inference was used. Media editing,
FFmpeg, video dubbing, sign recognition, books and yt-dlp remain outside
Hearth's scope. New network routes and download delegation must retain the
`ByokPolicy.FEATURE_BYOK` FOSS gate; inference keys never belong on model hosts.
