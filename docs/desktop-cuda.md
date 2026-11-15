# Windows NVIDIA CUDA packaging

This is an opt-in build path for the pinned Whisper and GGUF translation
backends. It does not enable GPU execution for every local engine. Android's
backends and the macOS Metal build are separate. **No NVIDIA Windows host has
validated this path yet.** Nemotron has a separate CUDA backend that has been
run on NVIDIA hardware; see [Nemotron on NVIDIA GPUs](#nemotron-on-nvidia-gpus). Packaging fixtures are not evidence of GPU execution,
driver compatibility, performance or an installable Windows release.

## Build contract

Use an official installed Windows x64 CUDA 12/13 Toolkit and an appropriate
Visual Studio C++ toolchain. Toolkit selection must be explicit when multiple
versions are installed. `stage-cuda-runtime.py` accepts an installer-registered
toolkit directory or a versioned directory under the standard NVIDIA CUDA
installation root; it never searches `PATH` or `System32` for redistributables.
An explicit `--toolkit`/`CUDA_PATH` must resolve to one of those installed roots.
The toolkit's `version.json`, CUDA header, compiler and installed EULA must exist.

The native build owner configures the pinned GGML sources with CUDA enabled,
`BUILD_SHARED_LIBS=OFF` and `GGML_STATIC=ON`. On Windows those pins link the static
CUDA runtime and dynamic cuBLAS. A dynamic CUDA runtime is copied only if a PE
import requires it. Configure deployment GPU architectures for the intended
machines; a build using only the build machine's native architecture is not a
portable installer. Build both JNI libraries before staging:

```powershell
python scripts/desktop/stage-cuda-runtime.py `
  --bundle desktop-runtime/native-libs/windows-x64 `
  --toolkit 'C:/Program Files/NVIDIA GPU Computing Toolkit/CUDA/v12.8' `
  --receipt-json build/desktop-cuda-runtime.json
```

The public builder contract is:

```python
stage_files(bundle, toolkit_root=None, native_names=None) -> dict
```

It stages files and returns the `cudaRuntime` receipt without requiring a native
manifest. Default entries are `hearth_desktop_whisper.dll` and
`transiber_translation.dll`. The builder calls it after compiling/copying these
DLLs and before loading their compiled-backend probes. The builder includes the
receipt as `manifest.cudaRuntime`, inventories all staged bytes in
`manifest.files`, and retains each native binary's actual compiled-backend probe.
It must not advertise CUDA merely because a toolkit directory exists. The
standalone `stage(...)` wrapper also updates an existing valid Windows manifest.
Run the separate Microsoft CRT staging step after final native manifest creation,
before Gradle packages the bundle.

The receipt includes exact toolkit version, JNI entry names, dependency-first
`loadOrder`, runtime `files`, SHA-256 for every staged runtime/legal/provenance
file, and the system driver requirement. For example:

```json
{
  "schemaVersion": 1,
  "toolkitVersion": "12.8.1",
  "entries": ["hearth_desktop_whisper.dll", "transiber_translation.dll"],
  "files": ["cublaslt64_12.dll", "cublas64_12.dll"],
  "loadOrder": ["cublaslt64_12.dll", "cublas64_12.dll"],
  "driver": "nvcuda.dll",
  "requiresSystemDriver": true
}
```

The example omits hashes/legal fields for readability; the actual receipt records
them. `cudart64_12.dll`/CUDA 13 counterparts appear only when required. Existing
opaque `.dll.payload` packaging preserves original bytes. The native loader must
verify the full manifest and preload the listed dependencies by absolute private
paths before either JNI entry. No global DLL search-path/PATH mutation is needed.
The four separately verified Microsoft CRT DLLs are loaded before the NVIDIA
libraries; accepting their imports here does not make them an assumed globally
installed dependency. A native build probe on a clean packaging machine needs
those dependencies available before loading JNI as well.

## Dependency and redistribution policy

The stager parses normal **and delay-loaded** AMD64 PE imports recursively.
cuBLAS additionally requires its matching cuBLASLt library even when a DLL import
table omits that dynamic dependency; NVIDIA's own
[library descriptors](https://nvidia.github.io/cuda-python/coverage/z_b173e2ae1fca2bc7_descriptor_catalog_py.html)
declare that dependency. The allowed NVIDIA redistributables are the matching
CUDA-major cuBLAS, cuBLASLt and, when imported, cudart. Every selected DLL must
have a valid Authenticode signature from **NVIDIA Corporation**. An unrecognized
non-system dependency stops packaging with its actual filename. Supporting a
different pinned backend/toolkit dependency requires an explicit policy and
license review, not copying additional DLLs from an arbitrary directory.

`nvcuda.dll` belongs to the installed NVIDIA display driver and is never bundled
or loaded from the application directory. NVIDIA distinguishes the
[driver and toolkit runtime](https://developer.nvidia.com/cuda/faq). The user needs
a compatible installed driver and usable NVIDIA GPU; compiling CUDA support is
not proof that either exists. GPU selection must fail visibly if initialization
or model placement fails.

The installed EULA is copied byte-for-byte as `NVIDIA-CUDA-EULA.txt`, alongside
`NVIDIA-CUDA-NOTICE.txt` and `nvidia-cuda-provenance.json`. The provenance records
relative toolkit paths, exact source hashes, signature certificate/version
details, inspected imports, native entry hashes and the packaging script hash.
Source hashes are checked again after signature verification/copying before
publishing files. Symlink escapes, wrong architectures, missing license text,
missing cuBLASLt and changed sources fail the build.

NVIDIA's [CUDA EULA, Attachment A](https://docs.nvidia.com/cuda/eula/index.html#attachment-a)
lists these runtime libraries as redistributable; the accompanying installed
EULA governs the actual toolkit version and redistribution conditions. These
libraries remain NVIDIA software and are not relicensed under Hearth's license.
The packager includes no compiler, profiler, driver installer or model weights.
Toolkit and driver installation requirements are described in NVIDIA's
[Windows installation guide](https://docs.nvidia.com/cuda/cuda-installation-guide-microsoft-windows/index.html).

## Validation and remaining acceptance

Run model-free host checks with:

```sh
python3 -m unittest discover -s scripts/desktop -p 'test_stage_cuda_runtime.py' -v
```

The nine fixtures cover actual PE dependency traversal, delayed cudart imports,
the cuBLASLt dynamic dependency, missing/unexpected libraries, driver exclusion,
CPU-only/wrong-major rejection, installed-root/symlink restrictions, mandatory
license/signature results, source mutation, provenance hashes and existing
manifest preservation. Signature execution is mocked in these fixtures; real
NVIDIA Authenticode and native loading require a matching Windows host.

Before claiming Windows CUDA support, validate the signed runtime closure on
Windows, run packaged-launcher verification with the actual bundled JRE, and run
pinned real Whisper and GGUF translation models through explicit CUDA selections.
Record compiled flags, driver/device identity, actual GPU placement, cancellation
and failure behavior, outputs and timings. Test CPU selection independently and
keep unsupported engines on their actual backend. No benchmark or Windows GPU
acceptance is claimed by this document.

## Owner's local Windows run

From **Developer PowerShell for Visual Studio**, use JDK 17, Python 3.11+,
CMake, Git, and the official CUDA Toolkit with a compatible NVIDIA driver.
These commands are local builds and do not invoke GitHub Actions. The CUDA
build is opt-in; use a CPU build on machines without the NVIDIA driver.

```powershell
python scripts/desktop/prepare-dependencies.py
python scripts/desktop/build-native.py --stage all --cuda `
  --cuda-toolkit 'C:/Program Files/NVIDIA GPU Computing Toolkit/CUDA/v12.8' `
  --llama-root build/desktop-dependencies/llama.cpp `
  --ort-root build/desktop-dependencies/onnxruntime-win-x64-1.20.1 --jobs 4
python scripts/desktop/stage-windows-crt.py
./scripts/desktop/build-media.ps1
./gradlew.bat -PhearthDesktopOnly=true :hearth-core:desktopTest :desktop-runtime:test :desktop-data:test :desktop-media:test :desktop-integration:test :desktopApp:desktopTest :desktopApp:createDistributable
python scripts/desktop/check-packaged.py
```

Adjust the installed toolkit directory to your version. CUDA 12/13 are accepted;
the actual DLL imports must match that major version. Upstream portable GPU
architecture defaults are retained; `--cuda-architectures` permits an explicit
CMake list. Do not replace them with `native` for a distributed installer.

Supply local Whisper tiny Q5_1 (multilingual or English-only), EuroLLM 1.7B
Q4_K_M, and a 0.5–30 second speech WAV. Models are **never packaged**. The check
accepts only these fixture hashes:

| Fixture | SHA-256 |
| --- | --- |
| Whisper tiny Q5_1 | `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7` |
| Whisper tiny.en Q5_1 | `c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b` |
| EuroLLM 1.7B Q4_K_M | `f22ab4b628f6e3cd12ec2e5aa6d1e460be2fa83f25cb6a8f5dd6d4d77b285b66` |

Whisper fixtures are available from [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp/tree/main).
Use this [pinned EuroLLM file](https://huggingface.co/QuantFactory/EuroLLM-1.7B-Instruct-GGUF/resolve/4126f034ddfe7fd39ca3378ae58d3b1d74065315/EuroLLM-1.7B-Instruct.Q4_K_M.gguf).

```powershell
python scripts/desktop/validate-gpu.py --backend cuda `
  --whisper 'D:/Models/ggml-tiny-q5_1.bin' `
  --translation 'D:/Models/EuroLLM-1.7B-Instruct.Q4_K_M.gguf' `
  --wav 'D:/Samples/speech.wav' --receipt build/nvidia-inference.json
```

This runs the actual packaged launcher and bundled JRE, opens no UI, records or
plays no audio, and leaves app settings alone. It explicitly tests CPU and CUDA
separately, requires the selected GPU to initialize and receive model weights,
performs real STT and English→Arabic translation, and requires each native
engine's successful-inference counter. The receipt records actual devices,
native/model/WAV hashes, model allocations, outputs and separate load/inference
timings. A flag, an empty output, a CPU fallback or an unavailable GPU cannot pass.
Send back `build/nvidia-inference.json` and the build logs. Single-fixture timings
are observations, not a throughput benchmark or a translation-quality evaluation.

## Nemotron on NVIDIA GPUs

Nemotron 3.5 ASR has its own CUDA backend on Windows x64. It is separate from the Whisper
and GGUF translation path above and follows a different rule, because it ships nothing
made by NVIDIA. It was built and run on an RTX 5070 on 15 November 2026.

| | Whisper and GGUF translation (above) | Nemotron |
| --- | --- | --- |
| NVIDIA libraries in the installer | cuBLAS and cuBLASLt, about 500 MB, under the CUDA EULA | none |
| What the user needs | an NVIDIA driver | an NVIDIA driver and an RTX 30-series or newer GPU |
| What the build needs | an installed official CUDA Toolkit | the pinned build tools `fetch-cuda-toolkit.py` fetches; no administrator rights |
| Run on NVIDIA hardware | not yet | yes, results below |

### What is built

- `hearth_nemotron_cuda.dll` (37 MB) is a second backend library beside the CPU
  `hearth_nemotron.dll`. It contains ggml's CUDA backend and NVIDIA's CUDA runtime, linked
  statically. It imports the NVIDIA driver (`nvcuda.dll`), so it cannot load on a computer
  without one; the CPU library is unchanged and remains the default.
- `hearth_nemotron_cublas.dll` (3.4 MB) holds NeMo-Speech.cpp's own matrix kernels
  (Apache-2.0), which stand in for cuBLAS and cuBLASLt. Upstream gives this file NVIDIA's
  library name so that it replaces the real one at run time. Hearth links it directly under
  its own name instead: nothing in the bundle is named like an NVIDIA library, a real cuBLAS
  loaded by another engine cannot be mistaken for it, and a call it does not implement
  fails the link rather than a user's session.
- The kernels are compiled as portable code for CUDA compute capability 8.0 and newer,
  which covers the RTX 30, 40 and 50 series. An older GPU is not listed, and asking for
  one fails with that reason.
- Portable code is compiled by the NVIDIA driver the first time it runs on a computer,
  and the driver keeps the result. On the RTX 5070 that first transcription took 22 s;
  every later start took about half a second. `--nemotron-cuda-native 86-real;89-real;120a-real`
  adds ready-made code for the RTX 30, 40 and 50 series, which removes that first wait for
  those cards and adds about 19 MB to the library for each. ggml's own default would also
  build for GPUs this backend cannot run on and comes to 169 MB.
- `Nemotron-CUDA-NOTICES.txt` carries the licence of the statically linked runtime.
- Together they add 27 MB to the Windows installer (131 MB against 104 MB).
- The shared adapter `nemotron_backend.cpp` is part of the Android runtime receipt and is
  not edited. The build compiles a copy with three counted replacements that ask
  `desktop-runtime/src/main/cpp/windows/nemotron_cuda.cpp` which device to use; if the
  adapter changes, the build stops and says which replacement no longer applies.

### Build tools

```powershell
python scripts/desktop/fetch-cuda-toolkit.py --accept-nvidia-eula
python scripts/desktop/build-native.py --stage backend --llama-root build/desktop-dependencies/llama.cpp `
  --ort-root build/desktop-dependencies/onnxruntime-win-x64-1.20.1 --jobs 12
```

The first command downloads four of NVIDIA's CUDA 12.9.1 redistributable archives
(684 MB) from `developer.download.nvidia.com`, each pinned by SHA-256, into
`build/desktop-dependencies/cuda-toolkit`. It keeps the compiler, the headers and the
import libraries, extracts none of NVIDIA's runtime DLLs, and checks the assembled tree
against a pinned digest. The archives are NVIDIA software under the CUDA EULA, which is
why the flag is required; each component's licence is kept beside the tools.

`build-native.py` verifies that tree again before it compiles. When the tools are present
it builds the GPU backend with the other speech backends; when they are not, it says so
and builds the CPU backends only, which is what CI does today. The GPU build uses Ninja
from Visual Studio's CMake tools and takes about three minutes on twelve jobs.
`python scripts/desktop/doctor.py` reports whether the tools are fetched.

### What the app does with it

- The native manifest gains a `nemotronCuda` receipt naming the two libraries and the
  notices with their SHA-256, the toolkit version, the minimum compute capability, and
  that no NVIDIA library is bundled and the driver comes from Windows. The loader refuses
  a receipt that says otherwise, a file whose hash differs, and an app-local `nvcuda.dll`,
  before it loads anything.
- Nemotron's engine offers **NVIDIA CUDA** only when that library loads and lists a
  usable device. The CPU engine stays available whatever happens to the GPU library.
- A session that asks for CUDA runs on exactly the device it named, or fails with the
  reason. The GPU library has no CPU path to fall back to.

### Run on an RTX 5070 (driver 621.14)

`HEARTH_NEMOTRON_SMOKE=1 HEARTH_NEMOTRON_CUDA_SMOKE=1` with the pinned model and a 10 s
recording, through `DesktopNemotronRealSmokeTest`:

| Measure | Result |
| --- | --- |
| Transcript | identical to the CPU transcript, and identical on a second pass |
| 10 s of audio, model already loaded | 172 ms on the GPU; 2,018 ms on the CPU with four threads |
| First transcription ever on this computer (the driver compiles the portable code) | 22 s, once |
| First transcription of a later session, including moving the model to the GPU | 0.5 s |
| Video memory while the model is loaded | 986 MB, returned when the session closes |
| The same choice through the app's engine path, from nothing loaded | 775 ms |
| Device 7 requested; CPU requested of the GPU library | both refused with the reason |

The test cannot pass on a flag: the device must report less free memory while the model
is loaded, and the session must name the device it runs on.

### Not verified

- Any other GPU, an older driver, and a second GPU (device index above 0).
- A computer with no NVIDIA driver. By design the GPU library fails to load there and
  only the CPU engine is offered; the loader policy is unit-tested, the computer is not.
- The installed app on a clean PC, and the service host's video-memory figure, which is
  still `null` for a GPU route.
- CI does not fetch the build tools, so released installers do not contain this backend
  until the release build does.

### Qwen3-ASR on the GPU

Not built, on evidence. Qwen3-ASR runs through ONNX Runtime, where the GPU route on
Windows that needs no vendor libraries is DirectML. The pinned model's three graphs were
timed on this PC's RTX 5070 with synthetic inputs (ONNX Runtime 1.24.4 with DirectML
against four CPU threads; these are timings, not transcripts):

| Graph | CPU | DirectML |
| --- | --- | --- |
| Convolution front end, ten seconds of features | 87 ms | 4 ms |
| Encoder (int8) | 49 ms | 22 ms |
| Decoder (int8), one step | 46 ms | 48 ms |

The decoder runs once for every output token and is where the time goes, and it is no
faster on the GPU. For a sentence of twenty tokens that arithmetic gives about 6% overall.
A GPU path for Qwen3-ASR therefore needs a decoder exported for GPUs (fp16 or fp32), which
is not in the pinned catalogue, as well as a DirectML build of ONNX Runtime and
sherpa-onnx. Until then Nemotron is the recognizer to choose for the GPU.
