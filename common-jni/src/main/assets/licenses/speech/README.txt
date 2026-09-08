Hearth optional speech runtimes. CPU-only, no model weights or network clients.
Qwen: sherpa-onnx with private static ONNX Runtime 1.27.1.
Nemotron: NeMo-Speech.cpp with private static GGML and SentencePiece.
Runtime source pins and local adapter/patch recipe hashes: runtime-build.json.
Build recipe: scripts/speech/build-runtimes.sh. Upstream source notices follow in subdirectories; some cover optional components not enabled in this build.
Hearth modifies sherpa CPU-provider guards and retains Qwen language metadata; NeMo C++ internals are statically embedded behind one private ABI.
