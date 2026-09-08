# Third-party notices

First-party application and speech adapter code: Apache-2.0, see LICENSE and NOTICE.
This project extracts a speech-only subset of Hearth at 978382d; modified application
packages use `com.sal7one.transiber`. Third-party license texts are also included in
APK assets under `licenses/`.

| Component | Version / provenance | License and notices |
| --- | --- | --- |
| whisper.cpp + ggml | Vendored source subset of ggml-org/whisper.cpp, 19ceec8eac980403b714d603e5ca31653cd42a3f | MIT; upstream LICENSE and `licenses/whisper-LICENSE` |
| Vosk Android | Maven com.alphacephei:vosk-android:0.3.70, arm64 shared library | Apache-2.0; `licenses/vosk/`, Kaldi and OpenFst notices (OpenFst in `licenses/speech/openfst/`) |
| ONNX Runtime | Maven com.microsoft.onnxruntime:onnxruntime-android:1.20.0 | MIT and upstream third-party notices in `licenses/onnxruntime-1.20.0/` |
| Qwen speech adapter | sherpa-onnx 210f340bcfdfd5b9ad6b24245e77a934d6c28f1b; CPU ONNX Runtime 1.27.1 | Apache-2.0 and dependency notices in `licenses/speech/` |
| Nemotron speech adapter | nemo-speech ffa38cb2408f1e832a36d46fef5e3e1e80d07e6c | MIT; GGML, SentencePiece and other notices in `licenses/speech/` |
| C++ shared runtime | Android NDK 27.0.12077973 | NDK NOTICE / NOTICE.toolchain in assets |
| AndroidX / Compose / Material | Version catalog and app Gradle dependencies | Apache-2.0; `licenses/android/Apache-2.0.txt` |
| Kotlin / coroutines | Kotlin 2.2.0, coroutines 1.8.1 | Apache-2.0 |
| OkHttp / Okio | OkHttp 4.12.0 and resolved Okio dependency | Apache-2.0; OkHttp notice in `licenses/android/` |

Exact prebuilt library hashes and original Maven artifact URLs are in
`scripts/supply-chain-artifacts.tsv` and `common-jni/src/main/jniLibs/SHA256SUMS`.
The Qwen/Nemotron build provenance and adapter hashes are in
`common-jni/src/main/assets/licenses/speech/runtime-build.json`.

FFmpeg, x264/x265, OpenCV, LiteRT, media editing, camera/ASL models and sample media
are not part of this extraction. Model weights are not in this repository or APK.
Qwen weights use their publisher's Apache-2.0 terms; Nemotron weights have their own
OpenMDW-1.1 terms. Imported Whisper/Vosk/Marian/voice models likewise retain their
publisher's terms. Model source links are in docs/models.md.

Upstream sources: https://github.com/ggml-org/whisper.cpp,
https://github.com/alphacep/vosk-api, https://github.com/microsoft/onnxruntime,
https://github.com/k2-fsa/sherpa-onnx. Further pinned sources are in the runtime build script.
