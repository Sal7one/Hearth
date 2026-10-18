# Native pipeline performance audit — 2026-10-18

Hearth has avoidable work around its inference engines. A faster model alone
does not remove language detection passes, repeated prompt evaluation, worker
creation, endpoint delays or result polling. This audit starts at the shipped
NDK code and pinned runtime sources; it does not treat desktop throughput as
Android overlay latency.

## Reproducible sources

The research checkouts are under `research/`, which `.gitignore` excludes;
additional source inspection used temporary directories. Upstream installers
and bundled upstream binaries were not executed. These are inspection pins,
not dependency upgrades:

| Project | Revision inspected | Relevant implementation |
| --- | --- | --- |
| whisper.cpp, already vendored | Hearth snapshot `4061a631305d574e70d4f9a2a2cdb7a472caa70f`; upstream commit not recorded | `common-jni/src/main/cpp/whisper/upstream/src/whisper.cpp`, vendored GGML CPU graph execution and ARM kernels |
| llama.cpp, Hearth's production translation pin | `64e9bceb2c3a856efed96feda784a50947049feb` | `src/llama-context.cpp`, `ggml/src/ggml-cpu/ggml-cpu.c`, public threadpool API |
| sherpa-onnx, Hearth's production speech pin | `210f340bcfdfd5b9ad6b24245e77a934d6c28f1b` | `session.cc`, offline NeMo TDT greedy decoder |
| Phonon publisher runtime repository | `cf97fdbedaf5bfd3cc8b88c946e64c0fdc2587b1` | CPU dispatch documentation, Docker recipes and rolling live-session implementation |
| CTranslate2 | `8c8cab2a954810216a45d65c1b8d56839c846e5b` | ARM64 quantized GEMM, greedy decoding and worker budgets |
| Bergamot | `9271618ebbdc5d21ac4dc4df9e72beb7ce644774` | sentence cache, length-based batching and vocabulary shortlist |

The Whisper row identifies Hearth's vendored snapshot, not a whisper.cpp
upstream revision. Its source evidence is the
[tracked snapshot](https://github.com/Sal7one/Hearth/blob/4061a631305d574e70d4f9a2a2cdb7a472caa70f/common-jni/src/main/cpp/whisper/upstream/src/whisper.cpp).

## What Phonon 2 establishes

The model is **Phonon 2**, an English Parakeet TDT derivative. The publisher's
[model card](https://huggingface.co/FermionResearch/Phonon-2/blob/88baeef8afde2d9f410a2d3337f689d632d2d53f/README.md)
reports single-stream speed on an Apple M5 using MLX and separately reports
NVIDIA GPU throughput. Those measurements do not establish Android CPU speed,
time to first caption or the latency of recognition followed by translation.

Phonon also now publishes a
[CPU runtime](https://github.com/fermionresearch/phonon/blob/cf97fdbedaf5bfd3cc8b88c946e64c0fdc2587b1/docs/cpu.md),
with a portable baseline and selected dot-product/i8mm tiers on ARM64. It
should not be dismissed as GPU-only. Its current
[Phonon 2 CPU container](https://github.com/fermionresearch/phonon/blob/cf97fdbedaf5bfd3cc8b88c946e64c0fdc2587b1/docker-cpu-phonon2/Dockerfile)
installs `fermion-research==0.2.11`, PyTorch and Transformers; it sets
`OMP_WAIT_POLICY=PASSIVE`. The supported CPU platforms do not list Android,
and that container is not a portable NDK library integration.

Hearth already has a Phonon backend. Its host exporter reconstructs the packed
five-value weights and produces int8 ONNX encoder, decoder and joiner graphs.
It does not execute the publisher's packed MLX or CUDA kernels. A compact
download and fast execution are separate properties. The existing
[adapter record](native-speech-phonon-2026-09-30.md) describes the conversion
and previous smoke observations; those observations are not phone benchmarks.

The architecture is still useful: TDT predicts how many encoder frames to
advance, reducing decoder steps. The pinned
[sherpa decoder](https://github.com/k2-fsa/sherpa-onnx/blob/210f340bcfdfd5b9ad6b24245e77a934d6c28f1b/sherpa-onnx/csrc/offline-transducer-greedy-search-nemo-decoder.cc)
implements token/duration splitting and progress guards. Hearth uses this
decoder through the existing speech ABI. Its Phonon integration is
utterance-based: it waits for the segmenter to emit audio. It is not a
cache-aware online recognizer merely because microphone input is continuous.

The publisher's public
[rolling live-session code](https://github.com/fermionresearch/phonon/blob/cf97fdbedaf5bfd3cc8b88c946e64c0fdc2587b1/docker-phonon2/_live.py)
also re-transcribes the accumulated segment for each partial. Its early
partial schedule does not establish retained encoder state. This is useful
for a very fast engine, but copying it to a slower phone backend could
multiply work during long speech.

## Verified avoidable work

### Translation retains a CPU pool and reuses safe prompt prefixes

Before this change, `translation/text_model.h` kept a persistent llama
context but never attached a threadpool. At the exact production pin,
`llama_context::graph_compute` passes the nullable pool into the CPU backend;
`ggml_graph_compute` then creates and frees a disposable worker pool for each
graph. Autoregressive translation calls decode repeatedly, so this is repeated
setup work inside a single sentence, not just model loading.

The supported
[public API](https://github.com/ggml-org/llama.cpp/blob/64e9bceb2c3a856efed96feda784a50947049feb/include/llama.h)
allows an owned pool to be attached to the existing context. Its default
polling level is 50; level 0 puts idle workers to sleep. A persistent pool with
polling disabled removes creation churn without leaving extra busy waiters
competing with recognition. Pool lifetime must outlive its attachment, and
context teardown must happen before pool release. `TextModel` now owns one
two-thread pool with `poll=0` for both prefill and generation, detaches it on
teardown and destroys the context before freeing the pool. This preserves model
selection, prompts, sampling and the STT → translation → TTS composition.

Fixed chat markers are tokenized once. For models without sliding-window
attention, the wrapper retains only a matching prompt prefix and removes the
previous generated output and changed suffix before the next request. It
always decodes at least the final prompt token again to obtain fresh logits.
Unusable caches, unsupported suffix removal and failed/aborted decoding fall
back to cleared state. Captions gain no conversation history. Gemma models
with sliding-window attention retain full prefill until eviction-aware reuse
has model-level parity evidence.

### Marian keeps decoder tensor ownership across tokens

`marian/marian_decoder.h` now passes owned ORT output values directly into the
next decoder call. Initial encoder cross-attention projections remain fixed;
empty encoder presents from cached calls are released rather than replacing
them. Token, mask, hidden-state and branch views and the logits output are
created once per caption. Greedy selection reads the logits directly and still
masks the padding token. This removes wrapper-side KV/logits copies and
repeated wrapper construction; ORT still allocates and computes graph outputs.
Cancellation, token limits and actual provider failures remain failures, and
partially allocated outputs are released on every exit path.

### Several speech ONNX sessions can compete while idle

The pinned sherpa session factory configures per-session worker counts but
leaves ONNX Runtime's spin settings at their defaults. A TDT recognizer has
separate encoder, decoder and joiner sessions, so one configured count does
not mean one process-wide pool. Hearth's build recipe now adds explicit
intra-op and inter-op `allow_spinning=0` under `HEARTH_SPEECH_CPU_ONLY`.
Workers sleep between jobs while thread budgets, providers and model graphs
stay unchanged. The pinned source anchors are counted before patching;
runtime rebuilds and source/binary checksum records must accompany the change.

Marian, OCR and Supertonic already disable idle spinning in their local ORT
configuration. The generic ONNX and VITS wrappers remain separate candidates
for the same policy; this audit does not claim they have been changed.

### Whisper's first auto-language call can do no transcription

In the pinned Whisper implementation, a null/automatic language already
triggers detection. `detect_language=true` additionally requests an immediate
return after detection. Hearth had set that flag for the first automatic
language call, then compensated for the empty transcript with another pass.
The corrected request uses automatic language with the detection-only flag
disabled, so the first call can return text. Upstream still performs its
language-detection encoder work; this does not eliminate detection itself.
Legitimate empty output is no longer retried with a forced-speech threshold.

### Whisper streaming context and worker scheduling need separate controls

The mel hop is 160 samples at 16 kHz, but the audio encoder subsamples by two.
`audio_ctx` counts encoder positions, not mel frames. The old streaming
calculation used the mel count, potentially asking the encoder to process
roughly twice the necessary context. Hearth now uses one position per 320
samples (20 ms), retaining the 224-position minimum and 16-position padding.
A six-second window requests 316 positions instead of 616; batch mode retains
the full default context. These are work dimensions, not a measured speedup.
Shorter acoustic context still needs owner device quality comparisons.

The old wrapper also silently increased a configured two-worker budget to at
least four. That works against simultaneous translation on heterogeneous
phone CPUs. Honoring the configured budget is more flexible than forcing a
single maximum for every engine. The wrapper now respects a configured 1–8
threads. Its condition-variable predicate observes accepted-input revisions,
removing an unconditional 200 ms wait that ignored audio-push notifications.
Partial cadence follows the source sample clock rather than ring size, so
rolling discards and reset cannot suppress the next useful partial.

### Utterance segmentation avoids sample-by-sample staging

Complete 20 ms frames are analyzed directly, sub-frame tails use fixed storage,
and pre-roll uses a fixed 200 ms ring. The utterance buffer keeps its reservation
across speech onsets. Chunk-size-independent gate decisions, chronological
pre-roll and continuous-speech splits remain covered by waveform tests.
After the last active frame, the decoder retains 200 ms of context instead of
the whole endpoint wait: the default 600 ms endpoint therefore sends 400 ms
less trailing audio through offline Sherpa decoding. Event timestamps still
include that wait, and internal pauses remain intact. The gate uses energy,
not a neural VAD; quiet endings and recognition quality require owner checks.

### Engine compute time is only part of overlay delay

The modern local result path now awaits a conflated change revision from
`LiveSpeechProcessor`; finals remain in its separate lossless queue. The 100 ms
timeout refreshes queue/compute metrics, and the destructive `snapshot()` runs
outside that timeout so cancellation cannot consume a final unseen. The legacy
Whisper display poll changed from 700 ms to 100 ms and still only reads the
native worker's published result. `SpeechSession` also bulk-copies PCM16 into
its reusable direct buffer instead of writing one sample at a time.
Windowed engines still require their configured audio window or silence
boundary. Thread and delivery improvements cannot turn an offline architecture
into genuine online decoding.

## Build audit: QA is already optimized

`app/build.gradle.kts` initializes `qa` from `release` and falls back to the
library's release variant. The generated arm64 native build is
`RelWithDebInfo`; its actual `build.ninja` compiler command ends in `-O3`.
Debug builds are explicitly `-O0`. The translation and speech plugin scripts
also request `Release`. Therefore the owner-facing QA slowdown should not be
explained as an unoptimized native build without inspecting the delivered
artifact.

Whisper's wrapper does bypass upstream CMake and recursively compiles GGML
sources into one CPU backend. The portable arm64 build does not select
optional dot-product, FP16-vector or i8mm variants. This is a real future
optimization opportunity. However, the pinned upstream
[vendored `GGML_CPU_ALL_VARIANTS` implementation](https://github.com/Sal7one/Hearth/blob/4061a631305d574e70d4f9a2a2cdb7a472caa70f/common-jni/src/main/cpp/whisper/upstream/ggml/src/CMakeLists.txt#L341)
requires dynamic backend libraries and shared-library packaging. Enabling a
global newer `-march` would instead raise the minimum CPU requirement and
could crash older phones or TVs. Neither change is a safe blanket flag flip.

## Useful projects without another runtime migration

[CTranslate2's decoding advice](https://github.com/OpenNMT/CTranslate2/blob/8c8cab2a954810216a45d65c1b8d56839c846e5b/docs/performance.md)
supports bounded thread budgets and avoiding scores/softmax when greedy
decoding does not consume them. Hearth's Marian decoder retains greedy
selection while improving wrapper cache ownership as described above.
CTranslate2 also has
[ARM64 int8 support through Ruy](https://github.com/OpenNMT/CTranslate2/blob/8c8cab2a954810216a45d65c1b8d56839c846e5b/docs/quantization.md).
That is a credible future comparison, not evidence that adding its runtime
will beat the current ONNX models on an owner device.

[Bergamot](https://github.com/browsermt/bergamot-translator/blob/9271618ebbdc5d21ac4dc4df9e72beb7ce644774/src/translator/translation_model.cpp)
uses sentence caching, length-based batching and vocabulary shortlists. Exact
sentence reuse is useful for repeated captions. Large batches improve file
throughput but can delay the first live translation. Vocabulary reduction
requires compatible model data and accuracy validation; it should not be
invented from a language label.

## Real-model diagnosis: a missed Hunyuan end token

A controlled two-thread CPU run of the exact catalogued experimental Hy-MT2
Q2_K artifact (`dced2d16784aaf339cab86a469ec3a6963111b200d863ed345a2380842b88ce7`)
exposed a correctness cause of apparent slowness. Its GGUF metadata declares
EOS token 3, an ordinary `$` token. Its embedded chat template and the pinned
[Hunyuan chat implementation](https://github.com/ggml-org/llama.cpp/blob/64e9bceb2c3a856efed96feda784a50947049feb/src/llama-chat.cpp#L841)
instead end assistant output with the control token
`<｜hy_place▁holder▁no▁2｜>` (120020 in this artifact). Generic EOG discovery
in the pinned runtime did not recognize that marker when metadata pointed
elsewhere. Official Tencent Hy-MT2 Q4_K_M metadata correctly names 120020 as EOS.

For `Hello.` → Arabic, the diagnostic trace emitted 120020 at sampled index 9,
but the old stop condition ignored it. Generation continued until the 384-token
cap failed after about 11.5 seconds on the host. Both the original and first
pool/cache wrapper exhibited this failure. Worker/prefix optimizations alone
could not repair the stop condition, and the capped run is not a valid completed
translation benchmark.

The Hunyuan adapter now resolves the exact assistant-end marker once, accepts
it only as a single control token with matching text, and stops on its token ID.
It also refuses to treat an ordinary token such as `$` as EOS merely because of
malformed Hunyuan metadata. Caption input remains literal; output substrings
are not stop rules. If that named marker is absent/invalid, a valid control
EOS/EOT remains usable; without either, model setup fails clearly. Gemma retains
its existing EOG handling. Empty/incomplete translations still fail visibly.
The final wrapper passes 1,785 ASan/UBSan checks.

With that fix, all four exact-Q2 host smoke requests completed. `Hello.` →
Arabic returned in 807 ms, but its text was malformed (`مرحب بالضواحh.`).
Arabic `السعر هو $5.` → English returned `The price is $5.`, preserving the
ordinary dollar token. The old 11.5-second capped failure and new completed
request are different outcomes, so their durations are not a speedup ratio.
This establishes repaired termination and a remaining Q2 quality problem;
it does not validate Arabic accuracy or Android latency.

## Controlled Q4 host comparison

The final comparison used official Hy-MT2 Q4_K_M, SHA-256
`dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`, on an
Apple M4 Pro running macOS 26.5. Both wrappers used the same pinned llama.cpp,
two CPU threads, zero GPU layers, Metal disabled and Accelerate disabled.
The run order was before/after/after/before (ABBA), with a fresh process for
each session. Each session excluded one `Hello.` warmup and measured five
English→Arabic requests drawn from three distinct captions.

| Warm request group | Observations per wrapper | Before median | Final median | Time reduction |
| --- | ---: | ---: | ---: | ---: |
| All measured requests | 10 | 444.662 ms | 270.044 ms | 39.27% |
| Changed caption, shared instructions | 8 | 444.740 ms | 270.375 ms | 39.21% |
| Same caption as the immediately preceding request | 2 | 444.207 ms | 225.194 ms | 49.30% |

These are completed-request host measurements for the combined wrapper changes,
not isolated pool/cache attribution or Android overlay latency. Three unique
captions and two identical-prompt observations cannot establish performance
across languages, long inputs, device contention or thermal load.

Outputs were not universally byte-identical. For `The train arrives in ten
minutes.`, the baseline returned `القطار سيصل في عشر دقائق.` and the final
wrapper returned `القطار يصل في عشر دقائق.`; both preserve the source meaning.
A current-wrapper diagnostic with prefix reuse disabled restored all six
baseline outputs. The pinned
[llama.cpp cache documentation](https://github.com/ggml-org/llama.cpp/blob/64e9bceb2c3a856efed96feda784a50947049feb/tools/server/README.md#L590)
warns that prompt reuse with different batch sizes can change logits/results.
That supports treating greedy wording as variable; it does not prove the exact
numeric cause of this observed difference. Four additional distinct validation
cases covering negation, Ada, `$5`, 12 tickets, 9:30 and room 204 returned
byte-identical baseline/final output. This small check is not a multilingual
quality benchmark or a guarantee of unchanged wording.

Raw records and the harness remain in ignored `build/performance/`:
`controlled-q4-final-results.json`, `configuration-q4-final.json`,
`q4-no-prefix-diagnostic.json`, `q4-validation.json`, `q2-final-smoke.json` and
`run_controlled_q4_final.py`. The baseline wrapper SHA-256 is
`a8c4f0d1c3478280228ec54032d1b54a381792c7306254f4099e71f1a4be8f3e`;
the final wrapper is
`6c900237a3e5b8089dbc5de935532bdb3d7a884562c57dceb4759ebf8c2de0db`.
Model weights remain separate from source and APKs.

## Validation status

All four final Android speech and translation runtime ABIs were rebuilt and staged,
with source/binary digests recorded in their runtime-build manifests and
`jniLibs/SHA256SUMS`. The pinned runtime revisions and public ABIs are unchanged.
The following checks passed:

- ASan/UBSan: 76 speech-session/segmenter checks, 144 production-Whisper stub
  checks, 1,785 final `TextModel` checks, the Marian tokenizer/decoder suite and the
  common utility suite, including its real-JVM JNI checks.
- `SpeechDeliveryTest`: six tests covering immediate wakeup, cancelled waits,
  repeated/distinct finals, empty updates, completion/close/failure and a
  completed final queued before a later failure.
- The Whisper dispatch regression fails when only the old stop-only wait
  predicate is restored, confirming it detects the fixed polling delay.

The final full Gradle test, both QA Kotlin compilations, native Debug build and
both QA APK variants passed after the Hunyuan fix. `verify-release.py` passed
for all 10 produced APKs. All six `SpeechDeliveryTest` cases passed in debug
and release. Host stubs verify scheduling, cache/state ownership and failure
behavior; they do not execute real model graphs. The separate Q2 smoke and Q4
comparison above use actual weights, without establishing Android throughput
or a multilingual quality matrix.
Device quality, thermal behavior and simultaneous speech/translation/TTS load
remain owner checks.

## Deferred until measured

- Optional ARM kernels with runtime CPU dispatch and preserved baseline ABI.
- Whisper CPU flash attention: upstream supports the operation, but a flag
  change still needs real-model transcript and device speed comparisons.
- Shared prompt KV reuse for sliding-window models. Upstream maintains
  eviction-aware checkpoints; naive prefix reuse can retain incomplete state.
- An additional translation runtime or a new speech model. Existing imports,
  integrity checks and independent engine routing remain the integration path.

For device evaluation, record model/checksum, ABI, build type, worker counts,
cold load, warm inference, first partial, final caption, translation completion,
peak memory and sustained-session behavior separately. Compare identical audio
and language routes. A lower word error rate or BLEU/chrF change requires a
reference corpus; neither a successful build nor a faster synthetic operation
establishes better language quality. No Android latency or quality improvement
percentage is claimed by this source audit.

### Adreno translation backend (October 19 follow-up)

The separately loaded ARM64 `libtransiber_translation_opencl.so` adds llama.cpp's
Adreno-specific OpenCL kernels alongside CPU and Vulkan. The llama revision stays
`64e9bceb2c3a856efed96feda784a50947049feb`; Khronos OpenCL-Headers is pinned to
`30bc20a8e90468e231d7c639805ae61ad1fefa4f` (September 30, 2026). No device driver
or proprietary Qualcomm binary kernels are bundled. A generated, typed API shim
uses only `dlopen("libOpenCL.so")` in the app's permitted Android linker namespace;
it does not probe private vendor paths. An optional manifest library declaration
allows a vendor to expose its public driver without making that driver necessary
for install, CPU, Vulkan, Android TV, or other ABIs.

The runtime requires an accessible Adreno GPU, available compiler and FP16. It
records the actual device, OpenCL version, driver, nonzero model layer placement
and positive OpenCL **model** buffer allocation before reporting OpenCL execution.
Unsupported operations can remain on CPU. Q4_K weights are supported by the
pinned backend; enabling the setting alone never establishes acceleration. The
S25 Ultra's Adreno 830 matches an upstream verified GPU family. S22 Ultra variants
need device validation: Snapdragon Adreno 730 is recognized by the pinned code but
is not in its verified-device table; Exynos variants should use Vulkan or CPU.

The upstream command-line backend exits or asserts for OpenCL driver/compiler
failures. A recorded build-only transformation converts those sites into JNI
exceptions and keeps cleanup nonthrowing. Actual kernel build logs are preserved
with the error. User action chooses CPU after a failure; there is no silent CPU
fallback. Kernel source is embedded and JIT compiled by the device driver. An
app-private cache directory reuses driver binaries on later loads; the native
cache key includes kernel source, options and driver/device identity. CPU remains
the default and never initializes the OpenCL DSO.

Validation: Android ARM64 native compilation; no OpenCL DT_NEEDED dependency;
only the seven private JNI entry points are exported; all four CPU ABIs and the
optional Vulkan plugin rebuilt/staged with current provenance; production-wrapper,
Vulkan placement and OpenCL placement host suites pass. Ten real dynamic-loader
checks against a controlled fake driver plus an absent-driver case verify typed
forwarding, capability failures and actionable loader errors without process
termination. Host validation is not an Android GPU latency or quality result.

Primary sources: [pinned upstream OpenCL documentation](https://github.com/ggml-org/llama.cpp/blob/64e9bceb2c3a856efed96feda784a50947049feb/docs/backend/OPENCL.md),
[Android optional native libraries](https://developer.android.com/guide/topics/manifest/uses-native-library-element).
