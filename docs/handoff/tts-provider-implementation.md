# Hearth TTS provider implementation handoff

This document is for an implementer who has not worked on Hearth. Read `AGENTS.md` first. The owner wants Arabic and English article reading, preferably male and female voices, reusable by other Arabic apps. Local execution targets phones, especially the owner's Galaxy S25 Ultra. **Hearth TVs must not run downloaded local models.** Android's separately installed system TTS remains an explicit service choice. The reusable library does not impose Hearth's app-specific TV policy on other apps.

Implementation resumed on 20 October 2026. **Magpie is now a real experimental CPU arm64 provider**, integrated through the reusable SDK and app family selector. The other four proposed new TTS families remain planned. [models/tts.json](models/tts.json) records their distinct readiness.

The new `:hearth-tts-magpie` module depends only on the small API, not broad common-jni binaries. It supplies an isolated 1,797,600-byte NeMo CPU runtime on arm64 and a separately built JNI bridge. The bridge keeps `com.sal7one.common_jni`, uses lifetime-bearing leases, enumerates actual speakers and emits bounded mono float PCM through ArticleReader. A fixed packaged library name is loaded; downloaded archives never contain executable code.

Live Magpie package: corrected official F16 generator 568,663,392 bytes +F16 codec 78,823,104 bytes +authenticated tokenizer ranges 31,076,864 bytes =678,563,360 download bytes. Final tokenizer members occupy 20,598,079 bytes, making installed model assets 668,084,575 bytes. The current official 446bff F16 metadata explicitly identifies v2607 and correctly orders Aria, Jason, John, Leo, Sofia. **The older upstream default 198068 F16 was tested and still had stale v2602 labels; it is excluded.**

The 31 MB payload is read through strict HTTPS206/Content-Range/content-length checks, a full SHA-256 and an exact 16-occurrence archive sequence. NVIDIA's append-only TAR contains superseded configurations/dictionaries; only the 10 final pinned members are installed. Generic ZIP duplicate/path/size rules are unchanged. FOSS imports can use the exact `tokenizer.ranges` file produced by the explicit test-asset script. TV and process-ABI policy gates guard app download/import/playback.

Real macOS CPU C ABI/JNI smoke tests generated Arabic and English 22050 Hz PCM and verified 5 speaker names, callback abort, cancellation propagation, bounded frames, idempotent destruction and retired-handle errors. These are functionality checks, not S25 speed/quality claims. Upstream callback abort currently reports a runtime output error rather than its documented cancellation status; the JNI layer maps an explicitly canceled request to CancellationException and preserves genuine callback/provider errors.

The public SDK maps bare `ar` to `ar-MSA`, preserves `ar-SA` and `ar-AE`, and maps `pt` to `pt-BR`; passing bare `ar` directly to the current upstream tokenizer fails. Chinese/Japanese optional native frontends are excluded. Native generation rate is 1.0; Hearth applies its existing rate preference at Android playback. Named speaker genders remain UNKNOWN until primary metadata or owner listening evidence establishes them.

`VoiceChoice.family` persists through DataStore and portable native-voice setup JSON. Legacy files default to Supertonic; per-family remembered voice IDs preserve previous selections. App native transport's existing `supertonic` identifier remains compatible while family selects the actual engine.

Reproduce native builds with `scripts/voice/build-magpie-runtime.sh android arm64-v8a`. Explicitly fetch ignored test assets with `scripts/voice/fetch-magpie-test-assets.py`, then run `scripts/voice/run-magpie-host-smoke.sh` on macOS. Never run owner-device commands. Owner listening, S25 thermal/memory measurements and optional Vulkan remain outstanding.

## What exists now

| Layer | Files | Purpose |
|---|---|---|
| Small public API | `hearth-tts/src/main/java/com/sal7one/hearth/tts/{SpeechEngine,ArticleText,ArticleReader}.kt` | No app, network, JNI or model dependency. Article input, verified voice preferences, PCM stream, lifecycle and backpressure. |
| Working adapter | `hearth-tts-supertonic/src/main/java/com/sal7one/hearth/tts/supertonic/{SupertonicCatalog,SupertonicSpeechEngine}.kt` | Existing Supertonic 3 native engine behind the public API; shared pinned file inventory and integrity checks. |
| Production consumer | `app/src/main/java/com/sal7one/transiber/voice/VoicePlayer.kt` | Shared app read-aloud uses `ArticleReader` for downloaded Supertonic, preserving existing Android and remote backends. One Android `AudioTrack` stays open across stream frames and text parts. |
| Existing downloader/importer | `app/src/main/java/com/sal7one/transiber/voice/{VoiceCatalog,VoiceModels,VoiceSetup}.kt` | Existing model downloads and bounded imports remain app responsibilities. Catalog delegates to SDK pins. |
| Existing native ownership | `common-jni/src/main/java/com/sal7one/common_jni/voice/SupertonicVoice.kt` and `common-jni/src/main/cpp/voice/` | Atomic Java handle, lease registry, exclusive destruction, ONNX cancellation, bounded output tensors. No native package rename. |
| Host tests | `hearth-tts/src/test/java/com/sal7one/hearth/tts/ArticleReaderTest.kt` | Arabic/supplementary Unicode preservation, exact voice selection, failure propagation, bounded production, cancellation and teardown. |

The core is small in dependency scope. **The Supertonic adapter is not yet a small redistributable native runtime:** it depends on the existing broad `common-jni` AAR, which also contains STT and translation infrastructure. Do not publish it as a tiny standalone TTS binary yet. No Maven publication configuration, public semantic version, external package or Android `TextToSpeechService` has been created.

## Public API and example

Add `:hearth-tts` for the API or `:hearth-tts-supertonic` for its working local adapter. Install files matching `SupertonicCatalog.assets` in the chosen app-private directory. The library does not download files, add permissions, accept provider keys or create a UI.

```kotlin
val reader = ArticleReader(SupertonicSpeechEngine(modelDirectory))
try {
    reader.read(
        text = articleText,
        language = "ar", // "en" also supported; choose per article/segment
        voice = VoicePreference(id = "M1", gender = VoiceGender.MALE),
        options = SpeechOptions(rate = 1f, qualitySteps = 5),
    ).collect { event ->
        when (event) {
            is ArticleSpeech.VoiceSelected -> {
                // Display fallbackReason if the caller explicitly allowed fallback.
            }
            is ArticleSpeech.Audio -> {
                // Feed event.pcm.samples to a single mono float AudioTrack.
                // Respect event.pcm.sampleRate. Playback must apply backpressure.
            }
        }
    }
} finally {
    withContext(NonCancellable) { reader.closeAndJoin() }
}
```

`SpeechEngine` is a blocking adapter contract, deliberately isolated from Android audio playback. It exposes supported `SpeechVoice`s, a text bound and `synthesize(..., SpeechCancellation, emit)`. It emits nonempty finite mono float PCM in frames no longer than one second. `cancel()` must be safe across threads and idempotent. `close()` runs on the provider's owning worker after synthesis returns. Future native engines must obey this contract rather than calling Android UI/audio APIs directly.

`ArticleReader.read` is a cold `Flow<ArticleSpeech>`. Each collection starts an article. It emits one `VoiceSelected`, then ordered `Audio` frames with the original text part and index. Normal flow completion is the article final signal; no synthetic success is emitted after errors or cancellation. **Exactly one article can execute per reader.** A concurrent article fails with a useful busy error. Callers wanting a queue must bound it and select whether to replace, wait or skip. Do not silently enqueue unlimited articles.

`stop()` cancels the active flow and immediately requests engine cancellation. Cancellation before native construction is covered by the per-article token. `close()` is idempotent and requests asynchronous worker teardown; `closeAndJoin()` additionally waits for actual engine destruction. App playback holds `nativeGate` until `closeAndJoin()` finishes, preventing a canceled native owner from overlapping its replacement. Do not call `closeAndJoin()` while blocking the worker thread itself.

The stream uses an explicitly owned `Channel(capacity = 1)`. Cancellation must call `channel.cancel()` **before** waiting for the native worker completion signal. `channel.close()` alone leaves pending senders suspended: waiting for teardown while a native callback is blocked sending into a full channel deadlocks. Preserve this ordering; external cancellation and close-with-full-queue tests cover it. Engine callbacks block when playback is slower than generation. At most the frame being consumed, the queued frame, and a producer's current frame are in flight. Supertonic still generates a bounded text part as a whole native float array before splitting it into API frames; this adapter is **article streaming**, not sample-by-sample model inference. Its native tensor limit is still relevant to peak memory. Future Magpie/MOSS adapters should emit the model's actual incremental codec chunks.

## Voice, language and text contracts

- `SpeechVoice.gender` is `UNKNOWN`, `FEMALE` or `MALE`. Set gender only from verified publisher/profile metadata, never from a name or pitch guess.
- `VoicePreference` defaults to `REQUIRE_MATCH`. A requested voice or gender failing to match the language is an error. The caller may opt into `ALLOW_SAME_LANGUAGE`, which returns a visible fallback reason. It never changes the requested language or sends speech to a cloud backend.
- Supertonic has ten verified gender-coded choices: F1–F5 and M1–M5. Each style works with the shared model's Arabic and English language inputs. A style is usable only after its pinned file and all core files are installed and verified.
- A family declaring Arabic does not prove that each English reference speaker sounds native in Arabic. New family gender labels and reference profiles require listening tests. The JSON inventories distinguish named baked speakers, verified gender metadata and unverified reference voices.
- There is no implicit language detector or Arabic-to-English translation. Mixed-language articles currently need caller-chosen segments/language. A future paragraph auto-language mode must be explicit, preserve ordering and avoid sending untranslated text to a wrong-language engine.
- `ArticleText` lazily splits 1–200,000 UTF-16 characters at sentence/word boundaries where possible. It keeps original non-whitespace code points and never cuts a surrogate pair. It is not an HTML parser, diacritizer, transliterator or grapheme-cluster segmenter. The app still caps its existing queue requests at 5,000 characters; the public provider permits longer articles.
- The original Supertonic `VoiceText.ids` performs that engine's established normalization and language tags after article splitting. Do not reuse it for unrelated families. Chatterbox V3 needs full-case NFKD; Magpie needs its matched IPA/text tokenizer; MOSS uses SentencePiece and a prompt grammar; Oute needs the 1.0 prompt/timing format.
- An engine returning empty or non-finite audio is an error. Do not convert a canceled/missing/unsupported model into empty success or silently fall back to Android voices.

## Five new model cards and integration order

The JSON has exact revisions, URLs, byte counts, SHA-256, sources, license notes, voices and blockers. These are source/component inventories, not root-level ZIP package manifests. Some source components need conversion/extraction before Android use. All sizes are bytes; RAM and installed footprint are separate.

1. **Magpie v2607** is now integrated experimentally with the corrected F16 generator and F16codec (647,486,496bytes). The earlier proposed Q8 generator pluscodec was410,635,296bytes but its stale speaker metadata is excluded. It also requires matching tokenizer/config dictionaries from a 1,470,208,000-byte `.nemo` archive. A safe range extractor/repackager can avoid downloading its redundant weights, but must use matching revision/range/member pins. Do not label 411 MB as the entire install. Five baked names are Aria, Jason, John, Leo and Sofia. The official v2607 order is 0,1,2,3,4 respectively. **Older GGUF conversion wrote the v2602 order into v2607.** Inspect `speaker_names` and tokenizer profile, or reconvert from the matching archive. Gender remains unknown in our cards until primary metadata or owner validation is recorded.
2. **MOSS-TTS Nano** is the first ONNX implementation. Official generator, tokenizer, waveform decoder and metadata inventory is 717,868,602 bytes. Reference encoding adds 45,322,911 bytes. Its Android smoke example uses pretokenized text, so it is not a complete article frontend. Native output is **48 kHz stereo**; choose an explicit tested downmix to the current mono API or extend the API with channels and layout. Do not interleave stereo and call it mono. The compact PyTorch headline is not the ONNX download size.
3. **OmniVoice** is an Advanced evaluation backend. Selected Q8 main/codec total 945,284,608 bytes. The pretrained model is noncommercial. The conversion card's codec license description conflicts with original custom-license metadata; resolve original terms before redistribution. There are no verified bundled fixed male/female Arabic presets. Keep voice design/reference profiles separate from the generation model and label them honestly.
4. **Chatterbox Multilingual V3** needs an ONNX adapter with contrib `MatMulNBits` support and the exact V3 tokenizer/trim logic. The selected Q4 stack totals **1,556,285,252 bytes** including reference and tokenizer. Earlier prose quoting 1.626 GB was an overcount. Native output is 24 kHz mono. The export omits upstream Perth watermarking; inspect upstream policy and document/implement the output provenance decision. V2 source compatibility is not V3 quality validation. Turbo is English-only and must not be substituted.
5. **OuteTTS 1.0** is Advanced experimental and noncommercial/share-alike with Llama terms. Q4 generator plus IBM DAC source weights/config total 1,113,900,979 bytes. The DAC `.pth` is **not executable in Hearth**. It needs a separately pinned Android graph/native conversion. Use the 1.0 two-codebook 24 kHz/1.5 kbps codec, not the 0.3 WavTokenizer. The official example names `EN-FEMALE-1-NEUTRAL`, but its profile bytes and Arabic quality are not pinned. No male/native-Arabic default profile is claimed.

For ordinary Arabic article apps, first validate Supertonic, then Magpie and MOSS as separate small runtime dependencies. Keep OmniVoice/Oute out of unrestricted commercial default bundles. Separate code license, weight license, codec license and speaker-profile rights. Do not add broad consent or permission UI unrelated to a feature; reference profile import needs provenance only when that feature is implemented.

## Task TTS-1: isolate a distributable Supertonic runtime

**Inputs:** working adapter and current native voice code. **Output:** standalone TTS-specific runtime AAR; API AAR unchanged; app adopts that runtime without duplication.

1. Trace `common-jni/src/main/cpp/voice/supertonic.cpp`, ONNX wrapper headers, `LeaseRegistry` and JNI exports. List only required source/dependencies and notices. Preserve `com.sal7one.common_jni` JNI names.
2. Create a TTS runtime module rather than adding all STT runtimes to the SDK dependency tree. Package one correctly sized, 16 KB-aligned ONNX runtime per supported ABI. Prevent duplicate `libonnxruntime.so` if app and provider are linked together. Keep handles, cancellation and lease registry semantics.
3. Do not rename the broad app runtime or change engine scheduling while extracting it. Make the app use exactly one native voice implementation. Include notices for runtime/ONNX/NDK and keep model weights separate.
4. Add consumer R8 rules for JNI bridges only. Publish API and runtime artifacts separately after owner chooses package version/repository and source license requirements are resolved. Measure AAR compressed/uncompressed bytes and per-ABI contribution. No invented size promise.
5. Regression tests: missing/truncated/wrong SHA assets, style shape/NaN rejection, cancellation during hashing/native load/inference, repeated article reuse and stop/restart, close while native run active, exact-one teardown, long Arabic article, alternate Arabic/English, F/M profiles, no network permission in core/runtime manifests.

**Done:** other apps can use TTS without STT/translation runtime dependencies; Hearth still passes app/native gates; minimal sample builds with shrinker and a local test model; model downloads remain explicit and outside the library.

## Task TTS-2: Magpie CPU implementation and remaining GPU/device checks

CPU integration is implemented. Continue from these actual files:

- `hearth-tts-magpie/build.gradle.kts`: optional API-only runtime module. No dependency on the large common-jni binary.
- `hearth-tts-magpie/src/main/cpp/magpie_jni.cpp`: stable C ABI loaded from fixed packaged `libhearth_magpie.so`; monotonic lease-registry handles, serialized synthesis, cross-thread JNI attachment, borrowed PCM copies, callback exception transport and exclusive destruction.
- `hearth-tts-magpie/src/main/cpp/vendor/tts.h`: original Apache-2.0 upstream C ABI at caa3f47c7c75fa2e9e6340272ebad57c1647dd44. Preserve append-only struct `size` fields.
- `MagpieCatalog.kt`: exact corrected F16model/codec pins, final tokenizer members, authenticated range hash and exact 16 regular-member archive sequence. The current generator revision is446bff55e1fe3d3aa29c771574e7d2b0fa322990; do not regress to198068 orQ8 without inspecting actual speaker metadata.
- `MagpieModels.kt`: pinned weight imports, full SHAverification and bounded authenticated tokenizer extraction. Earlier .nemo snapshots may only be discarded if they match the exact pinned occurrence sequence. Unknown paths, types, fields, sizes, hashes or extra final members fail.
- `MagpieSpeechEngine.kt`: lazy verified native model load; correct language mapping; real speaker enumeration checked against the pinned model; bounded mono float PCM; token-aware cancellation and exclusiveclose. Native cancellation is terminal for that session; a subsequent article recreates its runtime.
- `scripts/voice/build-magpie-runtime.sh`, `nemo-tts-inject.cmake`, `magpie.map`: separately built CPUruntime, static private GGML, unique SONAME, only 12 stable C ABI exports,16 KB alignment. Pinned NeMo source caa3f47c7c75fa2e9e6340272ebad57c1647dd44; llama/GGML bd4f514db14d87fded667787a7a963bfbaa98e89. Optional GPU is not built.
- `scripts/voice/fetch-magpie-test-assets.py`, `run-magpie-host-smoke.sh`, `magpie-smoke.cpp` and`tests/com/sal7one/common_jni/voice/MagpieNative.java`: explicit ignored test assets and real CABI/JNI smoke tests. No networkfetch runs fromGradle.
- `app/.../voice/MagpiePolicy.kt`: actual running-process runtime availability plus TVpolicy. Download/import/install/select/portableapply/playback must keep this guard; a64bit device can still run a32bit APK.
- `app/.../voice/MagpieTokenizerDownload.kt`: fresh credential-freeHTTPclient; exactly two 206 range responses and content lengths, then fullpayload/member hashes. No provider key is sent to modelhosts. Cancellation cancelsHTTPcalls and deletes temporary data.
- `app/.../settings/settings_voice_magpie_ui.kt`: family voice/preview/install UI, EN/AR/ZH labels and explicit unavailable runtime state. Downloadedweights use existing systemDownloadManager; tokenizerinstallation is a foreground cancellable31MBstep. Retry is safe because all components stay pinned.
- `app/.../voice/VoiceSettings.kt`, `VoicePlayer.kt`, `setup/PortableSetup*.kt`: compatible localtransportidentifier plus explicitfamilyselection, per-family remembered voices, portablefamilyfield and one continuousAudioTrack. Native rate stays1.0; Android playback applies the saved rate.
- `hearth-tts-magpie/src/main/assets/licenses/magpie/`: source/binary provenance, originalruntime/GGML notices. Modelweights are downloaded separately; no weights reside inGit.

Run the module JVM tests and existing9coretests. The real-source tokenizer fixture test uses the authenticated ignored range file when available. CABI smoke validates corrected5speakers and nonempty Arabic/English 22050 HzPCM. JNI smoke additionally validates Java callbacks, bounded frames, requested cancellation, idempotent destroy and stalehandle rejection. Upstream currently returns a runtime error after a raw falsePCMcallback; the JNI bridge maps explicit cancellation from its own atomicflag to CancellationException and preserves genuine callback exceptions/errors. Do not treat every provider output failure as cancellation.

Remaining work:

1. Owner runs Arabic/English listening and sustainedS25Ultra measurements. Record firstaudio, warm/cold load, realtimefactor, peakmemory and temperature. MacCPU success does not establish phoneperformance or Arabic pronunciationquality. Keep the model labeled experimental until that evidence exists.
2. Optional Vulkan needs a separate pinned artifact and backend diagnostics. The current CABI backend enum namesAUTO/CPU/CUDA; inspect actual Vulkan placement rather than assumingAUTO meansGPU. Start from CPUparity, isolateGGML symbols, build pinned Vulkanheaders/shaders as the other speech runtime recipes do, and verify no Vulkanload occurs whenCPU is selected.
3. Confirm real speaker gender metadata/listening observations before male/female labels. Do not infer gender from personal names. Default Arabic isMSA; expose SA/AEchoices deliberately when UI language preferences support them.
4. The upstream standardgenerationwindow is bounded; preserve punctuation, exact tokenization and cancellationlatency bounds. Do not destroy a handle during anactive call. Additional native cancellation before firstPCM may require a narrow upstream patch, with tests and source provenance.
5. A fully resumablebackgroundtokenizerdownload is a later refinement. The current foregroundstep is31MB, cancellable, authenticated and safely retryable; the two largeweightfiles already use systemdownloadtracking.

## Task TTS-3: MOSS ONNX adapter

Use the pinned components in the JSON and read the official repository's Android example plus browser reader implementation. Implement SentencePiece natively; reproduce exact prompt IDs, language marker semantics, global KV cache, local autoregression and codec state. Preserve cached input/output ownership and cap token/frame/cache sizes before allocation. Decode multiple codebooks, use correct stop tokens and do not interpret “AR” in conversion docs as Arabic when it means autoregressive.

Do not use the community REALBITS INT8 export as Arabic-ready: that conversion explicitly narrows its validated languages. Start with the official graphs. Implement stereo output correctly. A mono downmix should be explicit and tested with independent left/right signals, clipping/NaN bounds and sample count. Reference encode graphs are optional for synthesis-only; keep them out of a Basic download until a real reference feature needs them.

Test generated tokens and waveform against the original implementation for fixed seeds/sampling, Arabic punctuation/diacritics, English acronyms/numbers and alternating language segments. Report Nano-specific scores, not larger MOSS family scores. Only after CPU correctness should an ORT GPU provider or a smaller GGML conversion be considered. Hearth currently has no automatically enabled QNN/NNAPI/LiteRT provider for arbitrary ONNX models.

## Task TTS-4: other adapters and voice packs

OmniVoice needs its native diffusion schedule/generation algorithm and codec; a generic llama.cpp text completion call is insufficient. Chatterbox needs its exact speech encoder/embed/LM/conditional decoder order and V3 preprocessing. Oute needs1.0 prompt metadata plus an executable DAC graph and timing-compatible decode.

Each adapter must implement `SpeechEngine` behind a capability descriptor: actual languages, sample format, fixed vs reference voice mode, known gender inventory, streaming behavior, rate support, installed components, selected backend and unavailable reason. Do not advertise rate control if an adapter only supports1.0; either offer explicit PCM time-stretch with quality tests or reject unsupported rates.

Create separate versioned speaker-profile packs for Arabic male, Arabic female, English male and English female only when actual profiles exist, are licensed and have listening evidence. Save profile IDs and preferences through app DataStore; keep generated audio and reference data private. Never label generated/random/unknown voices as verified male/female. Do not infer native Arabic pronunciation from an English reference profile.

## Task TTS-5: Android system provider (later, separate module)

An embedded article library and a system-wide Android TTS engine are different products. Start with the embedded API to avoid importing Hearth UI/preferences/cloud keys into third-party apps. If the owner wants other apps to select Hearth in Android TTS settings, create a separate `TextToSpeechService` application/provider module with an intent service declaration, engine metadata and user-visible installation/configuration.

Implement language availability, voice enumeration, language/voice loading, synthesis callback `start/audioAvailable/done/error`, synchronous stop, lifecycle and concurrent-client limits. Android `SynthesisCallback` uses bounded PCM16 buffers; stream provider output without assembling a whole article in memory. Honor caller language and voice; never send text to a network provider automatically. Apply the provider app's explicit device/network policy, including whether TV execution is permitted. Test with a small client app and Android service tests; owner drives hardware tests and adb remains forbidden unless explicitly requested.

## Test and delivery gates

Run once when the integrated patch is ready:

```bash
./gradlew :hearth-tts:testDebugUnitTest :hearth-tts-supertonic:compileDebugKotlin
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
```

Native changes additionally require existing speech/common host suites and `externalNativeBuildDebug`; new optional libraries need their own host ABI/error/cancellation suites and all packaged ABI/alignment checks. Before any APK delivery, build both QA variants and run `python3 scripts/verify-release.py`. Do not publish model support based on host compilation alone. Owner later measures S25 real-time factor, first audio, warm/cold load, peak memory, temperature and Arabic/English voice preference. Keep results per model/quant/runtime/device; never reuse desktop CUDA latency as a phone claim.

Delivery uses the owner's existing GitHub draft/tag workflow, not APK attachments. Do not commit weights, ignored upstream checkouts, native build outputs, signing stores, keys or `local.properties`. Do not release a standalone SDK from a repository patch without explicit version/license/publication decisions.
