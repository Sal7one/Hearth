package com.sal7one.transiber.models

import com.sal7one.common_jni.speech.SpeechProfile

/** Exact publisher artifacts understood by the on-phone installer. No extension-based guessing. */
internal data class SpeechDownload(
    val profile: SpeechProfile,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val archiveRoot: String?,
    val roles: Map<String, String>,
) {
    val fileName get() = url.substringAfterLast('/')
}

internal object SpeechDownloads {
    private const val sherpa = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    val all = listOf(
        SpeechDownload(SpeechProfile.MOONSHINE_TINY_EN,
            sherpa + "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2", 29858559,
            "9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d",
            "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27",
            mapOf("model" to "tokens.txt", "encoder" to "encoder_model.ort", "decoder" to "decoder_model_merged.ort")),
        SpeechDownload(SpeechProfile.MOONSHINE_BASE_EN,
            sherpa + "sherpa-onnx-moonshine-base-en-quantized-2026-02-27.tar.bz2", 111266225,
            "43232c1d13013d37317163baec3135bd771a186a4356f28c889bab453bb0e891",
            "sherpa-onnx-moonshine-base-en-quantized-2026-02-27",
            mapOf("model" to "tokens.txt", "encoder" to "encoder_model.ort", "decoder" to "decoder_model_merged.ort")),
        SpeechDownload(SpeechProfile.QWEN3_ASR_0_6B,
            sherpa + "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2", 878702423,
            "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96",
            "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
            mapOf("frontend" to "conv_frontend.onnx", "encoder" to "encoder.int8.onnx", "decoder" to "decoder.int8.onnx", "tokenizer" to "tokenizer")),
        SpeechDownload(SpeechProfile.NEMOTRON_3_5_ASR_0_6B,
            "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/resolve/1c8deaecc64b91f034d73e08dd8b64625eb3395d/nemotron-3.5-asr-streaming-0.6b.q8_0.gguf", 741548352,
            "a5c435f294eea8f88ce68dd27b8c3bfea7f777cb2fbba04fcd30eaa555f429ae", null,
            mapOf("model" to "nemotron-3.5-asr-streaming-0.6b.q8_0.gguf")),
    )
    fun find(id: String) = all.firstOrNull { it.profile.id == id }
}
