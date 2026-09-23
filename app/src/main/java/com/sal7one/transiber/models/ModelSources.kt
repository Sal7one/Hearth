package com.sal7one.transiber.models

import com.sal7one.transiber.caption.CaptionEngineChoice

/** Publisher files are distinct from app-ready imports. Do not infer compatibility from an extension. */
internal data class ModelSource(
    val id: String, val label: String, val publisher: String, val files: String,
    val installation: String, val download: String? = null, val downloadBytes: Long? = null,
)
internal object ModelSources {
    private const val sherpa = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    private const val packaging = "Downloads and installs automatically on this phone. The original file stays in your download folder."
    fun speech(engine: CaptionEngineChoice): List<ModelSource> = when (engine) {
        CaptionEngineChoice.NEMOTRON -> listOf(ModelSource("nemotron-3.5-asr-0.6b", "Nemotron 3.5 · 0.6B Q8", "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b",
            "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/tree/1c8deaecc64b91f034d73e08dd8b64625eb3395d", packaging + " Multilingual streaming captions; translation uses a separate model.",
            "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/resolve/1c8deaecc64b91f034d73e08dd8b64625eb3395d/nemotron-3.5-asr-streaming-0.6b.q8_0.gguf"))
        CaptionEngineChoice.QWEN -> listOf(
            ModelSource("qwen3-asr-0.6b", "Qwen3-ASR · 0.6B INT8", "https://huggingface.co/Qwen/Qwen3-ASR-0.6B", "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models",
                packaging + " Multilingual captions in short audio segments. Runtime package: March 2026.", sherpa + "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2", 878702423),
            ModelSource("qwen3-asr-1.7b", "Qwen3-ASR · 1.7B · custom package", "https://huggingface.co/Qwen/Qwen3-ASR-1.7B", "https://huggingface.co/Qwen/Qwen3-ASR-1.7B/tree/main",
                "Compatible prepared packages are accepted, but this app has no verified ready-made 1.7B download. Raw checkpoints cannot be imported. Use the 0.6B package unless you are preparing a compatible sherpa export."))
        CaptionEngineChoice.OMNILINGUAL -> listOf(ModelSource("omnilingual-ctc-300m-v2-int8", "Omnilingual CTC · 300M v2 INT8", "https://huggingface.co/facebook/omniASR-CTC-300M",
            "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models", packaging + " Experimental, short utterance windows. Recognition has no language-forcing input; the source selection labels its output for translation.",
            sherpa + "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2", 292313120))
        CaptionEngineChoice.MOONSHINE -> listOf("tiny" to 29858559L, "base" to 111266225L).map { (size, bytes) ->
            ModelSource("moonshine-$size-en-v2", "Moonshine · ${size.replaceFirstChar { it.uppercase() }} · English", "https://github.com/moonshine-ai/moonshine", "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models",
                packaging + " English only, short audio segments. Runtime package: 27 February 2026. This adapter is not Moonshine’s newer streaming runtime.",
                sherpa + "sherpa-onnx-moonshine-$size-en-quantized-2026-02-27.tar.bz2", bytes)
        }
        CaptionEngineChoice.WHISPER -> listOf(ModelSource("whisper", "Whisper · whisper.cpp models", "https://huggingface.co/ggerganov/whisper.cpp", "https://huggingface.co/ggerganov/whisper.cpp/tree/main",
            "Download a whisper.cpp GGML .bin file, then Import Whisper file. Tiny/Base use less memory. Files ending in .en.bin recognize English only; multilingual files offer explicit spoken languages. Safetensors and GGUF files are not this adapter’s input."))
        CaptionEngineChoice.VOSK -> listOf(ModelSource("vosk", "Vosk · language-specific models", "https://alphacephei.com/vosk/models", "https://alphacephei.com/vosk/models",
            "Choose your spoken language on the publisher page. Download and extract its ZIP, then Import Vosk folder. Select the extracted model folder itself. Each model fixes its spoken language; there is no automatic language switching."))
        CaptionEngineChoice.CLOUD -> emptyList()
    }
    val marian = ModelSource("marian-en-ar", "Marian / OPUS-MT · English → Arabic", "https://huggingface.co/Helsinki-NLP/opus-mt-en-ar", "https://huggingface.co/onnx-community/opus-mt-en-ar/tree/main",
        "Legacy English → Arabic path. Prepare a folder with source.spm, tokenizer.json, encoder_model.onnx and decoder_model_merged.onnx (from the ONNX conversion), then Import translation folder. Not a GGUF or speech ZIP; arbitrary ONNX variants are not verified. New local speech engines use the translators above.")
}
