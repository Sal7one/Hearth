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
| Qwen / Moonshine speech adapter | sherpa-onnx 210f340bcfdfd5b9ad6b24245e77a934d6c28f1b; CPU ONNX Runtime 1.27.1 | Apache-2.0 and dependency notices in `licenses/speech/` |
| Nemotron speech adapter | nemo-speech ffa38cb2408f1e832a36d46fef5e3e1e80d07e6c | MIT; GGML, SentencePiece and other notices in `licenses/speech/` |
| C++ shared runtime | Android NDK 27.0.12077973 | NDK NOTICE / NOTICE.toolchain in assets |
| AndroidX / Compose / Material | Version catalog and app Gradle dependencies | Apache-2.0; `licenses/android/Apache-2.0.txt` |
| Kotlin / coroutines | Kotlin 2.2.0, coroutines 1.8.1 | Apache-2.0 |
| Apache Commons Compress / IO / Lang / Codec | Compress 1.28.0; IO 2.20.0; Lang 3.18.0; Codec 1.19.0 | Apache-2.0; upstream license and notice files in `licenses/downloads/` |
| OkHttp / Okio | OkHttp 4.12.0 and resolved Okio dependency | Apache-2.0; OkHttp notice in `licenses/android/` |

Exact prebuilt library hashes and original Maven artifact URLs are in
`scripts/supply-chain-artifacts.tsv` and `common-jni/src/main/jniLibs/SHA256SUMS`.
The Qwen/Nemotron build provenance and adapter hashes are in
`common-jni/src/main/assets/licenses/speech/runtime-build.json`.

FFmpeg, x264/x265, OpenCV, LiteRT, media editing, ASL models and sample media
are not part of this extraction. Model weights are not in this repository or APK.
Qwen weights use their publisher's Apache-2.0 terms; Nemotron weights have their own
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
