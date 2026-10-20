# STT implementation handoff

Status: implementation plan and pinned preparation cards, 19 October 2026. **None of these five models has been integrated, built for Android, or measured on an owner device in this workstream.** No live speech enum, installer, catalog, JNI, or native runtime has been changed here. The user subsequently requested a complete handoff before broader implementation; this document is that handoff, not a claim of completed support.

Read this alongside `models/stt.json` in this directory. That file contains exact model artifact revisions, file sizes, SHA-256 values, required components, variants, language limits, quality provenance, and blockers. `planned-*` card IDs are deliberately not live `SpeechProfile` IDs. Metadata hashes were read from public Hugging Face LFS metadata; payloads have not been downloaded or independently hashed in this handoff. Verify actual bytes during any later installation.

## Product decisions that must survive implementation

- Target local inference on the owner's **Samsung Galaxy S25 Ultra**. Do not tune the shortlist around television hardware. **Android TVs must not execute any local model**, even if an imported setup or old saved selection points to one. The owner tests devices; do not use adb or operate their phone.
- Keep independently selected stages: **STT → optional translation → optional TTS**. A bundled setup chooses those stages; it must not fuse their backends or force a translation/TTS model when someone only needs captions.
- A model family, a trained adaptation, a checkpoint, an artifact quantization, and a runtime backend are distinct concepts. Audar is new trained Qwen-derived weights; Q4/Q8 alternatives do not count as new models.
- Basic mode offers concise bundles and clear ready/download states. Put thread budgets, explicit acceleration, context/delay knobs, custom paths, raw error details, and advanced imports in Advanced. Persist per-profile choices in DataStore. Opening settings must not probe a GPU driver or load weights.
- Preparation cards may be searchable, but **must not have an enabled Install/Use action until their complete package and actual runtime are available on this ABI**. A research card is not an installable `SpeechArtifact`.
- `foss` has no network permission. Gate direct requests and delegated Android downloads with `ByokPolicy.FEATURE_BYOK`. Never attach provider API keys to GitHub, Hugging Face, or arbitrary file hosts. Local imports remain usable under the device policy.
- Keep `com.sal7one.common_jni`, native handle ownership, bounded audio queues, pinned integrity, and exact-once finals. Engine failures must reach the UI as errors. An explicitly successful no-speech verdict is different from an engine failure.
- Do not copy upstream servers, model managers, media editors, FFmpeg integrations, or unrelated model families into Hearth. Host-only developer conversion tools are separate from shipping the app.

## Five exact targets

Decimal sizes below are download weights, **not peak RAM**. Complete totals and per-file hashes are in `models/stt.json`.

| Candidate | Required default assets | Planned first adapter | Truthful streaming label | Important limitation |
|---|---:|---|---|---|
| Cohere Transcribe Arabic 07-2026 | one Q4_K_M GGUF, **1,558,162,848 bytes** | pinned transcribe.cpp | utterance finals | require Arabic/English selection; Auto is unavailable |
| Audar-ASR V1 Flash | Q4_K_M decoder + **BF16** mmproj encoder, **775,275,936 bytes combined** | llama.cpp `mtmd` | utterance finals first; optional windowed LocalAgreement later | cannot load through the existing Qwen ONNX adapter; never quantize its sensitive audio encoder |
| Voxtral Mini Realtime 2602 | one Q4_K_M GGUF, **2,830,493,984 bytes** | pinned transcribe.cpp stream API | **windowed partials for the current native implementation** | model architecture is streaming, but current native tentative partials reprocess accumulated input; Auto language only |
| Fun-ASR MLT Nano 2512 | one Q8_0 GGUF, **891,271,232 bytes**; Q4 variant **556,975,168** | pinned transcribe.cpp | utterance finals | require MLT variant; ordinary Nano does not provide this Arabic coverage; resolve selected export license discrepancy |
| OWSM v4 Base 102M | one Q8_0 GGUF, **118,204,672 bytes** | OWSM-only audio.cpp | utterance finals | token streaming from a complete file is not live microphone streaming; exact Arabic Q8 WER remains unverified |

Cohere has 2,065,804,048 stored parameters. Audar totals 782,426,112, including its 186,376,192-parameter audio encoder. Voxtral's official stored total is **4,429,679,360**, despite the 4B marketing name. Fun-ASR and OWSM use approximate publisher counts in the cards.

Quality numbers must retain the dataset, selected weights, and inference settings. The pinned transcribe.cpp documentation currently reports Cohere Q4 Arabic FLEURS WER **11.18%**, Fun-ASR Q8 Arabic/English FLEURS WER **25.88% / 5.02%**, and Voxtral Q4 LibriSpeech test-clean WER **2.08%**. Audar's six-set Arabic **32.04%** and Voxtral's official Arabic FLEURS **22.53%** are original-weight results, not verified Arabic results for the selected quants. OWSM conversion **1.21% word disagreement versus Python** is not human-reference WER. These are different evaluation protocols; do not create a fake universal score/ranking from them.

## Where the current application actually routes speech

Start with `AGENTS.md`. The baseline investigated was `release/v0.25.4` at `6c15890b284b6c74074d13d7d45d1311d0cf6035`. Other agents are editing adjacent code; inspect the current diff and preserve their work. Do not reset the checkout to this baseline.

| Existing file | Role / invariant | Required extension |
|---|---|---|
| `common-jni/src/main/java/com/sal7one/common_jni/speech/SpeechModels.kt` | `SpeechBackend`, `SpeechProfile`, `SpeechCapabilities`, `SpeechOptions.validate` | add runtime identities/profiles only when a compiled adapter is available; model-specific hints/Auto/partial kinds; explicit per-backend acceleration |
| `common-jni/src/main/java/com/sal7one/common_jni/speech/SpeechModelPackage.kt` | verifies root `hearth-speech.json`, declared files, roles, sizes/SHA, path/symlink/count limits | exact role sets for new profiles; single GGUF model vs Audar model+encoder; verify GGUF identity before native execution |
| `common-jni/src/main/java/com/sal7one/common_jni/speech/SpeechSession.kt` | `SpeechRuntime.availability/open`; serialized `SpeechSession`; close/reset/cancellation ownership | keep existing ABI config fields where possible; route profiles to adapter IDs; unavailable ABI must surface a real error |
| `common-jni/src/main/java/com/sal7one/common_jni/speech/LiveSpeechProcessor.kt` | bounded recorder-to-inference work | retain backpressure and overflow error; no unbounded list of audio windows |
| `common-jni/src/main/cpp/speech/backend_abi.h` | `HearthSpeechBackend` ABI **2**, despite `_v1` symbol suffix | no C++ allocation/STL object crosses the boundary; retain ABI unless genuinely necessary |
| `common-jni/src/main/cpp/speech/speech_session.cpp` | validates config; `loadBackend`; loads pinned DSO; owns plugin instance; assigns utterance/revision IDs | extend exact IDs/library/symbol/pin mapping and acceleration allowlist; do not broaden to arbitrary GGUF |
| `common-jni/src/main/cpp/speech/backends/transcribepsy_backend.cpp` | example bounded offline adapter | use as an ownership/segmentation pattern; do not reuse its Parakeet TDT runtime for new architectures |
| `common-jni/src/main/cpp/speech/backends/nemotron_backend.cpp` | existing genuinely cache-aware engine/partial handling | reference final/endpoint semantics; do not relabel new redecoding adapters as equivalent |
| `common-jni/src/main/cpp/speech/utterance_segmenter.h` | energy endpointing, 20 ms frames, 200 ms preroll, bounded max utterance | reuse for offline adapters; if introducing incremental callbacks, preserve existing behavior and tests |
| `app/src/main/java/com/sal7one/transiber/caption/CaptionOverlayConfig.kt` | stable `CaptionEngineChoice` persisted choices | append new choices; never rename existing stored values |
| `app/src/main/java/com/sal7one/transiber/caption/LocalSpeechModels.kt` | import/selection, staging, installed profile→engine mapping | strict role mapping, atomic complete-package installation; no half-complete Audar package becomes selectable |
| `app/src/main/java/com/sal7one/transiber/caption/CaptionEngineController.kt` | local package selection, runtime probe/open, capture and translation bridge | new engines must follow the existing speech branch; TV policy enforced before runtime probe/model IO/load; leave MT/TTS selection independent |
| `app/src/main/java/com/sal7one/transiber/caption/LocalSpeechSetup.kt` | profile fallback and language controls | profile-specific labels/hints; do not fall through to the Nemotron coverage text for new families |
| `app/src/main/java/com/sal7one/transiber/caption/SpeechAccelerationStore.kt` | per-engine DataStore acceleration selection | new Vulkan choices only after a corresponding packaged plugin is available; CPU remains the default |
| `app/src/main/java/com/sal7one/transiber/caption/SpeechTuning.kt` | per-profile persisted window/silence settings | reuse; keep 1,000–15,000 ms utterance and 200–2,000 ms silence bounds, 20 ms increments |
| `app/src/main/java/com/sal7one/transiber/models/SpeechDownloads.kt` | exact pinned installer sources | add installable single-GGUF entries only after adapter gates; introduce transactional component groups for Audar |
| `app/src/main/java/com/sal7one/transiber/models/PublisherSpeechPackage.kt` | stages publisher TAR or single-model packages | maintain archive/path limits; introduce a separate multi-file staging transaction instead of pretending the decoder alone is a complete model |
| `app/src/main/java/com/sal7one/transiber/models/PublisherSpeechReceipts.kt` | trusted receipt outside imported model tree | multi-component receipt must bind all component hashes + profile + manifest hash; receipt from an imported ZIP is not trusted |
| `app/src/main/java/com/sal7one/transiber/models/SpeechArtifactCatalog.kt` | **installable** catalog; maps artifacts to engine | do not put preparation cards here as working models; no family=runtime assumptions |
| `app/src/main/java/com/sal7one/transiber/models/SpeechCatalogInstallations.kt` | proves exact publisher artifact installed | Audar match requires decoder AND encoder, not same profile or decoder hash alone |
| `app/src/main/java/com/sal7one/transiber/models/ModelSources.kt` | linked publisher/runtime notes | new accurate provenance, licenses, runtime/streaming caveats |
| `app/src/main/java/com/sal7one/transiber/settings/SpeechArtifactBrowser.kt` | speech explorer cards and actions | show package total, languages, model family, linked card, readiness; gate actions by local-device policy + ABI/runtime availability |
| `app/src/main/java/com/sal7one/transiber/settings/SpeechBrowserPresentation.kt` | family titles, grouping/search, actual decode-mode descriptions | variants group under one checkpoint; retain windowed vs streaming distinction |
| `app/src/main/java/com/sal7one/transiber/setup/PortableSetupCatalog.kt` and `PortableSetupActions.kt` | JSON setup resolution/validation/apply | include new known families only after live IDs exist; verify package identity/presence, and TV policy before download/apply/use |
| `app/src/main/java/com/sal7one/transiber/benchmark/LocalBenchmarkRunner.kt` | local pipeline measurement | add consistent profile routing; never start local benchmark on TV; record each stage separately |
| `scripts/speech/make-package.py` and `test_package.py` | creates/validates package manifests from an explicit profile allowlist | add role sets and malformed/incomplete Audar cases; keep ZIP manifest at archive root |
| `scripts/speech/build-runtimes.sh`, `runtime-sources.py`, `verify-android-runtimes.py`, `stage-runtimes.py`, `stage-gpu-runtime.py` | source pins, exact source snapshots, private exports, 16 KB pages, hashes/licenses | extend or add narrow new-runtime equivalents; never stamp old binaries with newly calculated source hashes |
| `scripts/verify-release.py` and `scripts/native_abis.py` | actual packaged ABI/dependency/provenance checks | explicitly recognize new DSOs, exact exported ABI entrypoints, optional arm64 GPU status; do not globally loosen allowed dependencies/symbols |

Also run `rg 'SpeechBackend\.|SpeechProfile\.|CaptionEngineChoice\.' app common-jni scripts/speech` and let both Kotlin variants compile after enum additions. Settings, bundles, diagnostics, tests, model-source links, and portable setup validation contain exhaustive assumptions beyond the principal table.

## Source pins and proposed native layout

Use exact source checkouts in ignored `research/` or generated `build/`. `.gitignore` already excludes both. No model weights, build outputs, generated native binaries outside the established tracked runtime publication process, or upstream Git history should enter a commit.

| Runtime source | Exact revision | Narrow use |
|---|---|---|
| [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp/tree/1dc22facfc931e2f8adb43924b0e38b9c46f83c7) | `1dc22facfc931e2f8adb43924b0e38b9c46f83c7` | Cohere, multilingual Fun-ASR Nano, Voxtral Realtime |
| [audio.cpp](https://github.com/0xShug0/audio.cpp/tree/673ade451c9cba9c893fb0e606872d9dc44f21e3) | `673ade451c9cba9c893fb0e606872d9dc44f21e3` | OWSM only |
| [llama.cpp](https://github.com/ggml-org/llama.cpp/tree/64e9bceb2c3a856efed96feda784a50947049feb) | `64e9bceb2c3a856efed96feda784a50947049feb` | Audar decoder + `tools/mtmd/models/qwen3a.cpp` audio encoder |

The last pin is already the translation source pin and **contains** Qwen3-ASR mtmd code. Its presence is verified. Audar execution on that pin is not. The existing translation library deliberately disables tools/mtmd and accepts text only; do not send an audio GGUF to `LocalTranslationSession`.

transcribe.cpp vendors a modified GGML based on `d7cb574130e6f01ad25b3289685489200febcd74`; its `ggml/UPSTREAM` names two downstream patches. Build that vendored tree at the parent pin. Do not substitute Hearth's different translation GGML silently.

Suggested new files (proposed, **not present yet**):

- `common-jni/src/main/cpp/speech/backends/transcribe_cpp_backend.cpp`: private wrappers for three transcribe.cpp families.
- `common-jni/src/main/cpp/speech/backends/transcribe_cpp_exports.map`: export exactly `hearth_cohere_backend_v1`, `hearth_funasr_backend_v1`, `hearth_voxtral_backend_v1`.
- `common-jni/src/main/cpp/speech/backends/audar_mtmd_backend.cpp` and `owsm_backend.cpp`: separate narrow adapters.
- `common-jni/src/main/cpp/speech/expansion_versions.h`: new exact source pins, keeping existing backend identities/pins unchanged.
- `scripts/speech/transcribe-cpp.cmake`, `audar-mtmd.cmake`, `owsm.cmake`: private static dependencies behind one small C ABI; no global GGML exports.
- `scripts/speech/build-expansion-runtimes.sh`, `stage-expansion-runtimes.py`, `expansion-runtime-sources.py`: independently attest these new DSOs if extending legacy all-runtime publication would force unrelated rebuilds. Include adapter source, headers, CMake, patch tools, source/dependency pins, compiler/NDK and backend flags in actual compilation snapshots.

A practical first packaging shape is CPU `libhearth_transcribe_cpp.so` with the three entrypoints above, and separate explicit `libhearth_transcribe_cpp_vulkan.so` on arm64. Separate OWSM and Audar DSOs avoid dragging all of audio.cpp or mtmd into unrelated engines. Names are design choices; update loader, export checker, metadata and release verifier together. Do not ship names or metadata until the real binary exists.

The S25's native ABI is arm64. It is acceptable to start new runtime availability on arm64 only while existing models keep working on other phone ABIs. Expose `runtime_unavailable_for_abi` truthfully and disable Use/Install on unsupported ABIs. A CPU probe must not dlopen the Vulkan DSO or initialize a graphics driver.

## Task STT-1: readiness, package identity, and profile contracts

1. Add a preparation/readiness model separate from `SpeechArtifactCatalog`. Suggested states: `RESEARCH_ONLY`, `RUNTIME_BUILD_PENDING`, `INSTALLABLE_UNTESTED`, `DEVICE_VERIFIED`. Include `blockers`, supported ABI set, and actual acceleration choices. `DEVICE_VERIFIED` is a per-evidence designation, not a blanket claim based on a publisher desktop benchmark.
2. Proposed live backend IDs after compilation: `cohere_asr`, `funasr_mlt_nano`, `voxtral_realtime`, `audar_mtmd`, `owsm`. Proposed profile IDs: `cohere-transcribe-arabic-07-2026`, `fun-asr-mlt-nano-2512`, `voxtral-mini-4b-realtime-2602`, `audar-asr-v1-flash`, `owsm-v4-base-102m`. Reconfirm collisions against current source before adding them.
3. Keep artifact quant ID separate from profile ID. Current publisher artifacts often use `profile.id` as artifact identity, and `SpeechDownloads.find` resolves by profile. Supporting both Q4 and Q8 therefore requires explicit artifact IDs and receipt matching; **do not add a second enum profile merely to count a quant as a different model**. Preserve old ID lookup aliases so saved setups do not break.
4. All four self-contained GGUF types use role `model`. Audar uses `model` (decoder) and `encoder` (mmproj), both required and both GGUF. Its tokenizer is embedded in the decoder; extra Hugging Face tokenizer JSON files are not required for the selected native two-GGUF path. The existing Qwen ONNX role set is different and must remain different.
5. `SpeechModelPackage.verify` must reject absent/extra roles, missing or wrong hashes/sizes, links, undeclared files, path traversal and wrong magic just as today. Add exact architecture/variant checks at the native adapter boundary. `GGUF` magic alone is not model-family compatibility.
6. First capabilities: Cohere and Fun-ASR utterance finals; Audar utterance finals; OWSM utterance finals; Voxtral windowed partials under current upstream. All capture remains mono PCM16 at 16 kHz, converted to borrowed float32 for native push.
7. Auto is **not available for Cohere**. Add an explicit capability such as `automaticSourceLanguage` rather than continuing the current universal `source == auto` exception. Shared `SpeechCapabilities`, `SpeechOptions.validate`, Basic bundles, setup validator and language UI must all use it. Source languages and language-forcing input remain distinct. Voxtral accepts Auto only; source-language hints set is empty. A selected hint must never be silently ignored when the UI says it is forced.
8. Preserve detected language as ISO-639-1 (`ar`, `en`) for the independent MT bridge. Unknown stays unknown; mixed speech may remain `mul` only when actually reported. Never invent English or the translation target as the spoken language.

Acceptance: unit tests cover hint/Auto rules for every new profile and unchanged old profiles; a preparation card cannot initiate installation or become a loaded runtime; both Kotlin variants compile with no incomplete exhaustive switch.

## Task STT-2: native Cohere and Fun-ASR utterance adapters

Use [the pinned public header](https://github.com/handy-computer/transcribe.cpp/blob/1dc22facfc931e2f8adb43924b0e38b9c46f83c7/include/transcribe.h), not guessed Python or CLI flags.

1. Initialize every public params/output struct with its `transcribe_*_init` function. Zero `{}` alone is explicitly rejected by this 0.x ABI. Treat the pinned header and linked sources as a unit.
2. Before model load, initialize the permitted backend once with `transcribe_init_backends_ex`; CPU default must allow CPU only. For explicit Vulkan use `TRANSCRIBE_BACKEND_VULKAN`; never use `AUTO` to satisfy an explicit GPU request. The allowed mask is fixed on first registration in a given runtime DSO, which is another reason for separate CPU/GPU DSOs.
3. Call `transcribe_model_load_file`; own its `transcribe_model*`. Check `transcribe_model_arch_string`, `transcribe_model_variant_string` and `transcribe_model_get_capabilities` before creating a session. Expected Cohere: architecture `cohere_asr`, variant `cohere-transcribe-arabic-07-2026`. Fun-ASR: architecture `funasr_nano`, variant `fun-asr-mlt-nano-2512`. Ordinary `fun-asr-nano-2512` must be rejected for the multilingual profile.
4. Set `transcribe_session_params.n_threads` to the profile's bounded CPU budget and call `transcribe_session_init`. Each Hearth instance owns its own upstream model and session. Current upstream forbids concurrent compute across sessions sharing a model; do not introduce a shared-model cache without external serialization.
5. Feed existing `UtteranceSegmenter`, preserving sample clock, preroll and hard max. On emission call `transcribe_run` with the complete bounded utterance, transcription task, no translation, no timestamps, selected source hint. Fun-ASR ITN stays at the documented on/default setting for published parity.
6. On **every** non-OK status, copy status/error/log detail before cleanup and throw through `backend_guard.h`. `TRANSCRIBE_ERR_INPUT_TOO_LONG`, `TRANSCRIBE_ERR_OUTPUT_TRUNCATED`, aborted and backend failure are errors; a preserved partial transcript is not successful complete output. The public API has `transcribe_status_string`, not a `transcribe_last_error` function. If capturing log detail, install `transcribe_log_set` once before first model/thread and use lifetime-safe, bounded storage; do not reconfigure its global callback per session.
7. On success, copy `transcribe_full_text` and `transcribe_detected_language` before the next mutating call. Enqueue a bounded owned result with `is_final=1` and source clock endpoint. Do not return pointers into temporary strings or a mutable next result.
8. Implement reset by clearing the segmenter/result queue and upstream session state, retaining loaded weights safely. Finish flushes the tail once. Free session before model in every construction-failure and destruction path. The surrounding `SpeechNativeSession` already assigns stable utterance/revision IDs; do not increment them in a second unrelated Kotlin counter.
9. `execution_info` must identify requested and resolved backend and CPU frontend work accurately. `transcribe_model_backend` verifies primary runtime choice; if encoder/decoder placement cannot be proven separately, do not claim all operators ran on GPU. Verify again after first inference if materialization is delayed.

Acceptance: host fake-API tests exercise allocation failure at model/session creation, architecture mismatch, forced language, actual errors, repeated finish/reset, monotonic source clocks, silence, split frames, no duplicate final, queue bounds, CPU/GPU mismatch and cleanup. Then run real models through the same ABI/session used by JNI on Arabic and English fixtures, with reference transcripts and recorded parity.

## Task STT-3: real multi-component installation and Audar mtmd

The selected model needs **both** `Audar-ASR-V1-Flash-Q4_K_M.gguf` (396,700,512 bytes) and `mmproj-Audar-ASR-V1-Flash.gguf` (378,575,424 bytes). The encoder must remain BF16. Q8 decoder alternative is 639,442,784 bytes, **1,018,018,208 bytes combined**. Never advertise the decoder alone as total download size.

Installer sequence:

1. Add an explicit pinned component group abstraction rather than abusing one `SpeechDownload.url/sha256`. A component contains stable local filename, role, exact HTTPS URL/revision, bytes, SHA-256 and required flag.
2. Start one install transaction; retain per-component progress and cancellation. Verify each downloaded file in staging. A failed/cancelled second file must never publish the first as a selectable package. Resume only verified complete components, with no API key/header forwarding between hosts.
3. Only when all required components pass, write root `hearth-speech.json` with both role bindings and all asset hashes, run existing `SpeechModelPackage.verify`, and atomically move the complete directory. Pin a trusted receipt outside the imported tree to **the entire component set**, profile and manifest hash. Preserve crash recovery/idempotency and existing archive entry/path limits.
4. Startup or selected-path validation must prove both files still exist and match the registered package. A picker may cache verified inspection safely, but changed file identity/metadata invalidates that cache and triggers full verification before load. A user-selected content URI is not a native filesystem path: copy into app-private staging or preserve an explicit verified import contract.

Native sequence using the existing llama.cpp source pin:

1. Build `llama` + `mtmd` privately in a new speech DSO. `tools/mtmd/models/qwen3a.cpp` exists in that pin. `scripts/translation/CMakeLists.txt` currently disables tools; a new narrow project should explicitly add the mtmd target without changing text MT behavior. Set `MTMD_VIDEO=OFF`, `LLAMA_SUBPROCESS=OFF`, network/OpenSSL/CURL helpers off, examples/tools/server off in the shipping build. No video/FFmpeg functionality is needed for mono in-memory PCM.
2. Own a `llama_model`, `llama_context`, sampler and `mtmd_context`. Initialize the projector with `mtmd_context_params_default`, selected thread budget, and explicit CPU or exact device/GPU mode. `mtmd_init_from_file` takes the BF16 mmproj and the text model. Confirm `mtmd_support_audio` and `mtmd_get_audio_sample_rate==16000`.
3. For a bounded utterance, build an in-memory audio bitmap with `mtmd_bitmap_init_from_audio`; never round-trip recorder audio through a media file. Build the Qwen ASR chat/prompt with an actual audio placeholder from this runtime, tokenize using `mtmd_tokenize`, and evaluate with `mtmd_helper_eval_chunks` or the equivalent direct safe batch sequence. Own/free input chunks and bitmap per request; context/model persist per session.
4. Decode greedily with a bounded token budget. Match the publisher's ASR protocol, rather than generic assistant conversation: forced Arabic uses `language Arabic<asr_text>`; English uses its proper language token protocol. Strip only the protocol prefix, preserve the actual transcript, and map a successful `language None<asr_text>` no-speech verdict intentionally. A generation error or output-token budget exhaustion cannot become empty success.
5. The publisher benchmark uses BF16, `max_new_tokens=256` and `qwen-asr`. Verify selected Q4/Q8 output on Arabic, Gulf/Saudi and English against that reference before claiming quality parity. Keep all weights loaded and clear per-utterance context/KV state; never carry one user's previous transcript as hidden prompt into another session.
6. Start with utterance finals. LocalAgreement-2 is optional later: two consecutive bounded-window hypotheses must agree before a prefix is committed. It repeats inference and is not acoustic cache-aware streaming. Design overlap/dedup and endpoint final semantics before enabling partials.
7. For explicit Vulkan/OpenCL, prove the audio encoder and text decoder's resolved placement separately. The mere presence of GGML GPU kernels does not prove that both worked. Keep CPU default and a visible explicit retry path if the requested GPU backend errors; do not silently switch backend.

Acceptance: component mismatch, missing encoder, swapped encoder, invalid root manifest, interrupted install, receipt spoofing and startup file disappearance fail clearly. Real Arabic/no-speech/code-switching fixtures do not leak `language ...<asr_text>` or assistant scaffold into captions. Existing Qwen ONNX profiles and all independent translation/TTS behavior remain unchanged.

## Task STT-4: bounded Voxtral partials

Use [the pinned extension](https://github.com/handy-computer/transcribe.cpp/blob/1dc22facfc931e2f8adb43924b0e38b9c46f83c7/include/transcribe/voxtral_realtime.h) and the public stream lifecycle. Verify `architecture=voxtral_realtime`, exact variant, supported source coverage, and Auto-only recognition.

1. Load/own model and session as in STT-2. Initialize run, stream and `transcribe_voxtral_realtime_stream_ext` structs via their functions. Confirm extension acceptance with `transcribe_model_accepts_ext_kind`.
2. Expose publisher-validated delay tokens only: **1–15 or 30**, each 80 ms; streaming default 6 (=480 ms). Values 16–29 are invalid even if the graph could execute. Offline runtime default is 30 and the published quant table was measured at 6; parity tests must specify which.
3. Call `transcribe_stream_begin`, `feed`, `get_text`, `finalize`, `reset`. The pinned extension documents that tentative partial decode reprocesses accumulated audio. Bound audio to the existing max utterance window and reset on endpoint/max window. Set a sensible partial cadence such as 500 ms (`min_decode_interval_ms`) to keep repeated work bounded; measure it. Do not call the decoder for every tiny recorder frame.
4. Preserve 20 ms endpoint decisions and adequate onset preroll; do not drop recorder chunks at a reset seam. An incremental extension to the segmenter needs regression tests proving the existing offline segmentation does not change.
5. Read/copy `transcribe_stream_get_text` before mutating again. For authoritative revisions use `full_text`; committed/tentative views have documented divergence cases. Emit partial replacement under the same utterance ID and one final at actual endpoint. Do not re-emit all earlier finalized utterances as each new window arrives.
6. On finalization inspect the error status **and** `transcribe_was_truncated`. Upstream can retain committed text and finalize OK after an absolute stream wall; do not emit incomplete output as an ordinary successful final.
7. Keep raw audio/result queues bounded. Cancellation/Stop discards the tail through close, as the existing API says; explicit Finish flushes once. Reset retains weights, clears stream state and restarts source timeline safely.

Acceptance: test stream lifecycle/callback errors, same-text final after partial, changed final, multiple endpoints within one push, max-window split during continuous speech, reset without final, finish after silence, truncation flag with OK status, bounded queues and close during in-flight decode. Benchmark both Arabic and English on S25 before calling it a better live-overlay default. **Do not set `SpeechStreamingKind.CACHE_AWARE` for this pinned redecoding implementation.**

## Task STT-5: narrow OWSM native runtime

[audio.cpp's OWSM documentation](https://github.com/0xShug0/audio.cpp/blob/673ade451c9cba9c893fb0e606872d9dc44f21e3/docs/models/owsm.md) distinguishes complete-file token output from microphone streaming. Follow that distinction.

1. Use `AUDIOCPP_MODEL_SET=custom`, `AUDIOCPP_MODELS=owsm`. Disable `AUDIOCPP_BUILD_NATIVE_MODEL_MANAGER` (and its networking), services/server, examples, broad model builds and media/CLI helpers. Disable host-native CPU ISA flags for cross compilation, OpenMP and unselected GPU backends. Build only the framework pieces needed by OWSM behind Hearth's private ABI. The public C ABI target defaults shared; statically embed/narrow it where practical and audit exported symbols/dependencies either way.
2. The self-contained GGUF embeds SentencePiece tokenizer, normalization statistics, model spec and configuration. No separate `.pth`, `.npz` or tokenizer download is required for this selected artifact. Do not mix upstream PyTorch conversion files into the Android install.
3. C API path: `audiocpp_registry_create` → `audiocpp_model_load` with explicit family `owsm` → `audiocpp_session_create` for task `asr`, mode `offline`, explicit backend/thread budget. Create a request, `audiocpp_request_set_audio` with copied 16 kHz mono floats, set `language=ara/eng/auto` through `audiocpp_request_set_option`, then `audiocpp_session_run` and `audiocpp_result_text`.
4. `audiocpp_last_error` is thread-local: copy it before cleanup on every failed status. Free result, request, session, model and registry in safe order for each failure path. Preserve model/session as appropriate across utterances; check the chosen API's ownership contracts at the pin.
5. Set `num_beams=1` intentionally for phone latency; original Python's default is 5. Test the exact decoding settings rather than expecting identical text across beam widths. Keep `condition_on_previous_text=false` initially, transcription task only, and windows within 30 seconds. Hearth's current 15-second max is already inside that bound.
6. Map requested `ar`/`en` to `ara`/`eng` only inside this adapter. Map actual detected output back for MT. Do not accept a target translation language through the STT call; OWSM's optional speech translation is not this pipeline's MT stage.
7. Start CPU. Enable Vulkan only after the OWSM operator mix compiles and runs on the Android build with proven placement. General audio.cpp Vulkan support does not prove this model's full graph on Adreno.

Acceptance: source identity/architecture, language mapping, model and request lifetimes, errors, beam setting, bounded final delivery, and real Arabic/English parity. Keep “118 MB download” separate from memory: the conversion maintainer measured roughly 913 MiB peak host RSS in one Q8 CPU test; no S25 RAM result exists yet.

## Task STT-6: catalog, bundles, paths and testing

1. Promote an entry from preparation to installable only after its adapter loads a real verified package and passes host/native gates. Promote device claims only after owner evidence. Status belongs on the card so research models cannot look indistinguishable from usable ones.
2. Add clear source links to original model card, exact conversion artifact tree, runtime source pin, license and score methodology. The Fun-ASR selected GGUF ships FunASR Model Open Source License v1.1 while current upstream HF metadata says Apache-2.0; resolve/retain the applicable weight license rather than silently selecting the permissive label. **OWSM weights are CC-BY-4.0**, independently of audio.cpp's Apache-2.0 runtime.
3. Show complete default download total, required components and optional quant alternatives. Search `Arabic`, `English`, `Gulf`, `Saudi` only when those claims are supported; do not equate a supported Arabic code with measured Saudi accuracy. Use one checkpoint card with quant choices.
4. Basic bundles can suggest Audar/QuickMT/Magpie, Arabic accuracy with Cohere, and an Advanced Voxtral streaming evaluation. These are independent selections and may each be missing or incompatible. Bundle application must show all required downloads, checks and license notices before a single atomic preference change; no silent huge download from browsing.
5. Custom JSON setups reference stable model/checkpoint/artifact identity and component/tree digest. Resolve an existing verified install first; otherwise prepare an exact known download or validated import. Never treat a downloaded config JSON as trusted execution instructions, URL headers, raw native flags, arbitrary class names or a bypass around FOSS/TV policy.
6. Persist each model, source language, acceleration, tuning and path preference through DataStore. Validate selected grants/paths and full model identity on launch/use as the import contract requires. Surface missing/revoked/changed assets with Repair/Choose path and retain the saved preference; do not automatically replace the model or silently download a different one.
7. Keep no original text loss or duplicate finals during independent MT. Test source-language forwarding with Arabic, English, unknown and code-switching. The app's original-while-translation-pending preference must keep its current behavior.

Suggested test additions or extensions:

- `common-jni/src/test/java/com/sal7one/common_jni/speech/SpeechFoundationTest.kt`: profiles, hints/Auto, exact role sets and manifest checks.
- `SpeechDeliveryTest.kt` and `SpeechTranslationTest.kt`: same-text partial→final, unknown/mixed language, native failure propagation, independent MT routing.
- New native fake API fixtures under `common-jni/src/main/cpp/speech/tests/fakes/` plus `transcribe_cpp_backend_test.cpp`, `audar_mtmd_backend_test.cpp`, `owsm_backend_test.cpp`; run under existing ASan/UBSan speech test script. Fake only the upstream engine API, retain production adapters and native owner session.
- `app/.../caption/LocalSpeechModelsTest.kt`: complete/malformed packages, migration and selection; `SpeechTuningTest.kt`, `SpeechAccelerationStoreTest.kt`: per-profile persistence and exact choices.
- `app/.../models/PublisherSpeechPackageTest.kt`, `SpeechCatalogInstallationsTest.kt`, `setup/PublisherSpeechReceiptTest.kt`: true two-component transaction, integrity, crash/cancel/idempotency and receipt binding.
- `app/.../settings/SpeechBrowserPresentationTest.kt`, `models/ModelSourcesTest.kt`, `ModelSourceUrlsTest.kt`: family grouping, supported languages, truthful decode modes, no preparation Install button, valid linked sources.
- `app/.../setup/PortableSetupTest.kt` and `PortableSetupActionsTest.kt`: resolve existing exact package, missing encoder, no partial apply, unknown model ID and TV/FOSS rejection at execution boundaries.
- Native publication Python tests: source change invalidates snapshot, architecture/SONAME/dependency/export/page-size checks, no stale metadata for existing CPU/GPU runtimes.

## Commands and acceptance evidence

Run these from the repository root after relevant implementation, not as proof that this documentation itself added working runtimes. Use JDK 17 and the configured Android SDK/NDK. These first commands exist today:

```sh
python3 -m unittest discover -s scripts/speech -p 'test_*.py'
common-jni/src/main/cpp/speech/tests/run_speech_tests.sh
common-jni/src/main/cpp/common/tests/run_common_utils_tests.sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
./gradlew :common-jni:externalNativeBuildDebug
```

For a new backend's actual runtime recipe, implement and document host/Android CPU and arm64 Vulkan invocations alongside the script. The proposed `build-expansion-runtimes.sh` is not a currently existing command. Compile against the exact header/source pin; record the source snapshot **during compilation**, not publication. Test real model IO through the same `HearthSpeechBackend` and `SpeechNativeSession` used by JNI. The existing host smoke script is a useful starting point; its decoder-format helper must not become an app dependency.

Every shipped new DSO must have verified ABI/SONAME, minimal exact public exports, allowed dependencies, 16 KB load alignment, complete runtime/license notices and a matching actual source/pin/NDK/backend record. Optional GPU DSOs must stay ARM64 only and must not be loaded by CPU availability probes. Update `jniLibs/SHA256SUMS` using the existing helper; never hand-edit a checksum to disguise a stale binary.

Before APK delivery, build both QA variants and run:

```sh
./gradlew :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
```

The owner requested GitHub draft release/tag delivery, not APK attachments in chat. Respect existing signing identity/version rules and the draft release workflow; do not create a new production keystore or replace existing release assets casually. No release was built for this plan.

Owner S25 acceptance matrix, one model at a time and then complete independent bundles:

| Test | Record | Gate |
|---|---|---|
| Cold/warm start | load time, total download, resident memory after load | complete verified package, no unexplained stall/OOM |
| Live microphone | first partial where applicable, first final, end-to-final p50/p95, queue depth/overflow | no duplicate/lost final; accurate streaming label; bounded queue |
| Arabic quality | MSA news, Saudi/Gulf article/conversation, Egyptian speech, English–Arabic switching, noisy speech | transcript/reference differences and exact decoding/quant settings saved; no unsupported quality claim |
| English quality | short/long speech, names/numbers/punctuation | errors compared with existing Qwen/Nemotron/Whisper baseline |
| Silence/non-speech | silence, music, short noises | genuine no-speech semantics, no repetition/scaffold leakage |
| Independent bundle | STT only, STT+MT, STT+MT+TTS | per-stage time/RAM recorded, no cross-stage setting overwrite |
| Explicit GPU | CPU vs Vulkan; OpenCL only if implemented/proven | actual placement diagnostics, parity, memory, driver error surfaced; no silent fallback |
| Sustained use | 10–20 minute session, thermal/memory trend, interruption/background/Stop/reset | stable memory/latency, safe teardown and no UI freeze |
| Restricted device | TV import/apply/load/benchmark/download paths | no local inference, no accidental cloud/FOSS bypass |

Do not infer a speed win from a desktop benchmark or weight size. Keep the new backend available as an explicitly unverified Advanced option until the owner tests it, while preserving the existing flexible pipeline and saved choices.
