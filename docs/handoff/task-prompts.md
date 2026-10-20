# Bounded implementation assignments for Hearth

Use one assignment at a time. Give the implementing AI the common instruction block and the chosen task. The master plan and specialist plans explain the codebase; these prompts define small reviewable outcomes. Do not give a less capable agent the single instruction “implement all models.”

## Common instruction block

> Work in the Hearth repository. Read `AGENTS.md`, `docs/handoff/README.md`, the specialist implementation plan for this task, and the exact pinned card before editing. Inspect current Git status and preserve existing changes. Do not rename `com.sal7one.common_jni`, weaken model integrity/archive checks, add network capability to FOSS, or use adb. Local models are prohibited on Hearth TVs; do not silently choose a cloud provider. Keep STT, MT and TTS independent. Own only this task's files and coordinate shared catalogs/Gradle files. Add the smallest real end-to-end adapter, not a runnable-looking card backed by a stub. Use real errors and bounded queues. Run the task's host tests and both QA Kotlin compilations when shared app code changes. Report exact executable status, files changed, checks performed, remaining blockers and owner device checks. Do not claim phone speed, model quality or GPU execution from a build alone.

## Dependency and ownership order

| Task | Dependency | Ownership |
| --- | --- | --- |
| A Verify branch foundations | None | Current SDK, TV policy and GGUF MT integration; no new engine work |
| B Card readiness and explorer | A | Shared card/readiness UI and source evidence |
| C Cohere and Fun native runtime | A | One shared pinned transcribe.cpp runtime owner |
| D Audar multimodal runtime | C package conventions | Dedicated speech adapter; coordinate enums with C |
| E OWSM runtime | C package conventions | Dedicated speech adapter; coordinate enums with C |
| F Voxtral bounded partials | C | Same transcribe.cpp build; one owner of shared source pin |
| G CTranslate2 QuickMT | A | New translator runtime and package layout |
| H SMaLL100 | G | Model-specific CT2 tokenization and profile |
| I Jisr tokenizer | None | Pure tokenizer converter and golden fixtures only |
| J Jisr model export | I | One chosen export runtime; no UI until parity passes |
| K Magpie assets and voices | None | Correct tokenizer/profile/speaker mapping and reproducible package |
| L Magpie runtime | K and A | Optional SDK native adapter and actual Android DSO |
| M MOSS Nano | A | Optional SDK ONNX adapter and full frontend/codec |
| N OmniVoice | A | Optional SDK native model/codec adapter |
| O Chatterbox V3 | A | Optional SDK ONNX adapter; exact V3 components |
| P OuteTTS | A | Optional SDK generator plus correct DAC codec |
| Q Portable setups and Basic bundles | B plus runnable models | Coordinator owns cross-family schemas and presets |
| R SDK distribution and Android provider | A plus selected runnable voice adapter | Small AAR packaging, public API, optional platform service |
| S Owner evaluation pack | Any working adapter | Fixtures, benchmark/report support; no device automation |
| T Final integration and draft release | Relevant tasks completed | One coordinator runs global gates and publishes honest draft |

Two agents must not simultaneously edit `SpeechModels.kt`, `CaptionOverlayConfig.kt`, `DownloadAssets.kt`, `FileDownloads.kt`, `settings.gradle.kts` or native source/provenance manifests. Model-specific adapter files may be developed in parallel; the coordinator integrates shared registration changes after review.

## A Verify the existing branch foundations

> Verify the current `:hearth-tts`, `:hearth-tts-supertonic`, app consumer, TV policy and EuroLLM/Riva changes before extending anything. Read `validation.md` and reproduce the checks relevant to your environment. Confirm the SDK can read two articles sequentially, rejects a concurrent article, copies borrowed PCM, applies backpressure, propagates synthesis errors, cancels before native teardown and closes once. Check Arabic/English male and female Supertonic choices are actual publisher IDs. Confirm protocol 3 Kotlin/native binaries agree and old protocol 2 fails before model creation. Test stale TV local selections at actual import/download/open boundaries. Fix regressions in this foundation; do not add a new family in the same patch.

Exit: current branch compiles, required tests pass, no lost existing preference/model behavior. Preserve the distinction between a small core AAR and the current Supertonic adapter's heavy `common-jni` dependency.

## B Add consistent card readiness and explorer presentation

> Read all 15 records in `docs/handoff/models/` and existing `ModelSearch`, source-link and model-browser components. Introduce a small typed readiness model consumed by the actual explorer. Separate researched/prepared, runtime integrated, inference verified and device tested. Show a prepared model without offering Install/Use when this APK lacks the adapter. Default cards show family, Arabic/English support, complete selected download, voices and readiness. Advanced details reveal every component, license, pin, source and metric scope. Keep existing search/filter behavior and installation repair. Do not copy the handoff JSON into an importer as though it were a runtime ZIP manifest. Add tests for readiness/action gating, search aliases, optional component totals and model/quant grouping.

Exit: a missing runtime cannot be mistaken for an installed runnable model; all source/detail links use existing validated URL handling.

## C Integrate Cohere Arabic and Fun ASR MLT

> Follow the pinned transcribe.cpp APIs and exact assets in the STT plan. Build one isolated CPU runtime with separate Cohere/Fun plugin identities and architecture checks. Map Hearth's source language explicitly into upstream language values. Use the existing bounded utterance segmenter, 16 kHz mono input, sticky errors, exact-once finals and handle ownership. Start with Cohere Q4 and Fun Q8 reference packages. Prove host real-model parity on Arabic/English/silence before creating live catalog profiles. Then build arm64 Vulkan separately and report actual placement. Extend profile/package/native source fingerprint/build-stage/catalog/import/selection/source-binding/benchmark paths as one coherent integration. Do not route these files into the TDT-only TranscribePsy adapter.

Exit: real inference through the production session, host lifecycle failures covered, CPU availability independent of GPU drivers, correct per-family capabilities and all changed Android mappings compiled.

## D Integrate Audar Flash with both model components

> Use the selected quantized decoder and required BF16 audio encoder/projector together. Read the current Qwen multimodal source API and the model's exact language/output protocol. Hearth's current Qwen adapter and translation DSO are not proof this path already exists. Add a separate correct runtime/plugin, atomic two-file install and required role checks. Match the documented audio features, prompt steering, no-speech verdict and output prefix handling. Preserve the actual Arabic/English source identity. Bound chunking/redecode work. Verify that missing/wrong encoder, unsupported language, silence, cancellation during load and repeated independent utterances fail or complete correctly.

Exit: both files contribute to integrity and size accounting; real Arabic/English reference parity passes; no generic chat prompt or one-file “ready” status.

## E Integrate OWSM Base

> Implement the pinned audio.cpp OWSM API as a separate speech plugin. Use the selected self-contained GGUF and its embedded tokenizer/normalization metadata. Do not invent external tokenizers. Validate architecture/profile, input bounds and source-language behavior. Feed bounded completed utterances; do not call complete-file text deltas live cache-aware streaming. Add real-model Arabic checks because the small English conversion check is insufficient. Keep weight attribution CC-BY-4.0 distinct from the runtime's code license.

Exit: a verified package loads through `SpeechRuntime`, emits one final per utterance, surfaces errors, and the UI describes its actual behavior and unmeasured Arabic limitations.

## F Add Voxtral partial captions without unbounded replay

> Read the pinned native streaming implementation rather than relying on the model name. Current native tentative partials can reprocess accumulated audio. Implement bounded windowed partials first using existing utterance IDs/revisions/final rules and a fixed maximum context. Use `UTTERANCE_WINDOWED` plus accurate partial capability unless you implement and prove true incremental caches. Reset or forced boundaries must not repeat finalized text or discard a pending final. Test long continuous speech, no-speech, partial rewrites, backpressure, stale callbacks, stop and reset. Keep the 480 ms algorithmic delay separate from measured phone latency.

Exit: bounded memory/work and exact-once finals under sustained input. A later cache-aware implementation requires a separate parity/performance patch.

## G Add CTranslate2 with QuickMT as the first consumer

> Follow the translation plan's pinned CTranslate2 source and submodules. Use the initial CPU path `WITH_RUY=ON`, QuickMT `FLOAT32` compute for FP16-stored weights; report the resulting memory promotion explicitly. Do not assume CTranslate2 CUDA support works on Adreno. Implement a narrow native adapter behind `CancellableTextTranslator`, separate from llama.cpp. Use both direction-specific SentencePiece models and source/target vocabularies exactly. Implement an atomic six-file package per direction, selectable independently, with full integrity checks and no Python runtime. Establish real host reference token/output parity before Android. Add load/inference cancellation, error, empty-output and close-in-flight tests. A cancellation that cannot interrupt upstream compute must suppress delivery and retain ownership until return.

Exit: both QuickMT directions work in the existing caption/typed/camera consumers without consumer-specific inference code; only requested directions download. SMaLL is not part of this patch.

## H Add SMaLL100 using the tested CT2 path

> Reuse task G's runtime and lifecycle. Implement SMaLL100 language tags/decoder initialization from its actual reference code; it does not inherit QuickMT tokenization. Use the pinned INT8 package and `INT8_FLOAT32` CPU compute initially. Test both Arabic directions, unknown tags, punctuation, numbers, very short captions and Arabic INT8 parity. Surface the difference between original paper scores and selected-quant measurements.

Exit: a separate SMaLL profile/package is selectable through the same production translator interface without changing QuickMT behavior.

## I Prove Jisr tokenizer behavior before exporting a model

> Read both exact Jisr cards, tokenizer JSONs and reference preprocessing. Implement or select a concrete ByteLevel BPE tokenizer route. Do not use the existing OPUS SentencePiece tokenizer. Produce pinned golden token-ID and decoded-text fixtures for English, MSA, Saudi dialect, Arabic punctuation/harakat, mixed script, spaces and special tags. Verify forward and reverse vocabularies independently, including the documented vocabulary-count discrepancy. Verify the exact Saudi/MSA tags and reverse English tag. Work only on tokenizer/converter tests and fixtures; no runnable app catalog entry.

Exit: byte-exact IDs and round trips match the pinned reference on all fixtures. A generic “BPE supported” claim is insufficient.

## J Export and integrate Jisr after tokenizer acceptance

> Continue only after task I passes. Choose one bounded route for this task: export encoder plus decoder and decoder-with-past to ONNX, then use the existing ORT ownership patterns with the proved Jisr tokenizer. Pin export dependencies/options and generated file hashes. Compare encoder outputs, first-token logits, cached decode and complete translations to the original checkpoint in both directions, including Saudi/MSA tags. Add runtime/import/profile support only after parity passes. Preserve dialect-specific direction metadata; do not present the reverse model's MSA regression as a universal improvement.

Exit: reproducible export, no Android Python/pickle execution, correct cached decoder ownership/cancellation, and exact required components in the package.

## K Prepare correct Magpie v2607 assets and voice mapping

> Reproduce the model/tokenizer profile described in the TTS plan before writing the Android adapter. Verify selected Q8 GGUF `speaker_names` against v2607's actual speaker map, because older conversions used a different ordering. Obtain and checksum the correct tokenizer/frontend assets from the pinned source archive or a reproducible bounded export. Keep source archive size, selected GGUF/codec size and installed runtime size separate. Produce a root manifest with all required tokenizer, generator, codec and speaker metadata. Do not publish 411 MB as the complete download. Obtain actual voice/gender evidence rather than inferring gender from names.

Exit: a self-consistent package and voice-ID golden fixtures verified against the reference. Incorrect speaker mapping blocks task L's user-facing voice selector.

## L Integrate Magpie through the small TTS SDK

> Use task K's matched assets and pinned NeMo-Speech.cpp TTS C API. Build a separate optional Android DSO and adapter module behind `SpeechEngine`, leaving `:hearth-tts` free of native/model/network dependencies. Enumerate actual voices, implement the exact UTF-8/tokenizer path, stream bounded PCM callbacks, validate sample format and report failures. Cancellation is callback-based upstream; signal it promptly, suppress stale output and retain the handle through long initial encoder work. Prove CPU reference parity, then build/test optional Vulkan with real placement and symbol isolation. Use the app's existing audio focus/playback consumer.

Exit: Arabic and English synthesis with correct speaker IDs, real engine discovery, production article-reader integration and no fake “GPU ready” claim.

## M Integrate MOSS Nano beyond the pretokenized demo

> Use the exact official ONNX LM, codec and metadata components in the TTS card. Implement full user-text tokenization, Arabic/English language protocol and streaming state; pretokenized Android smoke inputs are not a complete adapter. Decide whether reference-audio encoding is supported and make its extra component optional only when unused. Handle the actual 48 kHz stereo codec output by a tested explicit conversion into the current SDK's mono PCM contract. Never relabel stereo samples mono. Add state reset, empty PCM, finite-value, cancellation, long article and Arabic reference checks.

Exit: arbitrary supported user text works through `SpeechEngine`, tokenizer and codec state are correct, and the default package contains every required generation component.

## N Integrate OmniVoice model and codec together

> Use the pinned Q8 generator plus quantized codec, not an older unquantized-codec size estimate. Add the native runtime behind an optional SDK adapter. Verify all codec operators and audio dimensions, and compare the quantized package against the original reference on Arabic/English samples. Handle reference voice requirements explicitly. Preserve CC-BY-NC weight restrictions in cards, manifests and packaging; a permissive C++ code license does not change the weights. Follow SDK cancellation, bounded-frame and ownership rules.

Exit: generator and codec both run correctly, source/quant quality claims stay separate, and no noncommercial weights are silently bundled into a commercial-ready provider artifact.

## O Integrate actual Chatterbox Multilingual V3

> Use the exact V3 ONNX package, tokenizer, embedding/LM, reference encoder, flow decoder and vocoder from the card. English-only Turbo and current native V2 conversion do not satisfy this task. Add an optional ONNX adapter with correct reference/default voice handling, source language tokens, bounded audio and cancellation. Keep unverified reference gender UNKNOWN unless supplied with trustworthy metadata. Test Arabic and English plus missing/reference-incompatible inputs; English-only converter checks do not establish Arabic quality.

Exit: actual V3 Arabic synthesis and full component integrity pass through the production SDK adapter. Do not claim a native Vulkan V3 path based on V2 code.

## P Integrate OuteTTS 1.0 and its DAC codec

> Pair the selected generator with the correct IBM 24 kHz 1.5 kbps DAC codec. The source `.pth` is not an Android runtime codec; first implement a reproducible conversion/native codec route with parity. Do not reuse Oute 0.3's different tokenizer. Implement generation/cancellation and bounded PCM through `SpeechEngine`, and account for the model's generated-token demand before making latency claims. Reference/default voice data must be explicit required components. Preserve CC-BY-NC-SA restrictions.

Exit: whole generator-to-waveform parity, correct speed/rate behavior, no missing codec, and exact total download/install accounting.

## Q Wire portable setups and simple bundles after adapters work

> Read current `PortableSetup`, catalog, preview, actions and plan tests. Extend catalog references/schema only for runnable profiles and validated new configuration fields. A setup file may select independent STT/MT/TTS stages but must not carry arbitrary URLs, executable code, keys, device paths or hidden provider switches. Preview all stages and report missing runtimes/files/languages/directions before changing preferences. Preserve root-manifest and model integrity checks. Add a few Basic presets; keep per-engine/backend/voice/dialect details Advanced. Persist choices with existing DataStore APIs and source bindings. Test both new imports and existing exported recipes, plus TV rejection.

Exit: setup import/export survives restart, applies only requested stages, never half-applies an invalid graph, and never starts a download or provider request merely by previewing.

## R Package the small SDK and optional Android TTS provider

> Keep `:hearth-tts` as a small network-free core with `SpeechEngine`, voice metadata and cancellable article PCM flow. The current Supertonic adapter still depends on broad `common-jni`; extract a dedicated TTS native/ORT dependency closure before calling the distributed implementation lightweight. Publish separate optional adapter AARs with sources, API docs, consumer rules and license notices, without model weights or the application module. Provide a minimal Arabic/English article sample showing strict male/female selection, explicit fallback, collection/playback and closeAndJoin. Test release/R8 consumption from a separate sample app. An Android `TextToSpeechService` is a separate optional provider app/module, not required in the core AAR; implement platform voice discovery, installed-data checks, synthesis callback lifecycle and stop only if assigned that follow-up explicitly.

Exit: consumers can integrate the core without Hearth UI, cloud permissions or all speech engines; artifact sizes and transitive dependencies are measured, not guessed.

## S Prepare owner evaluation fixtures

> Extend the existing benchmark/report infrastructure with deterministic Arabic/Gulf/English/code-switch/silence speech fixtures and Arabic/English article fixtures. Compare real production adapters, exact model/runtime/quant hashes and identical input/normalization. Separate cold loading, queue time, inference, endpoint waiting and playback. Prepare 20-minute sustained streaming/article instructions, strict male/female voice listening choices and stop/replace/background cases. Do not automate or access the owner's device. Keep private content out of diagnostics unless the owner explicitly exports it.

Exit: the owner can test every newly runnable option later and export a comparable result bundle; no invented WER/MOS/latency values appear as measurements.

## T Integrate and prepare the GitHub draft

> Review all completed adapters and update readiness fields to the actual evidence. Run the full root AGENTS gates, relevant native host suites, package/source fingerprint tests, both QA assemblies and release verifier. Verify FOSS permissions, ABI closure, 16 KB alignment and signing continuity. Keep weights, APKs, signing material and local paths out of Git. Create a reviewed commit/tag and source-only archive, then prepare a GitHub draft with actual ready/experimental/prepared status and test limits. Use installable signed QA artifacts until production signing exists. Do not send APKs in chat, rename unsigned files into installation downloads, or claim all 15 integrated when some are still planned.

Exit: exact validation/provenance and honest draft release assets, with every remaining owner device check listed.
