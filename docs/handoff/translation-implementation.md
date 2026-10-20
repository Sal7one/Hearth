# Translation implementation handoff

Research as of 2026-10-19; CT2 integration updated 2026-10-20. Target: local Arabic/English on the owner's Galaxy S25 Ultra. TVs must use configured cloud services. Preserve separate, independently selectable STT, MT and TTS stages. This document is an implementation specification, not a claim that every researched model runs in Hearth.

The complete pinned file inventory, SHA-256, exact byte sizes, licenses, language/direction inventories and evaluation caveats live in [models/translation.json](models/translation.json). Do not reconstruct downloads from rounded sizes in this document. Do not count precision variants as new trained models.

## Current state and deliverables

| Model | Required selected download | Current implementation | Next acceptance gate |
| --- | ---: | --- | --- |
| EuroLLM 1.7B Instruct Q4_K_M | 1,045,156,992 bytes | Pinned GGUF catalog/import/download/selection; publisher prompt; native CPU and explicit ARM64 GPU paths | Host CPU fixtures executed; reference-output parity and owner S25 sustained benchmark pending |
| Riva-Translate 4B Instruct v2 Q4_K_M | 2,759,302,720 bytes | Same GGUF path; separate publisher prompt; English-paired directions enforced | Host CPU fixtures + active cancellation executed; owner memory/thermal behavior beside STT and TTS pending |
| QuickMT EN↔AR | 773,707,570 bytes for both directions | ARM64 CT2 + SentencePiece runtime/session, pinned atomic download/ZIP install, shared dispatch/chooser/source bindings/setup/benchmark path; selected EN→AR production weights passed host samples | AR→EN real weights, official reference-output parity and owner S25 measurement |
| SMaLL-100 INT8 | 344,075,494 bytes | Same ARM64 CT2 path, separate target-token-on-source tokenizer, INT8_FLOAT32 compute; selected production weights passed host samples in both Arabic/English directions | Official reference-output parity, broader Arabic quality (sample lost subject) and owner S25 measurement |
| Jisr-MT 50M pair | INT8: 54,619,924 / 54,611,494 bytes per direction; FP32: 198,431,639 / 198,405,789 | Real CT2 conversion preserves trained decoder-start embedding and full vocabulary; real Rust ByteLevel BPE; dialect/DataStore/portable-setup/import/session integration | Owner S25 quality/performance; INT8 differs on 6 of 21 host examples |

The first two are added to `TranslationCatalog.models`; QuickMT direction models and SMaLL are in the separate `Ct2Catalog.models`. All new paths remain experimental. QuickMT and SMaLL now have separate executable catalog identities (`liveCatalogIds` in the handoff JSON); Jisr now has four importable runtime identities: two directions with FP32/INT8 precision choices, one trained family. See [Jisr integration](../models/jisr.md) and [real native/reference evidence](../research/jisr-native-parity-2026-10-20.json). The exact pinned QuickMT EN→AR and SMaLL INT8 production weights were downloaded, fully verified, executed through the production C++ adapter, then removed; see [exact host inputs/outputs and limitations](../models/ct2-host-smoke-2026-10-20.md). The later same-day pass executed QuickMT AR→EN on six pinned FLEURS sentences, and EuroLLM/Riva on six per English↔Arabic direction; all exact payloads were verified and removed. Riva required a publisher-template newline correction, with before/after receipts and active cancellation/recovery checks. See [the follow-up evidence and quality limitations](../models/mt-expansion-host-smoke-2026-10-20.md). The small upstream native CT2 Arabic transliteration fixture also passed FLOAT32 and INT8_FLOAT32. GPU capability is retained from the existing proven-placement loader; no new per-model GPU speed measurement exists.

All byte totals above include the selected package's tokenizer/configuration files. They exclude the runtime DSO, installation/staging copies, model activations, KV caches and Android memory. QuickMT's FP16 weights may be promoted at load on a CPU without a compatible FP16 compute path; never present the download size as peak RAM.

## Code map: existing architecture

- `common-jni/src/main/java/com/sal7one/common_jni/translation/TranslationCatalog.kt`: executable GGUF `TranslationModelSpec`, exact pins, language coverage, directions, licenses, prompt mode, publisher instructions. Added IDs `eurollm-17b-q4` and `riva-translate-4b-v2-q4`.
- `common-jni/src/main/java/com/sal7one/common_jni/translation/LocalTranslationSession.kt`: `CancellableTextTranslator`; pinned integrity check; owned native handle; blocking `open` and IO-dispatched `translate`; separate CPU/Vulkan/OpenCL runtimes. Keep `com.sal7one.common_jni` JNI names.
- `common-jni/src/main/cpp/translation/text_model.h`: actual llama.cpp owner, bounded context, deadline, sleeping persistent thread pool, cancellation, prompt prefix reuse and literal input tokenization.
- `common-jni/src/main/cpp/translation/translation_jni.cpp`: `LeaseRegistry` handle ownership and JNI protocol. Separate backend symbols are exported by existing map files.
- `common-jni/src/main/cpp/translation/{vulkan_text_model.h,opencl_text_model.h}`: require real GPU capability, actual offloaded model buffers/layers and execution metadata; no silent CPU fallback.
- `scripts/translation/{CMakeLists.txt,build-runtime.sh,stage-runtime.py}`: reproducible pinned llama.cpp build, Android CPU four ABIs and optional ARM64 GPU DSOs, license/provenance/staging hashes.
- `app/src/main/java/com/sal7one/transiber/translation/LocalTranslationModels.kt`: raw pinned single-file GGUF import and atomic replacement. It is not a CT2 directory installer.
- `app/src/main/java/com/sal7one/transiber/translation/ConversationTranslationSettings.kt`: shared saved local/cloud choice; `ConversationTranslatorSnapshot.open` dispatches cloud, ML Kit, Marian, cascade, CT2 or GGUF. Root added centralized TV policy here.
- `app/src/main/java/com/sal7one/transiber/translation/TranslationOptions.kt`: source and target choices, `supports`, model labels. Keep all call sites using exact directions rather than merely testing source/target set membership.
- `app/src/main/java/com/sal7one/transiber/translation/MarianPackage.kt`: existing pinned OPUS ONNX multi-file atomic installation template. Jisr is **not** an OPUS package.
- `app/src/main/java/com/sal7one/transiber/settings/{TranslationBrowserPresentation.kt,settings_translate_local_ui.kt}`: grouped cards/search/import/download/selection. Riva browser filtering now rejects unsupported non-English↔non-English edges.
- `app/src/main/java/com/sal7one/transiber/translation/TranslatorChooser.kt`: shared typed/caption/camera translation chooser; local options hidden on TVs.
- `app/src/main/java/com/sal7one/transiber/downloads/{DownloadAssets.kt,FileDownloads.kt}`: pinned download asset lookup, budgets and install dispatch. Preserve `ByokPolicy.FEATURE_BYOK` at every client and delegated download boundary.
- `app/src/main/java/com/sal7one/transiber/preferences/ModelSourceBindings.kt`: imported URI/source binding and launch validation. Register every new package component; do not trust retained permission or cached metadata alone.
- `app/src/main/java/com/sal7one/transiber/setup/PortableSetupActions.kt`: portable setup validation/apply/download. Root applies TV policy here. Include new runtimes in this dispatcher only after executable acceptance.

## Native prompt protocol 3

JNI `create(path: ByteArray, promptMode: Int)` replaces the old Boolean raw-prompt argument. All three runtime objects return and require `promptProtocolVersion() == 3` **before native model creation**. An APK with old DSOs must fail with a protocol mismatch; do not suppress this error.

| Mode | Family | Envelope |
| --- | --- | --- |
| 0 | Existing HY-MT / TranslateGemma / Index | Existing architecture-validated envelope; unchanged behavior |
| 1 | Existing MiLMMT | Raw Gemma instruction; no chat markers |
| 2 | EuroLLM | Empty system ChatML followed by user instruction and assistant generation prefix |
| 3 | Riva | Publisher-specific System/User/Assistant envelope; trusted instruction separated from literal user text |

New modes 2 and 3 require the GGUF `llama` architecture. They never infer the template merely from a generic Llama architecture. The selected publisher-pinned model spec supplies the mode. Existing mode 0 still rejects arbitrary Llama files.

EuroLLM instruction: `Translate the following English source text to Arabic:\nEnglish: {text}\nArabic:`. Native fixed envelope is `<|im_start|>system\n<|im_end|>\n<|im_start|>user\n...<|im_end|>\n<|im_start|>assistant\n` with actual newline characters.

Riva instruction: `You are an expert at translating text from English to Arabic.`. Kotlin passes `instruction + "\n\n" + text`; native separates the first blank line, tokenizes the instruction and source literally, and inserts only fixed wrapper markers with special-token parsing enabled. Caption text containing `</s>` or `<s>Assistant` cannot insert a new role.

**Riva separator correction:** the pinned official `tokenizer_config.json` stores quoted backslash-n escapes in Jinja source. Jinja renders actual U+000A line breaks, not literal backslash+n characters. The previous native envelope and the previous version of this paragraph were incorrect. The fixed native System/User/Assistant wrappers use real newlines while keeping instruction and source text literal. The [independent publisher-render receipt](../research/mt-riva-publisher-prompt-2026-10-20.json) records the exact tokenizer revision/hash, rendering environment and byte-level result; the native regression test compares the complete envelope with that rendered fixture. The prose README also has a different question-prefixed template; do not mix it with the pinned tokenizer template.

Riva's publisher coverage is English↔each supported language, not all multilingual combinations. `TranslationModelSpec.supports`, `directions`, and browser filtering enforce this. The current picker collapses regional Spanish, Portuguese and Chinese variants, so the adapter explicitly uses European Spanish, European Portuguese and Simplified Chinese. A later regional expansion must add distinct language IDs and prompt mappings instead of reusing a collapsed code.

`TranslationModelSpec.prompt` continues to accept 1–2000 source characters. The native context remains 2048 tokens with up to 384 output tokens and a 20 second deadline. These are caption/session limits; long articles must be segmented through existing text chunks or an explicit document controller, not truncated silently.

## QuickMT implementation recipe

1. Add a separate optional `transiber_ct2` native runtime, not a pretend GGUF extension. Candidate upstream source already cloned under ignored `research/CTranslate2`, revision `8c8cab2a954810216a45d65c1b8d56839c846e5b`, version 4.8.3. SMaLL conversion declares CT2 4.8.2; establish compatibility by real loading, not a version-string assumption.
2. Pin every dependency used: CT2's gitlinks include cpu_features `8a494eb1e158ec2050e5f699a504fbc9b896a43b`, Ruy `363f252289fb7a1fba1703d99196524698cb884d`, and spdlog `76fb40d95455f249bd70824ecfcae7a8f0930fa3`. Recursively record dependencies required by those submodules. Cached SentencePiece source in `build/speech-runtimes/src/sentencepiece` is revision `17d7580d6407802f85855d2cc9190634e2c95624`.
3. Establish a host smoke executable before Android/JNI. CT2 build starting flags: `WITH_MKL=OFF`, `WITH_CUDA=OFF`, `WITH_CUDNN=OFF`, `WITH_HIP=OFF`, `OPENMP_RUNTIME=NONE`, `BUILD_CLI=OFF`, `BUILD_TESTS=OFF`, `BUILD_SHARED_LIBS=OFF`; use `WITH_RUY=ON` as the first CPU math backend. This pinned CT2 source supports both FLOAT32 and INT8 GEMM through Ruy (`src/cpu/backend.cc` and `src/cpu/primitives.cc`). Start QuickMT with explicit `FLOAT32` compute and SMaLL with `INT8_FLOAT32`; QuickMT stored FP16 weights may therefore expand in memory, which must be reported. Treat OpenBLAS as a later measured optimization, not a prerequisite. Do not declare these flags sufficient until compilation and real-model execution pass.
4. Use one CT2 replica and a bounded serialized job queue; budget CPU threads alongside ASR/TTS. The app has no Android Adreno GPU provider for CT2. CUDA examples do not imply Android GPU support.
5. Native session owns `ctranslate2::Translator` and **two different** `SentencePieceProcessor` objects. Encode input using `src.spm.model`, append `</s>` like the [official QuickMT client](https://github.com/quickmt/quickmt/blob/5fe386f1d24a42fce4f7115726fb4b51ee83c054/quickmt/translator.py#L339-L350), translate piece strings, and decode the hypothesis using `tgt.spm.model`. These packages declare `add_source_bos=false`, `add_source_eos=false`, decoder start `<s>` and EOS `</s>`. The flags control CT2's automatic insertion; `add_source_eos=false` requires the client to supply the publisher's EOS once. QuickMT also uses `disable_unk=true`. The production adapter was corrected to this contract and rebuilt before the recorded host smoke. Preserve the independent SMaLL target-token-on-source contract. Compare official reference outputs on punctuation, numbers, Arabic diacritics and mixed English names before promoting quality readiness.
6. Implement cancellation/deadline semantics deliberately. CT2 `TranslationOptions.callback` returns true to stop generation **in greedy search only**. A callback stop produces a partial result, which must be rejected when cancellation/deadline triggered; never return it as successful final translation. Do not promise cancellable beam-5 decoding without testing a supported interruption mechanism. A safe caption-first implementation can use greedy search, then separately evaluate quality and offer beam search only with bounded lifecycle behavior.
7. Add `Ct2TranslationSession : CancellableTextTranslator`, with JVM `AtomicLong` and native `LeaseRegistry`, close-cancels-before-retire, one actual final output per request and errors surfaced verbatim. Export new symbols in `com.sal7one.common_jni.translation`; never reuse a Boolean ABI without a protocol bump.
8. Build a general pinned directory package schema. Each direction has exactly six required root files listed in JSON. Paired downloads use separate `en-ar/` and `ar-en/` install directories to prevent `model.bin`/vocabulary name collisions. Verify every part's size and SHA before atomically publishing the directory. ZIP manifest belongs at archive root; retain existing traversal and extraction limits.
9. Only after real backend acceptance, add package assets, resumable download/install UI, document/folder import, source bindings and launch verification; dispatch from `ConversationTranslatorSnapshot.open`, `TranslationOptions`, portable setup preview/apply, and benchmarks. A failed/missing runtime must explain itself, not silently select another model or emit an empty translation.
10. Compare original six-sentence pinned benchmark and a larger Arabic corpus on host and owner's S25. Beam-5 publisher FLORES scores are not a prediction of beam-1 Android quality.

## SMaLL-100 implementation recipe

Reuse the accepted CT2 session/package infrastructure, but keep a **separate tokenizer adapter**:

- Source pieces are `["__{targetLanguage}__"] + SentencePiece(text) + ["</s>"]`.
- The target-language token belongs on the **source**, not a forced target prefix as in a standard M2M100 implementation.
- Output piece strings decode through `sentencepiece.bpe.model`.
- Select `int8_float32` CPU compute where supported; report actual runtime compute type. No new quant is invented here: this exact public INT8 conversion already exists.
- Verify every one of the 100 target language tokens against the pinned vocabulary, excluding ordinary underscore strings. Map Hearth `fil` back to native `tl` only inside this adapter. Keep native `ns` explicit rather than guessing a normalized alternate.
- Initial acceptance: real English→Arabic and Arabic→English outputs match the reference CT2 pipeline at identical decode settings; added-token inventory and unsupported language errors covered without model weights; corrupted package/partial install rejected.
- Preserve MIT attribution and conversion provenance. Original floating-point paper spBLEU is not measured INT8 parity.

## Jisr-MT implementation and reproduction

The recipe below is implemented. `scripts/translation/jisr/export.py` uses the pinned CT2 BartLoader adaptation, disables Marian OPUS-specific zero start/last-vocabulary-row removal, and adds Apache-2.0 license plus source NOTICE. Native protocol2 supports the two directions in FP32/INT8 and preserves all14 exact dialect tags. The exact six installed files per variant are in `conversionProfiles` in the JSON card. Packages are import-only until an actual public runtime-package URL exists; never construct nonexistent publisher `model.bin` URLs.

This cannot be implemented by renaming an OPUS-MT folder.

1. Use both pinned original checkpoints and five required inference files per direction in JSON. Export ONNX with a verified encoder/decoder cache contract, or prove CT2 Marian conversion supports these custom checkpoints. The JSON source assets are **not** already executable inference exports. New exported assets need their own exact source revision, conversion command/environment, complete component sizes and SHA pins.
2. The tokenizer is Hugging Face **ByteLevel BPE**, regex pretokenization, no normalizer, distinct added special tokens. Existing OPUS SentencePiece/Unigram code is incompatible. Use a proven tokenizers implementation (with Rust/JNI artifact provenance) or implement ByteLevel BPE only after full reference-vector coverage, including UTF-8 byte mapping and special-token matching. Do not approximate it with whitespace or SentencePiece.
3. Forward inputs must prepend the selected exact target tag plus one space. `>>ara<<` means MSA, `>>ars<<` means Saudi. Reverse inputs always prepend `>>eng<<` plus one space; do not add a source dialect tag.
4. Keep publisher dialect mappings exactly: `apc` Lebanese; `ajp` Syrian; `ajp-ps` Palestinian. They do not map cleanly to strict modern ISO labels and must not collapse into one normalized Levantine code. The full 14-target tag table is in JSON.
5. Config/tokenizer audit: 32,000 base BPE entries, forward dialect added IDs 32000–32012, config `vocab_size=32013`. Card prose says 32014 and is off by one; executable files are authoritative. Reverse vocab is 32000. Decoder start/pad ID=0, EOS=1; publisher generation max length 256, beam 4, no-repeat 3-grams. Model positional limit 1024.
6. Implement explicit dialect choice as Advanced config, portable JSON setup field, DataStore preference and card capability. Basic Arabic should use MSA unless the user selects a dialect bundle; never silently switch article reading into Saudi dialect.
7. Reject unsupported long inputs or segment explicitly; do not inherit tokenizer's serialized truncation silently. Compare exact token IDs and decoder outputs against the reference Transformers example.
8. Show quality caveats in details: reverse MSA loses 6.17 BLEU versus its base; Algerian/Bahraini/Iraqi have no external benchmark. The two directional models have different training bases, so do not exchange tokenizers or assume one shared bidirectional checkpoint.
9. Only after export/tokenizer parity, add assets/installer/chooser/setup integration as above. Cover direction-specific errors, tag persistence, Unicode, missing source files, unsupported imported package and model replacement rollback.

## Integration acceptance and regression checks

Keep existing models and source/target choices working; every new engine must implement the same translator interface rather than adding a second app-wide pipeline. Model selection must preserve user's independent STT/MT/TTS choices. Do not auto-enable TTS or rewrite existing preferences when a model downloads.

Changes completed in this pass:

- New Euro/Riva pinned specs, explicit licenses and evaluation notes.
- Publisher-specific prompt modes, protocol 3 across JVM/JNI; all four CPU and both optional ARM64 GPU DSOs rebuilt/staged with 16 KiB validation and provenance/SHA refresh.
- New C++ regression coverage for Euro empty-system ChatML, Riva exact template separators, literal source control markers, bad template inputs and wrong architectures.
- Riva directions restricted in catalog and browser; host benchmark script can select both new families.
- TV local translation UI/chooser/selection blocked through `LocalModelPolicy`; cloud choice is explicit, saved local model files/preferences are not deleted.

Validation performed before root integrated checks: translation CTest **3/3 pass**; Python macOS translation benchmark helper **2/2 pass**; all six translation DSOs built and staged. That earlier prompt-build checkpoint did not execute new GGUF model weights. Subsequent Jisr inference is recorded separately: 21/21 FP32/reference exact matches, 15/21 INT8/FP32 exact matches and20 exact tokenizer vectors. Root owns the single combined Gradle validation to avoid competing builds.

Run from repository root (Gradle needs existing local caches and JDK 17):

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
./gradlew :common-jni:externalNativeBuildDebug
python3 -m unittest discover -s scripts/benchmark -p test_macos_translation.py
cmake --build build/translation-runtime/host --target translation_runtime_test translation_smoke -j 4
ctest --test-dir build/translation-runtime/host --output-on-failure
```

For a reproducible rebuild, `scripts/translation/build-runtime.sh android {ABI}` for all four ABIs; then `... android arm64-v8a vulkan` and `... android arm64-v8a opencl`. Run the matching `stage-runtime.py` commands **sequentially**, because each updates a shared provenance/checksum file. Never stage from a stale source-set build.

Owner S25 acceptance must cover Arabic and English both directions, cold load, warm median/p95 segment latency, cancellation and restart, foreground/overlay lifecycle, peak combined STT+MT+TTS memory, 10–20 minute thermal behavior and actual GPU placement metadata. CPU/Vulkan/OpenCL must be compared per model on the same texts. Do not infer a GPU win from a selected setting.

For future APK delivery, build both QA variants and run `python3 scripts/verify-release.py`; follow the owner's GitHub draft-release workflow. This handoff does not itself require APK delivery or device control. Do not use adb.
