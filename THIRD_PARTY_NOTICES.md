# Third-party notices

First-party application and speech adapter code: Apache-2.0, see LICENSE and NOTICE.
This project started with a speech subset of Hearth at 978382d; modified application
packages use `com.sal7one.transiber`. Third-party license texts are also included in
APK assets under `licenses/`.

| Component | Version / provenance | License and notices |
| --- | --- | --- |
| whisper.cpp + ggml | Vendored source subset of ggml-org/whisper.cpp, 19ceec8eac980403b714d603e5ca31653cd42a3f | MIT; upstream LICENSE, `licenses/whisper-LICENSE` and `licenses/ggml-cpu-NOTICES.txt` |
| Vosk Android | Maven com.alphacephei:vosk-android:0.3.70, arm64 shared library | Apache-2.0; `licenses/vosk/`, Kaldi and OpenFst notices (OpenFst in `licenses/speech/openfst/`) |
| ONNX Runtime | Maven com.microsoft.onnxruntime:onnxruntime-android:1.20.0 | MIT and upstream third-party notices in `licenses/onnxruntime-1.20.0/` |
| Qwen / Moonshine speech adapter | sherpa-onnx 210f340bcfdfd5b9ad6b24245e77a934d6c28f1b; CPU ONNX Runtime 1.27.1 (Android) / 1.20.1 (desktop) | Apache-2.0 and dependency notices in `licenses/speech/` |
| Qwen3-ASR desktop adapter | static sherpa-onnx 210f340bcfdfd5b9ad6b24245e77a934d6c28f1b linked with the shipped ONNX Runtime 1.20.1; Qwen3-ASR weights Apache-2.0 (separately downloaded, never committed) | `Sherpa-LICENSE.txt` and the aggregated `Sherpa-Dependencies-NOTICES.txt` are staged into the desktop native bundle and listed in its manifest |
| TranscribePsy desktop backend | parakeet.cpp/qvac-transcribepsy 364ec36130dc87d2cd46e596598268a6e3313751, statically linked ggml; TranscribePsy 250M EU q8 weights Apache-2.0 (separately downloaded, never committed) | parakeet Apache-2.0 and ggml MIT notices are aggregated in `Speech-Backends-NOTICES.txt` in the desktop native bundle |
| Nemotron 3.5 desktop backend | NeMo-Speech.cpp ffa38cb2408f1e832a36d46fef5e3e1e80d07e6c, SentencePiece 17d7580d6407802f85855d2cc9190634e2c95624, statically linked ggml; Nemotron 3.5 ASR q8 weights OpenMDW-1.1 (separately downloaded, never committed) | NeMo-Speech.cpp Apache-2.0, SentencePiece Apache-2.0 (with protobuf-lite BSD-3-Clause) and ggml MIT notices are aggregated in `Speech-Backends-NOTICES.txt` in the desktop native bundle |
| CTranslate2 desktop translator | static CTranslate2 4.8.3 (MIT), SentencePiece 0.2.0 (Apache-2.0), Ruy (Apache-2.0), spdlog (MIT), cpu_features (Apache-2.0), Apache-2.0 Rust byte-BPE; QuickMT weights CC-BY-4.0 / SMaLL-100 MIT (separately downloaded, never committed) | `Ct2-Dependencies-NOTICES.txt` is staged into the desktop native bundle and listed in its manifest; model cards carry the weight licenses |
| Nemotron speech adapter | nemo-speech ffa38cb2408f1e832a36d46fef5e3e1e80d07e6c | MIT; GGML, SentencePiece and other notices in `licenses/speech/` |
| C++ shared runtime | Android NDK 27.0.12077973 | NDK NOTICE / NOTICE.toolchain in assets |
| AndroidX / Compose / Material | Version catalog and app Gradle dependencies | Apache-2.0; `licenses/android/Apache-2.0.txt` |
| ZXing core | com.google.zxing:core:3.5.3 | Apache-2.0; QR encoding only |
| Forge | node-forge 1.4.0, npm integrity and SHA-256 in `assets/phone-setup/forge-source.json` | BSD-3-Clause; upstream license and bundled notices in `assets/phone-setup/FORGE-LICENSE.txt` |
| Kotlin / coroutines | Kotlin 2.2.0, coroutines 1.8.1 | Apache-2.0 |
| Apache Commons Compress / IO / Lang / Codec | Compress 1.28.0; IO 2.20.0; Lang 3.18.0; Codec 1.19.0 | Apache-2.0; upstream license and notice files in `licenses/downloads/` |
| OkHttp / Okio | OkHttp 4.12.0 and resolved Okio dependency | Apache-2.0; OkHttp notice in `licenses/android/` |

Exact prebuilt library hashes and original Maven artifact URLs are in
`scripts/supply-chain-artifacts.tsv`, `common-jni/src/main/jniLibs/SHA256SUMS`, and `hearth-onnx-runtime/src/main/jniLibs/SHA256SUMS`.
The Qwen/Nemotron build provenance and adapter hashes are in
`common-jni/src/main/assets/licenses/speech/runtime-build.json`.

FFmpeg, x264/x265, OpenCV, LiteRT, media editing, ASL models and sample media
are not part of this extraction. Model weights are not in this repository or APK.
Phonon 2 packages are user-converted from Fermion's pinned artifact; its weights are
CC-BY-4.0 (as is NVIDIA's parakeet-tdt-0.6b-v3 base). Qwen weights use their publisher's Apache-2.0 terms; Nemotron weights have their own
OpenMDW-1.1 terms. Imported Whisper/Vosk/Marian/voice models likewise retain their
publisher's terms. Model source links are in docs/models.md.

Upstream sources: https://github.com/ggml-org/whisper.cpp,
https://github.com/alphacep/vosk-api, https://github.com/microsoft/onnxruntime,
https://github.com/k2-fsa/sherpa-onnx. Further pinned sources are in the runtime build script.

Optional play-only translation: `com.google.mlkit:translate:17.0.3`, including
`libtranslate_jni.so`, under [Google ML Kit terms](https://developers.google.com/ml-kit/terms).
Downloaded packs are not redistributed in this repository or APK. Moonshine v2
English weights use their package’s MIT license; other Moonshine artifacts may
differ. TranslateGemma weights use Gemma terms, HY-MT1.5 uses Tencent Hunyuan
community terms, and Hy-MT2 uses Apache-2.0. The translation runtime is llama.cpp
(MIT); exact revision and adapter hashes are in `licenses/translation/runtime-build.json`.

The vendored ggml CPU sources include Mozilla Foundation llamafile SGEMM and
Jeffrey Quesnelle / Bowen Peng YaRN contributions; their MIT notices are packaged
in `licenses/ggml-cpu-NOTICES.txt`. The source tree also retains Android-disabled
SYCL files with Apache-2.0 WITH LLVM-exception headers. Those files are not built
into these Android APKs; preserve their individual headers when redistributing.

Camera translation uses AndroidX CameraX 1.4.0, including its image-processing JNI
utility, under Apache-2.0. PaddleOCR/PaddleX recognition dictionaries are derived
from pinned PaddlePaddle PP-OCRv5 ONNX export configurations (Apache-2.0), with
blank/space entries for CTC decoding. Exact origins, revisions and hashes are in
`assets/ocr/provenance.json`. Recognition and detector weights are separate
verified downloads, not APK assets. See [OCR sources](docs/camera-ocr.md).

Supertonic 3 inference/text preparation adapts the MIT implementation from
[Supertone](https://github.com/supertone-inc/supertonic), revision
1e9799e964ea4c0dad7cde993b65c3c813a7b373. Its copyright/license and provenance are in
`assets/licenses/voice/`. Downloaded Supertonic 3 weights use OpenRAIL-M terms, also
included there, and are not bundled in the APK. The first-party server bridge calls
separately installed Chatterbox (MIT), Qwen3-TTS (Apache-2.0), or Fish Speech
(Fish Audio Research License) packages. No code, weights or reference recordings
from those packages or tts-bench are redistributed in Hearth. See [voice sources](docs/voices.md).

## Japanese reading OCR

Manga OCR's vocabulary is bundled under Apache-2.0 with exact provenance in
`assets/ocr/manga-provenance.json`. The ONNX Community encoder/decoder exports
are separate verified downloads. Meiki's model-contract reference code is
Apache-2.0, but its detector/reader weights declare LGPL-3.0. These are separate
licenses and the weights are not bundled. Publisher links, pinned artifacts and
limitations are recorded in [Japanese OCR adapters](docs/manga-ocr-adapters.md)
and packaged in `assets/licenses/ocr/NOTICE.txt`.

## Additional optional runtimes in 0.26.0

- Cohere/Fun-ASR/Voxtral use the MIT-licensed
  [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) port pinned at
  `1dc22facfc931e2f8adb43924b0e38b9c46f83c7`, with GGML and dependency notices
  packaged in `assets/licenses/speech-expansion/`. CPU and Vulkan binaries have
  separate hashes and source snapshots. Model weights retain their publisher's
  terms, including the selected Fun-ASR conversion's distinct license evidence;
  see the sourced model cards rather than assuming the runtime license covers weights.
- [CTranslate2](https://github.com/OpenNMT/CTranslate2) 4.8.3 and
  [SentencePiece](https://github.com/google/sentencepiece) use MIT and Apache-2.0
  respectively. Ruy, cpuinfo, cpu_features, spdlog and other retained dependency
  notices are in `assets/licenses/ct2/`. QuickMT weights use CC-BY-4.0; the selected
  SMaLL-100 checkpoint declares MIT. These model files are separate downloads.
- [NeMo-Speech.cpp](https://github.com/NVIDIA/NeMo-Speech.cpp), pinned at
  `caa3f47c7c75fa2e9e6340272ebad57c1647dd44`, supplies the optional Magpie CPU
  runtime under Apache-2.0, with GGML MIT and retained dependency notices in
  `assets/licenses/magpie/`. Magpie and codec model assets are separately acquired
  under their published terms; the app links the NVIDIA Open Model License.
- The [Ming-3.0](https://huggingface.co/collections/inclusionAI/ming-30) models and
  original upstream code declare MIT, with their own third-party notices. Hearth
  distributes first-party server adapters and pinned metadata; Ming model code,
  weights, reference voices and server dependencies are not included in the APK
  or SDK AARs. See [Ming sources and sizes](docs/ming-3.0.md).


The shared ONNX Runtime 1.20.0 prebuilts and their notices now reside in the
model-free `:hearth-onnx-runtime` module. `:common-jni` and
`:hearth-tts-supertonic` consume that single package; the Supertonic adapter has
no STT or translation binary dependency. Its unchanged native implementation
and `com.sal7one.common_jni.voice` API reside in `hearth-tts-supertonic/`.

The optional OmniVoice runtime is built from MIT-licensed omnivoice.cpp
1a468d8b9d65b3544ca1004d2d7c05dfc65d1f3e and GGML
cc5df49a6b878db2b68b9a7b45976e279594627d. Code notices and the exact build
snapshot are in `assets/licenses/omnivoice/`. Pretrained model weights are
separate noncommercial user downloads. The Higgs Audio v2 codec uses the
original Boson Community and incorporated Meta Llama 3 terms; the community
conversion card must not be interpreted as replacing those terms. See
https://huggingface.co/bosonai/higgs-audio-v2-tokenizer/blob/main/LICENSE.

The independent OWSM and Audar CPU speech plugins retain audio.cpp
673ade451c9cba9c893fb0e606872d9dc44f21e3 (Apache-2.0) and llama.cpp
64e9bceb2c3a856efed96feda784a50947049feb (MIT), with included dependency
notices and exact build records under `assets/licenses/speech-modern-cpu/`.
OWSM model weights are separate CC-BY-4.0 downloads attributed to Yifan
Peng/ESPnet. Audar model weights are separate downloads under the
[AudarAI Open License v1.0](https://www.audarai.com/license/audarai-open-license-v1.0/).
Runtime licenses do not replace these model licenses. See
[the native adapter and package notes](docs/models/stt-modern-cpu.md).

## Standalone MOSS Nano and Chatterbox V3 TTS providers

`hearth-tts-moss` contains the Apache-2.0 MOSS orchestration and privately linked SentencePiece (including its original third-party notices), plus MIT JSON. Model/codec weights are separately downloaded under their exact pinned Apache-2.0 model terms. `hearth-tts-chatterbox` uses MIT chatterboxcpp audio/Perth code, real Hugging Face tokenizers0.22.2 with locked dependency notices, and MIT JSON. The native Perth converter operates only on the separately downloaded official MIT checkpoint; no pickle execution or checkpoint redistribution is included. Chatterbox V3 model/export is MIT, and the adapter always applies native Perth before publishing audio. Both optional providers share the single official ONNX Runtime AAR; neither links the broad STT/translation `common-jni` binary. Full source/binary provenance and dependency notices ship under each module's `assets/licenses/` folder.
