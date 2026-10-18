Hearth speech runtimes. CPU defaults on all ABIs; no model weights or network clients.
Qwen: sherpa-onnx with private static ONNX Runtime 1.27.1.
Nemotron: NeMo-Speech.cpp with private static GGML and SentencePiece.
TranscribePsy: QVAC TranscribePsy with private static GGML; utterance-based CPU decoding.
Optional ARM64 TranscribePsy Vulkan/OpenCL plugins are loaded only by explicit selection. GPU encoder and decoder with CPU mel; resolved backend is checked after model load.
OpenCL uses an optional public vendor driver via a private typed loader; no vendor driver is bundled. GPU evidence: gpu-runtime-build.json.
Runtime source pins and local adapter/patch recipe hashes: runtime-build.json.
Build recipe: scripts/speech/build-runtimes.sh. Upstream source notices follow in subdirectories; some cover optional components not enabled in this build.
Hearth modifies sherpa CPU-provider guards and retains Qwen language metadata; NeMo C++ internals are statically embedded behind one private ABI.
