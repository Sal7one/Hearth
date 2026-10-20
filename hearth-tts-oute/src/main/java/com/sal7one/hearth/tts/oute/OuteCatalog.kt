package com.sal7one.hearth.tts.oute

data class OuteAsset(val filename: String, val bytes: Long, val sha256: String, val url: String)

/** Oute 1.0's Llama generator and IBM DAC v1.0 codec are packed in one GGMM file. */
object OuteCatalog {
    const val project = "https://github.com/foldl/chatllm.cpp"
    const val modelCard = "https://huggingface.co/OuteAI/Llama-OuteTTS-1.0-1B"
    const val source = "https://modelscope.cn/models/judd2024/chatllm_quantized_oute-tts"
    val filesUrl get() = "https://modelscope.cn/api/v1/models/judd2024/chatllm_quantized_oute-tts/repo/files?Revision=$revision&Recursive=true"
    const val revision = "a1b7a4fc64949c9d6a3206422830df41009f1ad7"
    const val referenceRevision = "f5eac6e70d792844c6a6959d900a47af2c061a5b"
    const val license = "CC-BY-NC-SA-4.0; Llama 3.2 Community License; IBM DAC CDLA-Permissive-2.0; reference Apache-2.0"
    val languages = setOf("ar", "en")
    // This is an English reference. Arabic is cross-lingual and may retain its accent.
    val voices = listOf("EN-FEMALE-1-NEUTRAL")
    val assets = listOf(
        OuteAsset("oute-tts-1b.bin", 1438416240, "e4e14b573459a8a4958e32e39678d2f0ac61cf73ccadb6dbda1f8fde1f8ade83",
            "https://modelscope.cn/api/v1/models/judd2024/chatllm_quantized_oute-tts/repo?Revision=$revision&FilePath=oute-tts-1b.bin"),
        OuteAsset("en-female-1-neutral.json", 25295, "6bb6bacca60c9759741d849c2baf2af3f6263a51b759d67826ef39e1efc4436c",
            "https://raw.githubusercontent.com/edwko/OuteTTS/$referenceRevision/outetts/version/v3/default_speakers/json/en-female-1-neutral.json"),
    )
}
