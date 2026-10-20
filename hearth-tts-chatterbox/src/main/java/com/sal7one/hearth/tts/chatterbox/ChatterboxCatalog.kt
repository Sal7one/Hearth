package com.sal7one.hearth.tts.chatterbox

data class ChatterboxAsset(val filename: String, val bytes: Long, val sha256: String, val url: String)
/** Pinned V3 ONNX Q4 stack. Default reference gender is unverified. */
object ChatterboxCatalog {
    const val source = "https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX"
    const val revision = "19b94afe41de738624b2f3afc75b4cd56221fbeb"
    const val project = "https://github.com/resemble-ai/chatterbox"
    const val license = "MIT"
    // Initial tested native frontends; Chinese/Japanese/Russian frontends are intentionally excluded.
    val languages = setOf("ar", "en")
    val voices = listOf("default")
    val assets = listOf(
        ChatterboxAsset("onnx/speech_encoder.onnx", 1204600, "012a9583c0a9560afa11307b585f1e799fca0e1701bdae53fa34766da78f8eae", "https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/resolve/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/speech_encoder.onnx"),
        ChatterboxAsset("onnx/speech_encoder.onnx_data", 591274880, "f1f663a1a03aa885f8f0fcaa730dd8dfbce8caad514093b13c9682a4a2842990", "https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/resolve/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/speech_encoder.onnx_data"),
        ChatterboxAsset("onnx/embed_tokens.onnx", 17616, "c8dd692ee8dfc9099baf39c8074e9bcc0a760e8c25ae1cac4a8c62c7cf4536bc", "https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/resolve/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/embed_tokens.onnx"),
        ChatterboxAsset("onnx/embed_tokens.onnx_data", 68808704, "d263116bec8ae582953c99bdffa9ecd53914d1ae2732da8455e361ed5baa2a9a", "https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/resolve/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/embed_tokens.onnx_data"),
        ChatterboxAsset("onnx/language_model_q4.onnx", 228633, "91846c24706116f87b39aac5b13aa1a67243b6f973bc433327d143b8eff9fde6", "https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/resolve/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/language_model_q4.onnx"),
        ChatterboxAsset("onnx/language_model_q4.onnx_data", 353621248, "621a963492f871ccc841592c6ca117bf88dbea4c0c25d61be4391197c8b878b0", "https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/resolve/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/language_model_q4.onnx_data"),
        ChatterboxAsset("onnx/conditional_decoder.onnx", 6371670, "f494d8fed14f269e969a58ccbd627cdfb5eb889d0816c7fde09621fdcb160128", "https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/resolve/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/conditional_decoder.onnx"),
        ChatterboxAsset("onnx/conditional_decoder.onnx_data", 533970816, "f41f149c1a30c273133739e873294e28dbc3c4ae6376b0a501e9cd21de286835", "https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/resolve/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/conditional_decoder.onnx_data"),
        ChatterboxAsset("tokenizer.json", 72765, "8763bb615f5e0ad49fbb53b65d371e37961f64bb0a55b96c0205f4a59ba5be13", "https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/resolve/19b94afe41de738624b2f3afc75b4cd56221fbeb/tokenizer.json"),
        ChatterboxAsset("default_voice.wav", 714320, "3ebc531cdaba358a327099c1c4f0448026719957bcf4d8e9868767f227e02f4e", "https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/resolve/19b94afe41de738624b2f3afc75b4cd56221fbeb/default_voice.wav"),
    )
}
