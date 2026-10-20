# Hearth TTS provider implementation handoff

This document is for an implementer who has not worked on Hearth. Read `AGENTS.md` first. The owner wants Arabic and English article reading, preferably male and female voices, reusable by other Arabic apps. Local execution targets phones, especially the owner's Galaxy S25 Ultra. **Hearth TVs must not run downloaded local models.** Android's separately installed system TTS remains an explicit service choice. The reusable library does not impose Hearth's app-specific TV policy on other apps.

The latest instruction is plan-first: preserve a coherent, tested provider foundation and produce an actionable implementation plan. **None of the five new model families is integrated or advertised as executable.** Their evidence cards are in [models/tts.json](models/tts.json). Do not turn these staging cards into live download buttons without completing their runtime and package gates.

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

1. **Magpie v2607** is the first native integration. Q8 generator plus F16 NanoCodec decoder is 410,635,296 bytes. It also requires matching tokenizer/config dictionaries from a 1,470,208,000-byte `.nemo` archive. A safe range extractor/repackager can avoid downloading its redundant weights, but must use matching revision/range/member pins. Do not label 411 MB as the entire install. Five baked names are Aria, Jason, John, Leo and Sofia. The official v2607 order is 0,1,2,3,4 respectively. **Older GGUF conversion wrote the v2602 order into v2607.** Inspect `speaker_names` and tokenizer profile, or reconvert from the matching archive. Gender remains unknown in our cards until primary metadata or owner validation is recorded.
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

## Task TTS-2: real native Magpie adapter

Ignored research checkout available locally at `research/tts-expansion/NeMo-Speech.cpp`, revision **caa3f47c7c75fa2e9e6340272ebad57c1647dd44**. Its llama.cpp submodule is **bd4f514db14d87fded667787a7a963bfbaa98e89**. Do not commit that checkout or any model weights. For a fresh environment, explicitly clone/pin those revisions into an ignored build directory.

Read these upstream files before coding:

- `include/nemo_speech/tts.h`: stable C ABI, ownership and struct `size` fields.
- `docs/sdk.md`, `docs/tts/models.md`, `docs/tts/configuration.md`.
- `src/tts/c_api.cpp`, `src/tts/synthesizer.cpp`, `src/tts/magpietts/CMakeLists.txt`.
- `models/index.json`: default component pins and bounded tokenizer tar-range/member extraction evidence.
- Existing Hearth `scripts/speech/build-runtimes.sh`, `scripts/translation/build-runtime.sh` and symbol maps: follow their isolated-library/reproducible-source pattern.

Start with a **CPU-only arm64 build** to validate tokenizer and ABI. Upstream requires CMake >=3.26; Android app common-jni uses 3.22.1, so build optional NeMo separately. Local `/opt/homebrew/bin/cmake` is 4.4.2; NDK is 27.0.12077973. A configure recipe to adapt and test is:

```bash
cmake -S research/tts-expansion/NeMo-Speech.cpp \
  -B build/voice-runtimes/magpie-arm64-cpu -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384' \
  -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF \
  -DGGML_VULKAN=OFF -DGGML_METAL=OFF -DGGML_CUDA=OFF \
  -DNEMO_SPEECH_BUILD_ASR=OFF -DNEMO_SPEECH_BUILD_DIAR=OFF \
  -DNEMO_SPEECH_BUILD_TTS=ON -DNEMO_SPEECH_BUILD_NMT=OFF \
  -DNEMO_SPEECH_BUILD_CLI=OFF -DNEMO_SPEECH_BUILD_MIC_CAPTURE=OFF \
  -DNEMO_SPEECH_BUILD_HTTP=OFF -DNEMO_SPEECH_BUILD_GRPC=OFF \
  -DNEMO_SPEECH_BUILD_TESTS=OFF -DBUILD_TESTING=OFF \
  -DNEMO_SPEECH_TTS_WITH_JA=OFF -DNEMO_SPEECH_TTS_WITH_ZH=OFF \
  -DNEMO_SPEECH_WITH_NORM=OFF
cmake --build build/voice-runtimes/magpie-arm64-cpu --target nemo_speech_tts -j4
```

This recipe has **not been executed or proved Android-compatible** in this patch. Verify the upstream patch application path and target dependencies before treating output as shippable. Do not suppress a compilation failure by removing tokenizer support or model layers. Resolve platform-specific build failures as explicit small patches with pinned source inputs. A CPU build is not GPU acceleration evidence.

Then implement a small optional JNI bridge/runtime module:

1. Keep JNI package `com.sal7one.common_jni`. Load a fixed packaged native library; never load executable `.so` files from downloaded model archives. Prefer the stable C ABI. Isolate NeMo's GGML symbols and SONAME from the app's Whisper/translation GGML copies; static GGML into a separate shared runtime with an export/version script and `--exclude-libs,ALL` is the existing pattern. Inspect `llvm-readelf --dyn-syms/--needed`, ABI architecture and load segments.
2. Create an opaque handle with exclusive lifetime ownership. `nemo_speech_tts_create` receives the matched generator, codec and tokenizer directory. Enumerate real `speaker_count`/`speaker_name`; never hardcode names into a stale GGUF. Select language and voice for every request.
3. The PCM callback receives **little-endian signed16 mono**. Copy before callback returns, convert to float exactly once and emit bounded frames. Returning false cancels the operation. Do not save the callback's borrowed pointer. A Java exception must stop native callbacks and propagate as a real error.
4. Upstream exposes no independent cancel entry in this C ABI. Use an atomic per-request cancellation token checked by the PCM callback and before JNI entry. Document worst-case delay before first callback. Do not destroy the synthesizer while a call is active; retire handle, signal cancel, wait for owner, then destroy. Consider a narrow upstream cancellation patch if initial encoder latency is material.
5. Map returned status and `nemo_speech_tts_last_error()` into meaningful Kotlin errors. `ERROR_CANCELLED` must remain cancellation; invalid arguments/runtime failures must not become no audio. Do not assume thread-local error text can be read on another thread.
6. Retain a model session across article parts to preserve warm speed. Default threads should leave capacity for independent caption/MT stages. Make longform/window/cache options backend capabilities in Advanced; Basic selects a tested bundle.
7. Build separate Vulkan arm64 artifact only after CPU parity passes. Configure pinned Vulkan headers, shader generator and host `glslc` following existing speech/translation recipes; prove tensor placement and report backend diagnostics. The C ABI backend-preference enum currently names AUTO/CPU/CUDA, so inspect actual runtime backend selection and do not claim a CPU enum enables Vulkan.
8. Package native notices, original source revision, patch hashes, binary hash and size in provenance. Native APK needs16KB alignment and all included ABI checks. Keep GPU optional; fail clearly if selected but unavailable rather than silently claiming GPU.

**Model package:** build a safe archive with root manifest matching Hearth's importer and approved exact files. Use an ignored builder to extract the matched `.nemo` tokenizer/config assets, reject links/path traversal/duplicate paths and enforce compressed/uncompressed bounds. The upstream index's range/member hashes are tied to its pinned model revision; do not apply them to a different `.nemo` merely because byte count matches. Record installed size and full download size separately. Enable catalog download only after reproducible artifact hashes and model load smoke tests.

**Done:** actual owner-testable Magpie backend, real Arabic/English tokenizer, all five real voices enumerated, cancellation/error path tests, CPU parity, verified optional Vulkan backend, correct installed/download size, notices and model cards. No future-only option looks installed or runnable.

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
