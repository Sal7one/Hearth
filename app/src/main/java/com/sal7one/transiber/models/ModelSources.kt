package com.sal7one.transiber.models

import com.sal7one.transiber.caption.CaptionEngineChoice
import com.sal7one.transiber.translation.MarianPackage

/** Publisher files are distinct from app-ready imports. Do not infer compatibility from an extension. */
internal data class ModelSource(
    val id: String, val label: String, val publisher: String, val files: String,
    val installation: String, val download: String? = null, val downloadBytes: Long? = null,
)
internal object ModelSources {
    private const val packaging = "Downloads and installs automatically on this phone. The original file stays in your download folder."

    /** Source cards use the same pinned artifacts as the installer, so labels and links cannot drift. */
    fun speech(engine: CaptionEngineChoice): List<ModelSource> = when (engine) {
        CaptionEngineChoice.CLOUD -> emptyList()
        CaptionEngineChoice.WHISPER -> listOf(ModelSource("whisper", "Whisper · whisper.cpp models",
            WhisperDownloads.all.first().publisherUrl, WhisperDownloads.all.first().filesUrl,
            "Download a whisper.cpp GGML .bin file, then Import Whisper file. Tiny/Base use less memory. Files ending in .en.bin recognize English only; multilingual files offer explicit spoken languages. Safetensors and GGUF files are not this adapter’s input."))
        CaptionEngineChoice.VOSK -> SpeechArtifactCatalog.forEngine(engine).map(::artifactSource) +
            ModelSource("vosk", "Vosk · import another language", "https://alphacephei.com/vosk/models", "https://alphacephei.com/vosk/models",
                "Other compatible Vosk models can still be imported as extracted folders. An unrecognized import keeps its language unspecified; the app does not guess it from the folder name.")
        CaptionEngineChoice.QWEN -> SpeechArtifactCatalog.forEngine(engine).map(::artifactSource) +
            ModelSource("qwen3-asr-1.7b", "Qwen3-ASR · 1.7B · custom package",
                "https://huggingface.co/Qwen/Qwen3-ASR-1.7B", "https://huggingface.co/Qwen/Qwen3-ASR-1.7B/tree/main",
                "Compatible prepared packages are accepted, but this app has no verified ready-made 1.7B download. Raw checkpoints cannot be imported. Use the 0.6B package unless you are preparing a compatible sherpa export.")
        CaptionEngineChoice.PHONON -> SpeechArtifactCatalog.forEngine(engine).map(::artifactSource) +
            if (SpeechArtifactCatalog.find("phonon-2.5") == null) listOf(ModelSource("phonon-2.5", "Phonon 2.5 · 25 European languages · prepared package",
                "https://huggingface.co/FermionResearch/Phonon-2.5",
                "https://huggingface.co/FermionResearch/Phonon-2.5/tree/96dad4f3b5b784b1aa595213ea7481bf8a7addcd",
                "Import a prepared Hearth transducer package for 25 European languages, including English and Russian. Raw .bps model weights are not this adapter’s input. Auto provides original captions without a source-language identity; choose a known source for translation. Arabic and Chinese speech are not supported. CC-BY-4.0.")) else emptyList()
        else -> SpeechArtifactCatalog.forEngine(engine).map(::artifactSource)
    }

    private fun artifactSource(artifact: SpeechArtifact): ModelSource {
        val guidance = when (artifact.engine) {
            CaptionEngineChoice.AUDAR -> " Arabic/English short utterance finals. CPU only; decoder and the matching BF16 audio encoder are both required. Phone performance unverified."
            CaptionEngineChoice.OWSM -> " Experimental 102M Arabic/English utterance finals. CPU only; Arabic accuracy was uneven in host smoke tests. No partials or phone speed claim."
            CaptionEngineChoice.COHERE -> " Arabic/English utterance finals; choose the spoken language explicitly. CPU or explicit ARM64 Vulkan; phone speed unverified."
            CaptionEngineChoice.FUNASR_MLT -> " Multilingual utterance finals including Arabic/English, with ITN enabled. CPU or explicit ARM64 Vulkan; phone speed unverified."
            CaptionEngineChoice.VOXTRAL -> " No recognition language hint; a chosen source labels text for translation. Auto keeps the source unknown. Bounded windowed partials in this adapter; the native partial path reprocesses accumulated audio. CPU or explicit ARM64 Vulkan; phone speed and Arabic quant parity unverified."
            CaptionEngineChoice.TRANSCRIBEPSY -> " Released October 14, 2026. Eight European languages in short utterance windows; no partials or language hints. Translation uses a separate model."
            CaptionEngineChoice.NEMOTRON -> " Multilingual streaming captions; translation uses a separate model."
            CaptionEngineChoice.QWEN -> " Multilingual captions in short audio segments. Runtime package: March 2026."
            CaptionEngineChoice.OMNILINGUAL -> " Experimental, short utterance windows. Recognition has no language-forcing input; the source selection labels its output for translation."
            CaptionEngineChoice.PHONON -> if (artifact.profile == com.sal7one.common_jni.speech.SpeechProfile.PHONON_2_5)
                " Phonon 2.5 transducer ONNX (INT8); 25 European languages, including English and Russian. Auto captions have no reported source language; choose a known source for translation. Arabic and Chinese speech are not supported. The raw .bps archive is not importable."
                else " Fermion's five-value weights converted to sherpa transducer ONNX (INT8); English utterance windows. The raw .bps archive is not importable."
            CaptionEngineChoice.MOONSHINE -> " Language-specific captions in short audio segments. This adapter does not provide Moonshine streaming decoding."
            CaptionEngineChoice.VOSK -> " Streaming recognition in the model's fixed language."
            else -> error("Unsupported automatic speech source: ${artifact.id}")
        }
        return ModelSource(artifact.id, artifact.label, artifact.publisherUrl, artifact.filesUrl,
            "$packaging$guidance ${artifact.license}.", artifact.url.takeIf { artifact.components == null }, artifact.bytes.takeIf { artifact.components == null })
    }

    // The legacy custom-folder adapter remains English→Arabic. Automatic packages for
    // every supported pair are presented separately in the Translation explorer.
    val marian: ModelSource get() {
        val pair = MarianPackage.pairs.first { it.id == "marian-en-ar" }
        return ModelSource(pair.id, "Marian / OPUS-MT · English → Arabic", pair.originalModelCard, pair.filesUrl,
            "Legacy English → Arabic path. The Translation explorer installs the verified four-file INT8 package automatically. For a custom folder, include source.spm, tokenizer.json, encoder_model.onnx and decoder_model_merged.onnx from the same compatible ONNX conversion. Raw checkpoints, GGUF files and speech ZIPs are not this adapter’s input.")
    }
}
