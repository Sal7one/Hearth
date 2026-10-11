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
        SpeechDownload(SpeechProfile.OMNILINGUAL_CTC_300M_V2,
            sherpa + "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2", 292313120,
            "951b32409aade32bd525310bb39e9666773ba3fc611a39e817f620936d76c631",
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05",
            mapOf("model" to "model.int8.onnx", "tokenizer" to "tokens.txt")),
        SpeechDownload(SpeechProfile.PARAKEET_TDT_0_6B_V3,
            sherpa + "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2", 487170055,
            "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf",
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
            mapOf("model" to "tokens.txt", "frontend" to "joiner.int8.onnx", "encoder" to "encoder.int8.onnx", "decoder" to "decoder.int8.onnx")),
        SpeechDownload(SpeechProfile.SENSE_VOICE_SMALL_BASE_INT8,
            sherpa + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2", 163002883,
            "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e",
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
            mapOf("model" to "model.int8.onnx", "tokenizer" to "tokens.txt")),
        SpeechDownload(SpeechProfile.SENSE_VOICE_SMALL_INT8,
            sherpa + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2", 165783878,
            "7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63",
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            mapOf("model" to "model.int8.onnx", "tokenizer" to "tokens.txt")),
        SpeechDownload(SpeechProfile.SENSE_VOICE_SMALL_FP32,
            sherpa + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2025-09-09.tar.bz2", 886547748,
            "bce28acdb0fb3a5b1e0b93f8b202e904ea1d2eca4f3a4c0cd5006626f10d540e",
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2025-09-09",
            mapOf("model" to "model.onnx", "tokenizer" to "tokens.txt")),
        SpeechDownload(SpeechProfile.PHONON_2,
            "https://github.com/Sal7one/Hearth/releases/download/v0.23.0/phonon-2-hearth-int8.tar.bz2", 339918748,
            "6b6cd464a3271f25f0bcbde0c12839516cd1e9b49f8292368da6a1931a2fa805", "phonon-2-hearth-int8",
            mapOf("model" to "tokens.txt", "frontend" to "joiner.int8.onnx", "encoder" to "encoder.int8.onnx", "decoder" to "decoder.int8.onnx")),
        SpeechDownload(SpeechProfile.NEMOTRON_3_5_ASR_0_6B,
            "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/resolve/1c8deaecc64b91f034d73e08dd8b64625eb3395d/nemotron-3.5-asr-streaming-0.6b.q8_0.gguf", 741548352,
            "a5c435f294eea8f88ce68dd27b8c3bfea7f777cb2fbba04fcd30eaa555f429ae", null,
            mapOf("model" to "nemotron-3.5-asr-streaming-0.6b.q8_0.gguf")),
    )
    fun find(id: String) = all.firstOrNull { it.profile.id == id }
}
