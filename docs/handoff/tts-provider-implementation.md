# Hearth TTS provider implementation handoff

Read `AGENTS.md` first. The embedded Arabic/English article SDK and all five shortlisted new TTS families now have real native adapters. **Magpie, OmniVoice, MOSS Nano, Chatterbox Multilingual V3 and OuteTTS1.0 are experimental CPU ARM64 providers.** Supertonic3 remains the existing provider with verified male/female style IDs. Functionality receipts do not establish Arabic intelligibility, phone latency or sustained thermal behavior.

The Hearth app blocks every downloaded local model on TVs. Android system TTS remains a separate explicit choice. The reusable SDK does not impose Hearth's TV policy on other applications. No owner-device commands were used. No model downloads run from Gradle or from SDK initialization.

## Current modules and production consumers

| Module / source | Responsibility | Binary dependencies |
|---|---|---|
| `:hearth-tts` | `SpeechEngine`, `SpeechVoice`, preferences, article splitting, bounded `ArticleReader` flow and lifecycle | Coroutines; no model, JNI, Android player, network or app settings |
| `:hearth-onnx-runtime` | One official ONNX Runtime1.20.0 DSO per app ABI and notices | Shared by app speech, Supertonic, MOSS and Chatterbox; no second ORT SONAME |
| `:hearth-tts-supertonic` | Standalone Supertonic implementation, catalog, normalization and JNI | API +shared ORT; **no broad common-jni AAR dependency** |
| `:hearth-tts-magpie` | Correct v2607 F16 stack, tokenizer extraction, NeMo native runtime | API; private GGML, isolated CPU ARM64 DSO |
| `:hearth-tts-omnivoice` | Diffusion generation and Higgs codec | API; private GGML, isolated CPU ARM64 DSO |
| `:hearth-tts-moss` | SentencePiece, global/local generation, incremental codec | API +shared ORT; private SentencePiece |
| `:hearth-tts-chatterbox` | V3 NFKD tokenizer, cached Q4 LM, waveform decoder, required Perth | API +shared ORT; private real HF tokenizers0.22.2 |
| `:hearth-tts-oute` | Oute1.0 prompt, combined ChatLLM generator/DAC runtime | API; private GGML, isolated CPU ARM64 DSO |
| `app/.../voice/LocalVoiceFamilies.kt` | Executable descriptors: inventory, languages, readiness, integrity, runtime construction | App policy only; stages remain independent |
| `app/.../voice/VoicePlayer.kt` | Production `ArticleReader` consumer with one continuous `AudioTrack` | Native, Android and remote transports remain distinct |
| `VoiceCatalog`, `VoiceModels`, `VoiceSetup` | Pinned downloads/imports, derived components, explicit selection | Generic verified sink; DataStore preferences and original SAF source binding |

The native package remains `com.sal7one.common_jni`. Supertonic sources moved to `hearth-tts-supertonic/src/main/{cpp/voice,java/com/sal7one/common_jni/voice}`. Its four JNI symbols live only in `libhearth_supertonic_jni.so`; common-jni no longer compiles a duplicate voice implementation. Shared ONNX binaries/notices moved to `:hearth-onnx-runtime` with unchanged vendor hashes. These modules contain no network permission or model weights.

## Model packages and honest inventory

Exact component URLs, revisions, sizes, SHA256s, runtime status and license sources are in [models/tts.json](models/tts.json), rendered in [models/README.md](models/README.md). Source archive size and final installed size differ where conversion/extraction occurs.

| Family | Required downloaded bytes | Final installed model bytes | Voices exposed by Hearth |
|---|---:|---:|---|
| Supertonic3 | 398,361,202 core +selected style (~291KB) | Same | F1–F5 /M1–M5, verified publisher-coded gender |
| Magpie v2607 | 678,563,360 | 668,084,575 | Aria, Jason, John, Leo, Sofia; gender UNKNOWN |
| OmniVoice | 945,284,608 | Same | `auto`, `design-female`, `design-male`; designs are not fixed identities or verified gender |
| MOSS Nano | 717,868,602 | Same |18 pinned references, all UNKNOWN gender; default Ava |
| Chatterbox V3 |1,593,714,936 |1,593,672,348 | One pinned `default` reference, UNKNOWN gender |
| OuteTTS1.0 |1,438,441,535 |Same | `EN-FEMALE-1-NEUTRAL`; English source profile, UNKNOWN for strict cross-lingual gender matching |

Supertonic is the current choice for a strict male/female article API. Native Arabic speech with an English reference does not establish a native Arabic accent. No adapter infers speaker gender from a name or generated pitch. Omni/Oute cannot be unrestricted commercial SDK defaults: the former has noncommercial generator and Boson/Meta codec terms; the latter has CC-BY-NC-SA +Llama3.2 terms. Keep runtime, weights, codec and reference licenses separate.

## Public API, backpressure and ownership

Use the API example in [../tts-sdk.md](../tts-sdk.md). `SpeechEngine.synthesize` is a blocking worker contract. Each frame is finite nonempty mono float PCM, carries its actual sample rate, and contains no more than one second of samples. Engines own their input array until `emit` returns; `ArticleReader` copies at its emission boundary before enqueueing, so a producer can reuse its native buffer safely.

`ArticleReader.read` is a cold flow, emits one `VoiceSelected`, then ordered `Audio` parts. Completion is the article's final signal. Failures/cancellation propagate; no empty audio or synthetic success substitutes for a failure. One article can run per reader. The owning `Channel(capacity=1)` bounds backpressure; cancellation **cancels that channel before waiting for native completion**, otherwise a producer blocked on a full channel can deadlock teardown. Tests cover cancellation/close with a slow consumer and original-buffer mutation.

`stop()` cancels the active flow and requests engine cancellation. `closeAndJoin()` waits for actual worker destruction. The app holds its native reservation until this completes, preventing overlap with a replacement model. Do not block the UI thread awaiting native cleanup. A model's initialization may delay cancellation before its handle is published. Cancelled or terminally failed native sessions are recreated before reuse.

JNI uses monotonic lifetime-bearing handle leases. Retire a handle, signal cancellation, then obtain exclusive ownership before destroying it. A callback calling close on its own inference thread must retire/signal and return instead of waiting for itself. PCM callbacks install their active-handle identity even when invoked on an upstream foreign thread. Same-thread and foreign-thread callback-close tests protect this. Java callback exceptions retain their exact identity; malformed PCM, thread attachment and genuine provider failures remain errors, not cancellation.

## Voice, language and preference contracts

`SpeechVoice.gender` is UNKNOWN/FEMALE/MALE. `VoicePreference` defaults to `REQUIRE_MATCH`; strict unsupported gender/language fails. `ALLOW_SAME_LANGUAGE` is explicit and returns a reason, never authorizing Arabic→English substitution or local→cloud fallback.

`ArticleText` splits up to200,000 UTF16 characters at sentence/word boundaries, preserves original non-whitespace code points, and never cuts a surrogate pair. It is not HTML extraction, Arabic diacritization, language detection or grapheme segmentation. Callers choose a language per article/segment. Each family owns its own normalization and tokenizer; never feed Supertonic IDs to another engine.

`VoiceChoice.family` and per-family selected voices persist through DataStore and portable setup JSON. The historical `backend="supertonic"` transport means downloaded native speech; the separate family chooses its implementation. Old setup JSON defaults to Supertonic without clearing remembered choices. Readiness checks TV policy, running-process ABI, complete files, actual selected inventory and requested gender. A phone capable of64bits may still run a32bit APK. Import, queue transfer, retry, install, portable apply, selection and playback retain policy guards.

Choosing/installing an engine does not enable read aloud, STT, translation or overlay. “Use selected voice for captions” explicitly changes only the shared voice selection. The package queue groups required files and shows real transfer/retry/install state. File/import details are secondary. Basic mode places additional experimental engines under More engines; Advanced exposes source links/checksums/tuning.

## Task TTS-1: standalone Supertonic extraction — implemented

The adapter depends only on API +shared ORT. Existing `SupertonicVoice`, `VoiceText`, native shape bounds, style validation, cancellation and JNI names were preserved. `scripts/stage-vendor-runtimes.py`, CI, checksum roots, CMake and release verification use the new ORT location. Host normalization/style bounds and app/core tests cover this extraction. No small-size claim hides the real ORT footprint. Publishing needs measured final AARs and consumer dependency metadata.

## Task TTS-2: Magpie CPU — implemented; device/GPU acceptance remains

Use corrected official generator revision446bff55e1fe3d3aa29c771574e7d2b0fa322990, F16 generator568,663,392B +F16 codec78,823,104B. The old198068 default incorrectly retained v2602 labels and is excluded. Authenticated31,076,864B tokenizer ranges contain an exact16-member append-only TAR sequence; only10 final pinned members (20,598,079B) are installed. Superseded entries are accepted only when matching that exact pinned sequence. Generic ZIP rules remain unchanged.

`MagpieModels`, `MagpieSpeechEngine` and `scripts/voice/build-magpie-runtime.sh` use pinned NeMo caa3f47c7c75fa2e9e6340272ebad57c1647dd44 plus isolated GGML bd4f514db14d87fded667787a7a963bfbaa98e89. Actual C ABI/JNI Arabic/English22,050Hz PCM, speakers, callback exception identity, cancellation, retirement and callback-close were exercised. Bare `ar` maps to `ar-MSA`; SA/AE are preserved; `pt` maps to `pt-BR`. Native rate1; playback applies saved speed. Incremental native PCM does not imply verified S25 realtime performance. No Vulkan runtime is currently enabled.

## Task TTS-3: MOSS Nano — implemented

Official OpenMOSS source8b7bcc9341b3b4ef3a3a58ba1338a7d85ff133eb and pinned official ONNX files drive arbitrary text; no Python or pretokenized fixed-prompt shortcut. Private SentencePiece17d7580d6407802f85855d2cc9190634e2c95624 feeds the exact17-wide prompt grammar (16 codebooks). Global prefill/decode caches, local fixed sampling, codec offsets/KV/positions are retained correctly. Six codec frames flush23,040 samples (.48s). Stereo48kHz channels are explicitly averaged into mono and tested with distinct left/right signals and NaN/bounds.

MOSS exposes18 actual manifest references and19 explicitly enumerated publisher language codes; do not repeat a headline20-language count as an inventory. Raw SentencePiece input has no WeText number expansion. Optional user-reference encoder is excluded. Generation is capped at375frames/30seconds per120-character text part; missing EOS at that bound is an error. ORT termination handles active cancellation. [Real C ABI/JNI receipt](../research/moss-native-smoke-2026-10-20.json) covers Arabic/English finite PCM, callback errors and lifetime. No QNN/NNAPI/GPU provider is automatically selected.

## Task TTS-4: remaining adapters — implemented; acceptance remains

**OmniVoice:** ServeurpersoCom1a468d8b9d65b3544ca1004d2d7c05dfc65d1f3e plus private GGML cc5df49a6b878db2b68b9a7b45976e279594627d. The adapter runs the real diffusion schedule and Higgs codec, not generic llama text completion. Arabic maps to trained `arb` (passing `ar` is invalid upstream); initial exposed languages are Arabic/English. It produces native chunks, with cooperative cancellation between them. Attribute designs may vary between article parts. [Real receipt](../research/omnivoice-native-smoke-2026-10-20.json). Generator is noncommercial; codec Boson Community +incorporated MetaLlama3 terms override a misleading community Apache label.

**Chatterbox V3:** four exact graphs from Masterx revision19b94afe41de738624b2f3afc75b4cd56221fbeb, real HF tokenizer0.22.2, full-case NFKD, special IDs6563/255/0/6561, repeated speech start,16-head/64dim caches,6second reference and final speech-token960-sample trim. Initial native frontends are Arabic/English; no unsupported CJK/Russian frontend is advertised. Greedy Q4 uses repetition penalty1.2, at most500tokens/20seconds per120-character part. Waveform decoding and mandatory native Perth finish before bounded24kHz output; this is article streaming, not incremental live speech.

Raw V3 LM has three empty newer optional attention slots per node. ORT1.20 rejects those12-input declarations. `ChatterboxGraph` verifies raw228,633B/SHA, removes only90 absent inputs across exactly30GQA nodes with a bounded protobuf reader, preserves all attributes/weights/other fields, and verifies derived228,453B/SHA. Native uses `onnx/language_model_q4.ort20.onnx`. Same ORT1.24.3 raw-vs-derived Arabic41/English29tokens and seeded decoder+Perth PCM are bit-identical. Cross-version ORT1.20 English differs by one token; do not claim cross-version bit parity. Shared ORT was not upgraded.

Perth is mandatory. `ChatterboxPerth` converts the exact official37,429,684B checkpoint into37,387,276B `perth-encoder.bin`, validating whole source SHA, complete stored-ZIP inventory, each tensor SHA and final output SHA. It never reads/executes pickle. Native uses MIT chatterboxcpp1a9a4fc00a6caa8c8bb9bee533225f40661e4220 with prepared cancellation checks per STFT frame/convolution channel/time. Short/invalid audio fails. Native-vs-original Torch32k watermark maxerror1.64e-6; silence detector NaN is preserved. [Synthesis/graph receipt](../research/chatterbox-native-smoke-2026-10-20.json), [watermark receipt](../research/perth-watermark-parity-2026-10-20.json). No custom reference import or verified gender is offered.

**Oute1.0:** foldl/chatllm.cpp74b8237a4c1796f76bb0ed7f483b8e52e7d4d5c7 uses a pinned combined1,438,416,240B GGMM Q8 generator +IBM DAC1.0 file and25,295B English reference JSON. The original Python DAC checkpoint is not falsely advertised executable; this native package actually contains the compatible codec. Oute0.3 WavTokenizer/GGUF files are incompatible. Arabic is cross-lingual from the English reference. Recorded two-thread host runs took35.073s for3.080s English and38.656s for4.146s Arabic audio: explicitly slow/experimental, no phone speed claim. [Receipt](../research/oute-native-smoke-2026-10-20.json). Cooperative CPU cancellation and callback/lifetime tests pass. License is noncommercial/share-alike +Llama3.2, with separate DAC/reference terms.

## Native reproduction and validation

Each `scripts/voice/build-{magpie,omnivoice,moss,chatterbox,oute}-runtime.sh` verifies source pins, prepares any patches outside clean ignored upstream checkouts, builds separate CPU ARM64 DSOs with hidden/private symbols and16KB alignment, and records source sets +binary hashes in module `assets/licenses/<family>/runtime-build.json`. Chatter/MOSS share ORT, never broad common-jni. Chatter rebuilds real Rust tokenizer sources with locked Cargo dependencies. CI offline JNI fixtures never package fake runtimes.

Developer model fetches are explicit ignored fixtures: `fetch-<family>-test-assets.py`. Hash every component before real smoke; delete large weights after evidence. `run-<family>-host-smoke.sh` exercises actual JNI; offline `run-<family>-jni-contract-test.sh` exercises failure/PCM/callback-close contracts. MOSS stereo and Chatter tokenizer host tests additionally need their documented small fixtures/dependencies. The Chatter Kotlin exact-graph test uses retained228KB source graph only when the explicit developer fixture exists; synthetic protobuf tests always run.

Run integrated gates once the patch is stable:

```bash
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
./gradlew :common-jni:externalNativeBuildDebug :hearth-tts-supertonic:externalNativeBuildDebug :hearth-tts-magpie:externalNativeBuildDebug :hearth-tts-omnivoice:externalNativeBuildDebug :hearth-tts-moss:externalNativeBuildDebug :hearth-tts-chatterbox:externalNativeBuildDebug :hearth-tts-oute:externalNativeBuildDebug
python3 scripts/validate-model-handoff.py --render
```

Run common/speech/voice host suites and optional JNI fixtures. Before APK delivery build Play/FOSS QA and run `python3 scripts/verify-release.py`. Release verification checks exact staged vendor hashes, source provenance, exports, notices, unique runtime dependencies and alignment. Owner performs S25/S22 listening, first-audio, cold/warm load, RTF, memory and thermal tests. GitHub draft/tag delivery remains the owner's workflow; no child agent uploads or commits artifacts.

## Task TTS-5: Android system provider — conditional separate product

The current deliverable embeds article TTS in another app. No Maven publication or Android system `TextToSpeechService` is claimed. If the owner chooses a system engine later, create a separate provider application/module, engine metadata, bounded PCM16 `SynthesisCallback`, voice/language loading, stop/concurrency and explicit configuration. Do not bring Hearth UI/preferences/cloud keys into the tiny API or implicitly send text to a server. Publication also needs a selected artifact repository/version and a shrinker-tested external consumer sample.
