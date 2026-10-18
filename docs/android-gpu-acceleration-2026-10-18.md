# Optional Android GPU engines — 18 October 2026

Hearth adds explicitly selected **Vulkan** and **Adreno OpenCL** paths for local
GGUF translation and TranscribePsy speech, plus **Vulkan Whisper**, on compatible
ARM64 Android devices. CPU remains the default. This applies to
Index-Translate, Hy-MT, MiLMMT and TranslateGemma through the existing translation
adapter; it does not replace their model files, prompts or language coverage.

## Using it

Enable **Advanced** mode. In **Models → Translation**, expand translation
acceleration and select **Vulkan** or **OpenCL**. Speech acceleration is selected
independently per supported engine under **Models → Speech → Installed**. Restart an active caption session to use the new
choice. Newly opened typed, conversation and camera GGUF translators capture the
same setting. Speech, TTS, cloud translators, Marian and ML Kit retain their own
independent runtime choices. Basic bundles keep safe CPU defaults; applying a
bundle does not silently turn on GPU.

The application reports the actual GPU name, the loader's layer assignment and
positive device-model-buffer allocation. Unsupported operations can still execute
on CPU. The layer assignment message is derived from the requested placement; it
is not an independent tensor-by-tensor or operation-by-operation GPU measurement.
A requested layer count or the presence of a Vulkan library alone is not accepted
as successful acceleration. No compatible GPU, allocation failure or invalid model
produces a visible error. Typed and quick translation can explicitly switch to
CPU and retry; the runtime does not silently replace a selected GPU session with
CPU or turn an error into an empty result.

## Phones, Android TV and compatibility

The owner will test the Galaxy S25 Ultra and S22 Ultra. Samsung identifies the
[S25 family's Snapdragon 8 Elite for Galaxy](https://news.samsung.com/global/galaxy-s25).
The S22 Ultra has both
[Snapdragon](https://image-us.samsung.com/SamsungUS/samsungbusiness/pdfs/datasheet/Galaxy_S22_Ultra_Unlocked_Spec_Sheet.pdf)
and [Exynos](https://semiconductor.samsung.com/processor/showcase/smartphone/)
variants. Hearth uses the runtime's device/feature detection, including integrated
GPUs, rather than a Samsung or Qualcomm model-name allowlist. A model name alone
does not establish driver compatibility or speed.

All five optional GPU DSOs ship only for ARM64. The existing CPU libraries continue to
ship for ARM64, ARMv7, x86 and x86_64, including their universal APKs. CPU startup
does not load optional GPU DSOs or initialize graphics drivers. No manifest GPU requirement
is added. Android TVs and older devices keep the CPU path; merely opening
settings does not probe their GPU driver. The UI checks the current process's
64-bit ARM architecture, so a 32-bit process on a 64-bit SoC is not offered a
library it cannot load. An incompatible restored GPU preference has an explicit
CPU control.

The pinned llama Vulkan backend requires a usable Vulkan 1.2 device and its
required compute features. See [upstream Vulkan build guidance](https://github.com/ggml-org/llama.cpp/blob/64e9bceb2c3a856efed96feda784a50947049feb/docs/build.md#vulkan).
Driver capability, memory limits and unsupported graph operations remain relevant
even when Android itself supports Vulkan. GPU does not remove speech collection,
silence endpoint waiting, queues or translation-model loading from caption latency.

## Native isolation and reproducibility

`libtransiber_translation_vulkan.so` statically embeds the exact same pinned
llama/GGML sources as the CPU translator. Only its
`com.sal7one.common_jni.translation.LocalTranslationVulkanNative` entry points are
exported; GGML and C++ model objects stay private. The CPU JNI object and native
handle ownership remain separate. Context cancellation, bounded output, the
20-second inference deadline and model integrity checks are retained.

The optional binary, CPU binaries, NDK revision, source hashes, Vulkan/SPIR-V
header pins and shader compiler hash/version are recorded in
`common-jni/src/main/assets/licenses/translation/runtime-build.json`.
The shipped shaders are generated with shaderc **2026.4**. The task-local,
unmodified compiler comes from Homebrew's official
[pinned bottle layer](https://ghcr.io/v2/homebrew/core/shaderc/blobs/sha256:12ba0c59a98fb8f671f9473573002b2a812f26d2e60ea4f577f0c8c750c9838a).
Its archive SHA-256 is the URL suffix and its executable SHA-256 is
`73f9f514345e732bee65d4b92043af384b34f26af3ed797e0afbfb4e1449f8ce`.
Upstream source is shaderc `v2026.4`, commit
`b64fd42c4a2a9d501c47aca5d3571f7673a5e9cc`. This compiler enables newer
cooperative-matrix/integer-dot shader paths alongside baseline kernels; the
runtime selects paths according to device features. Their availability does not
establish a speedup on a particular phone.

For a reproducible GPU build, set `HEARTH_GLSLC` to this verified compiler and use
`build-runtime.sh android arm64-v8a vulkan` with a fresh GPU build directory.
Changing shader compilers in an existing upstream build directory can retain a
stale generated shader header; regenerate that header or use a fresh directory.
The default recipe can also use NDK glslc, whose older version builds fewer
optional kernels. The staged record identifies the compiler actually used.

Vulkan/SPIR-V license notices accompany the binary. APK verification checks the
optional library only in ARM64/universal packages, its private exports, ELF and
16 KiB alignment, exact bytes, notices and source records. It also rejects a
Vulkan dependency leaking into the default CPU translator. FOSS gains no network
permissions.

## Verification and performance limits

The production native wrapper and strict Vulkan initialization are exercised by
sanitized host tests. They cover CPU startup without a GPU probe, missing and
integrated GPUs, unsupported offload, confirmed placement, post-load failure and
resource cleanup. Kotlin tests cover the shared setting, immutable session
snapshot, strict backend choice, original errors, exact-once native destruction
when execution metadata fails and actual execution information in typed results.

A separate task-local host build uses the production `VulkanTextModel`, pinned
llama and the verified Index Q4 weights through MoltenVK on an Apple M4 Pro. It
confirms actual model placement and repeated multilingual inference. This is a
compatibility check on a Mac GPU, not an S25/S22/TV benchmark. The owner has not
yet measured Android first-use shader compilation, warm inference, sustained
temperature, memory pressure or complete caption latency. Known Index translation
errors still apply; GPU acceleration is not a quality upgrade.

The final host run confirmed **1,204.91 MiB of actual Vulkan model buffers** and
the loader's 26/26 layer assignment. All twelve multilingual requests completed
with nonempty text and exact parity for repeated inputs. First-use shader work
was visible in the first request. The host runtime was compiled at `-O1` and ran
beside Gradle work, so its timings are compatibility observations, not a controlled
CPU/GPU comparison. The Android runtime remains a separate Release build.
[Cases, outputs, timings, source/tool identities and limitations](benchmarks/2026-10-18-index-vulkan-host.json).

Native ASan/UBSan wrapper and Vulkan/OpenCL policy suites passed, as did the
three host translation CTest cases and the typed optional-driver loader tests.
Common and speech host suites cover lifecycle, ownership, dispatch and strict
backend failures. The complete integration and delivery gate is:

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :common-jni:externalNativeBuildDebug :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
```

The gate verifies all ten Play/FOSS CPU splits and universal APKs. It checks the
five optional ARM64 GPU binaries and notices, exact private exports, source and
binary identities, ELF widths, page alignment, optional vendor-library manifest
and FOSS permissions. Default CPU libraries cannot depend on GPU drivers. Model
weights remain separate. QA APKs are debug-signed test artifacts. No Android
device was controlled.

## Speech engines and Adreno OpenCL

| Engine | Shipped execution choices | Scope |
| --- | --- | --- |
| TranscribePsy | CPU, Vulkan, Adreno OpenCL | Same pinned Q8 model. Actual mel/encoder/decoder backend names are reported. OpenCL keeps TDT decoding on CPU because of the upstream persistent-state write bug. |
| Whisper | CPU, Vulkan | Same model files and existing streaming worker. GPU-only flash attention is required by the pinned backend to preserve initial words/negation. |
| GGUF translation | CPU, Vulkan, Adreno OpenCL | Same prompt protocols, integrity and cancellation. Adreno driver/device and positive GPU model-buffer evidence are required. |
| Moonshine, Phonon, Qwen, Omnilingual | Existing CPU engines | No claim that an acceleration switch supplies missing provider kernels. |
| TTS, OCR, Marian and platform translation | Their existing independent engines | They do not inherit GGUF or speech GPU settings. |

The new binaries are `libhearth_transcribepsy_vulkan.so`,
`libhearth_transcribepsy_opencl.so`, `libhearth_whisper_vulkan.so` and
`libtransiber_translation_opencl.so`, alongside the existing translation Vulkan
DSO. Each embeds its own private runtime. Versioned C tables preserve one owner
for each recognizer and retain bounded queues, cancellation and exact-once final
text. Speech C ABI version 2 checks both table size and backend revision. The
Whisper table additionally checks the complete vendored-tree digest; that tree
had no original upstream commit record, so the provenance does not invent one.

OpenCL uses a typed dynamic shim and an optional Android native-library manifest
declaration (`libOpenCL.so`, `required=false`). There is no `DT_NEEDED` OpenCL
link in any DSO. Loading CPU does not load the vendor driver. A requested OpenCL
session with a missing or unsupported driver fails visibly. Kernel/program caches
are confined to app-private cache directories and are distinct from model files.
The pinned OpenCL headers are `30bc20a8e90468e231d7c639805ae61ad1fefa4f`.

Host checks with real model weights found and fixed a Whisper Vulkan regression:
without GPU flash attention the pinned backend omitted leading words, including
“ask not.” The GPU-only fix restores exact CPU text in quantized/F16 and negation
fixtures, including batch and streaming-final checks. Warm Mac GPU inference was
faster on those fixtures. This is **not** a measured Android speedup. See
[Whisper host proof](benchmarks/2026-10-18-whisper-vulkan-host.json).

TranscribePsy CPU and Vulkan outputs matched byte for byte across all eight
supported-language fixtures, with one final and unknown-language reporting
preserved. Vulkan was approximately twice as slow on this Mac GPU for these short
clips. Existing transcription errors were also preserved. GPU is therefore an
explicit choice, not an assumed upgrade. See
[TranscribePsy host proof](benchmarks/2026-10-18-transcribepsy-vulkan-host.json).
No Adreno phone was controlled or benchmarked here.

**Benchmarks → Compare CPU and GPU backends** runs each supported backend against
the same installed model and input without mutating saved preferences. Result
identities distinguish backends; unavailable drivers are failed rows. Cold load,
first and warm passes, actual execution status and output accuracy remain
separate. Speech replay is accelerated input/batch inference, not a measurement
of capture-to-overlay latency. Full real-time latency and sustained thermal
behavior still require owner device tests.

TranscribePsy evidence is in its pinned
[Android build configuration](https://github.com/tether-ai-research/qvac-transcribepsy/blob/364ec36130dc87d2cd46e596598268a6e3313751/CMakeLists.txt),
[mobile device handling](https://github.com/tether-ai-research/qvac-transcribepsy/blob/364ec36130dc87d2cd46e596598268a6e3313751/src/parakeet_ctc.cpp)
and [mixed OpenCL TDT execution](https://github.com/tether-ai-research/qvac-transcribepsy/blob/364ec36130dc87d2cd46e596598268a6e3313751/src/parakeet_tdt.cpp).

NNAPI is not the chosen investment: Google deprecated it in Android 15 and warns
that many future implementations will use its CPU backend.
[Android NNAPI guidance](https://developer.android.com/ndk/guides/neuralnetworks).
Qualcomm QNN HTP is an NPU backend and its compiled speech models depend on the
SoC and fixed input shapes; they are not interchangeable with the current ONNX
downloads. [ORT QNN provider](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html),
[Sherpa model constraints](https://k2-fsa.github.io/sherpa/onnx/qnn/models.html).
