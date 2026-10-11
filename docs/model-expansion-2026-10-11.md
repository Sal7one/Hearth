# Speech and translation expansion — October 2026 draft

Checked against publisher/model-runtime sources on 2026-10-11. This document separates
implemented adapters and downloaded, verified artifacts from research candidates.
A quantization is a variant, not a new model family. No phone benchmark or universal
accuracy claim is implied by a host smoke test.

## Implemented speech families and variants

| Family / artifact | Coverage and contract | Download | License / primary evidence | Hearth evidence |
|---|---|---:|---|---|
| Parakeet TDT 0.6B v3 INT8 | 25 European languages, including English and Russian; explicit source declaration, no forced-language decoding; windowed final results | 487,170,055 B | CC-BY-4.0; [NVIDIA model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), [sherpa NeMo export/usage](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html) | New production C ABI adapter over the pinned sherpa runtime. Publisher English fixture decoded exactly through SpeechNativeSession. No Arabic or Chinese capability is advertised. |
| SenseVoice Small original INT8 | Chinese, English, Cantonese, Japanese, Korean; automatic detection or forced source; windowed finals | 163,002,883 B | Apache-2.0; [original model card](https://huggingface.co/FunAudioLLM/SenseVoiceSmall), [runtime artifacts](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html) | Production native English and Chinese fixture decodes. Known language tokens normalized to ISO codes so automatic translation receives actual source evidence. This is the default artifact within the new SenseVoice family. |
| SenseVoice Small Yue INT8 | Cantonese-focused WenetSpeechYue fine-tune; publisher retains five language modes | 165,783,878 B | Apache-2.0; [fine-tune publisher](https://huggingface.co/ASLP-lab/WSYue-ASR), [runtime notes](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html) | Adapter exercised with actual weights. English fixture auto-detected Cantonese and had poor transcription. Advanced regional choice, not the multilingual default. |
| SenseVoice Small Yue FP32 | Same regional checkpoint, unquantized alternative | 886,547,748 B | Same fine-tune and runtime sources | Actual Cantonese fixture matches INT8 transcript through production native inference. Advanced choice; larger storage does not establish better general-English accuracy. |

The original SenseVoice model is from 2024. Its selected INT8 archive was published
in 2025; the 2025-09-09 archive is a distinct Cantonese fine-tune. These are not 2026
model releases. Parakeet v3 is also a 2025 model. Existing Qwen3-ASR, Nemotron,
Moonshine, Omnilingual, Phonon, Whisper and Vosk integration remains intact.

All six sherpa-family backend exports share one maintained private runtime:
Qwen3-ASR, Moonshine, Omnilingual CTC, Phonon, Parakeet and SenseVoice.
The C ABI version and bounded session/event contracts are unchanged. Package-role
validation distinguishes token/model pairs from encoder/decoder/joiner bundles.
Android arm64-v8a, armeabi-v7a, x86 and x86_64 require their own rebuilt binary,
export list, ELF machine type, 16 KiB alignment and recorded digest. Download pins
live in SpeechDownloads; runtime source and ABI digests in runtime-build.json.

All four new speech archives were downloaded and checked against these pins:

| Artifact | SHA-256 |
|---|---|
| Parakeet v3 INT8 | `5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf` |
| SenseVoice original INT8 | `7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e` |
| SenseVoice Yue INT8 | `7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63` |
| SenseVoice Yue FP32 | `bce28acdb0fb3a5b1e0b93f8b202e904ea1d2eca4f3a4c0cd5006626f10d540e` |

## Compact translation implemented in this draft

Five new verified OPUS-MT Marian INT8 directions extend three existing directions:
Arabic → English, English → Russian, English → Chinese, English → Spanish, and
Spanish → English. There are eight direct directions and twelve explicitly
selectable two-model English-pivot routes. A pivot loads two sessions and can
compound translation errors; it is never a silent substitution for the selected
model. Packages are approximately 244–253 MB per direction, downloaded as four
pinned files rather than every quantization in a model repository.

These are additional useful directions in an existing older family, not a claim
that OPUS-MT is a newly released architecture. Base Helsinki weights are Apache-2.0;
ONNX conversion metadata is CC-BY-4.0. Sources: [Arabic → English](https://huggingface.co/onnx-community/opus-mt-ar-en),
[English → Russian](https://huggingface.co/onnx-community/opus-mt-en-ru),
[English → Chinese](https://huggingface.co/onnx-community/opus-mt-en-zh),
[English → Spanish](https://huggingface.co/onnx-community/opus-mt-en-es),
[Spanish → English](https://huggingface.co/onnx-community/opus-mt-es-en).
Immutable revisions, individual bytes/digests and the final tree digest are in
MarianPackage. Every new file was downloaded and SHA-256 verified before native
inference. FOSS supports local imports; app downloads stay behind ByokPolicy.

| New direction | Immutable conversion revision | Verified installed-tree SHA-256 |
|---|---|---|
| ar → en | `3302f1bd7f2c38f272c73a2b82345fae3bea35ce` | `b9f5b17416e1735a66433311ab72b2c52910dbe14a44eb8b6f7ad6ed49416aa3` |
| en → ru | `c6967b328d292f9f3d14bab6e9de40f9bd367114` | `fc29762221d09e2ee59866c7c3585e7c3b8daf0d229088f3bd0309bfbae0d80d` |
| en → zh | `4dc37afc6deb160f4c9048260d48019ee497658e` | `ac77da24d00301dea71b15773b5bffb287a8d8667f53079a20dbc9fdc2d25ce9` |
| en → es | `b5eba94a023a1954c90401b43537f479f962981d` | `38367778c3b0bd8044ca54625efa602e3458c585ca240580bc0faa5d25c4a978` |
| es → en | `0ca6ea53b39356aece8a15bd92eaf7bb40035ea6` | `9a0f9cb3fc2b4112e3e54a692fe61fe2089d74aab64acdfcb7f67e0cc9dbb27a` |

A real production MarianEngine/MarianTokenizer smoke probe, two CPU threads on
this Apple Silicon Mac, yielded:

| Direction | Input | Actual output | One short-fixture inference |
|---|---|---|---:|
| ar → en | أحتاج إلى تذكرتين إلى الرياض. | I need two tickets to Riyadh. | 353 ms |
| en → ru | I need two tickets to Riyadh. | Мне нужно два билета до Эр-Рияда. | 455 ms |
| en → zh | I need two tickets to Riyadh. | 我需要两张去利雅得的机票 | 282 ms |
| en → es | I need two tickets to Riyadh. | Necesito dos entradas para Riad. | 396 ms |
| es → en | Necesito dos billetes para Riad. | I need two tickets to Riyadh. | 301 ms |

Chinese output inferred airplane tickets from an ambiguous source; the Spanish
word choice is also context-sensitive. These probes establish executable runtime
compatibility, not translation quality or on-phone latency. Reproduce with
`scripts/translation/run-marian-host-smoke.sh VERIFIED_MODEL_DIRECTORY SOURCE_TEXT`.
The script downloads nothing and compiles the exact production engine. Compare
quality on the same input set/device in Hearth Benchmark before choosing a default.

## Newer, genuinely small dedicated translation candidates

| Candidate / publication window | Verified primary claim | Format / license | Current decision and required work |
|---|---|---|---|
| [TranslatePsy-EuroNano](https://huggingface.co/qvac/TranslatePsy-EuroNano), Sep 2026 | 17M/31M/43M; 17/31/42 MB INT8 per direction; English ↔ nine European languages | Marian/Bergamot SSRU; INTGEMM binaries + SentencePiece; Apache-2.0 | Strong compact European candidate. It is **not an ONNX drop-in** for Hearth's Transformer-decoder MarianEngine. Needs a bounded Bergamot C++ adapter, all-four-ABI build, manifest roles and real sentence probes. No Arabic/Russian/Chinese coverage. |
| [TranslatePsy-AfriSLM 0.8B Q4](https://huggingface.co/qvac/TranslatePsy-AfriSLM-0.8B-Q4-GGUF), Aug–Sep 2026 | Approximately 672 MB Q4_K_M, English and 19 African languages | Qwen3.5 decoder, GGUF; Apache-2.0 | Not advertised as working. Hearth TextModel currently accepts only gemma3/hunyuan-dense. Requires a Qwen3.5-compatible runtime, publisher prompt, controlled evaluation and language contract. No Arabic coverage claimed. |
| [Hy-MT1.5 1.8B 1.25-bit](https://huggingface.co/tencent/Hy-MT1.5-1.8B-1.25bit), Apr–May 2026 | Publisher 440 MB; 33 languages, including Arabic; dedicated MT | Custom STQ1_0 kernel, not ordinary Q2/Q4; model terms need separate review from compression-code license | Compact Arabic candidate. Hearth's pinned llama.cpp 64e9bceb has no STQ1_0 symbols. Publisher directs users to [kernel PR 22836](https://github.com/ggml-org/llama.cpp/pull/22836). First prove kernel/quant support across four ABIs and preserve cancellation/source-integrity checks. |
| [Hy-MT1.5 1.8B 2-bit](https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-2bit-GGUF), Apr 2026 | Verified actual 600,535,360 B artifact, Arabic included | Publisher Q2_0c [kernel PR 19357](https://github.com/ggml-org/llama.cpp/pull/19357); separate weight-license review needed | Downloaded and tested: production TextModel fails to load. Actual tensor type IDs collide with the current runtime's NVFP4 type. Not added to the catalog. A compatible conversion plus reference parity, or a reviewed compatible runtime, must pass before adoption. |

Publisher benchmark scores, archive bytes, installed size, peak RAM and total
pipeline RAM are distinct quantities. No combined-under-1-GB claim is supported
by parameter count or file size alone. The new research candidates stay out of
the runnable catalog until actual weights pass production inference.

The 2-bit failure is reproducible with the existing production
`translation_smoke` target at llama.cpp `64e9bceb2c3a856efed96feda784a50947049feb`.
Downloaded revision `ef3a410f443c23a155e563814defc5a933a3a35c`, SHA-256
`46e587d917fc4f6f5bda602f10c4a53da8840240355824bcc0e227559c9cba3e`.
GGUF v3 contains 354 tensors: 129 F32, one type 14, and 224 type 40.
Our ggml defines type 40 as NVFP4 and Q2_0 as type 42. Native error, verbatim:

```text
gguf_init_from_reader: tensor 'blk.0.attn_k_norm.weight' has offset 203248672, expected 203572256
gguf_init_from_reader: failed to read tensor data
```

Having a Q2_0 kernel is insufficient for this older custom GGUF format. Do not
edit type IDs or weaken the loader: a numeric enum match alone does not prove
identical block encoding or accurate translation. Preserve the publisher file
digest and obtain conversion/reference parity before introducing a new artifact.

## Further speech candidates

| Candidate | Primary evidence | Decision |
|---|---|---|
| [Cohere Transcribe Arabic July 2026](https://huggingface.co/CohereLabs/cohere-transcribe-arabic-07-2026) | Apache-2.0, 2B Conformer encoder/Transformer decoder, Arabic dialects + English/code switching | Valuable Arabic quality candidate, but no tested Hearth adapter or pinned mobile quant package. Larger than the compact additions; actual quant bytes and peak RSS need measurement. Never infer a sub-1-GB ready model from four-bit parameter arithmetic. |
| [Qwen3-ASR 1.7B](https://huggingface.co/Qwen/Qwen3-ASR-1.7B) | Existing importer/runtime family supports the selected architecture; official model covers 30 languages plus Chinese dialects | Existing import profile retained. One-click download requires a complete verified compatible conversion, package pin and mobile memory/latency evidence. Dialect count is not 52 distinct languages. |
| [Nemotron 3.5 ASR](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) | Current official GGUF is Q8_0 | Preserve the working pinned package. No fabricated official Q4 option; changing a model revision requires tokenizer/layout and runtime tests. |

## Validation and limits

Host validation in this draft: speech_test 72 checks under ASan/UBSan; package
Python suite 8 tests; real native Parakeet/SenseVoice/Marian weights as above.
Parent integration records Gradle/common suites, native Android builds, APK checks
and outstanding app tests in the draft evidence bundle. No adb, phone hardware,
paid cloud requests or thermal benchmark was used in this model-expansion task.

Both speech DSOs were actually rebuilt and staged for all four Android ABIs,
including unchanged Nemotron, so their shared source-set provenance describes
the current build. Final `verify-android-runtimes.py` passed for each architecture:
ELF machine/class, permitted dependencies, exact six sherpa/one Nemotron exports,
SONAME and 16 KiB LOAD alignment. All eight staged hashes, byte sizes and source-set
digests were independently reconciled with runtime-build.json and current sources.
This validates packaging; it does not prove loading or inference on physical TVs.

Parakeet publisher English fixture through the production session path:
`Ask not what your country can do for you, ask what you can do for your country.`
Exact result; load 13.65 s and inference 3.87 s for 3.85 s audio in this concurrent
host build environment. These figures are not comparable to a controlled phone
benchmark or the earlier Phonon probe.

The 5.329-second Parakeet Spanish publisher fixture exposed a useful windowing
tradeoff: the normal 4-second cutoff split its last words into a short fragment,
decoded incorrectly as `To porto país.` With an explicit 15-second maximum in the
host probe, it remained one utterance and decoded `No preguntes qué puede hacer tu
país por ti, pregunta qué puedes hacer tú por tu país.` App defaults remain unchanged.
Reproduce with `scripts/speech/run-host-smoke.sh PACKAGE_DIR es.wav es 15000`;
the optional maximum passes through the same strict SpeechConfig validation.
Responsive windows can reduce latency and hurt recognition around boundaries;
evaluate both when selecting a windowed model. This probe does not establish
real-time throughput or accuracy on an Android phone.

SenseVoice original English: `The tribal chieftain called for the boy and presented
him.` then `50 pieces of gold.`; correctly reported en; 0.516 s inference for
7.152 s audio. Chinese recognized the opening-hours phrase and reported zh; an
initial short silence fragment emitted `.` with en. This is an observed false
positive, not hidden as success. Existing benchmark silence fixtures can expose
it. Cantonese-tuned English auto-detection was wrong; advanced status makes that
tradeoff visible before use.

The Yue INT8 and FP32 variants, with declared yue, both decoded the publisher
Cantonese fixture as `呢几个字都表达唔到我想讲嘅意思`. FP32 took 0.675 s and
INT8 0.977 s for 5.148 s audio in separate concurrent-host probes. This single
sample does not rank quantizations; benchmark multiple identical fixtures on the
target phone. Neither variant has been tested on Android hardware in this task.

Speech and translation are independently optional. Benchmark source coverage is
read from model capabilities; unsupported Arabic/Chinese on Parakeet or Arabic on
SenseVoice does not appear as a successful option. Preset candidates are starting
points, not measured rankings: saved comparisons on the same phone/input determine
best speed, quality and balance.
