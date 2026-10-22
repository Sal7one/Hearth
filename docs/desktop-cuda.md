# Windows NVIDIA CUDA packaging

This is an opt-in build path for the pinned Whisper and GGUF translation
backends. It does not enable GPU execution for every local engine. Android's
backends and the macOS Metal build are separate. **No NVIDIA Windows host has
validated this path yet.** Packaging fixtures are not evidence of GPU execution,
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
