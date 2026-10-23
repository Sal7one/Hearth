package com.sal7one.hearth.core.vision

/** One set of content pins for Android and desktop; weights are never bundled with the runtime. */
data class VisionArtifact(val id: String, val owner: String, val repository: String, val revision: String,
                          val filename: String, val bytes: Long, val sha256: String) {
    val url get() = "https://huggingface.co/$owner/$repository/resolve/$revision/$filename"
}
object MangaArtifacts {
    val detector = VisionArtifact("ocr-comic-detector","ogkalu","comic-text-and-bubble-detector",
        "16e8a622f91fabc6b5b65c96d32d1183f8843546","detector-v4-s_int8.onnx",11120765,
        "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79")
    val encoder = VisionArtifact("ocr-manga-encoder","onnx-community","manga-ocr-base-ONNX",
        "f9023406bb2f6b17df67bc4a327c56ecd20611f0","onnx/encoder_model_uint8.onnx",86967805,
        "a73e7a9959f3412f4d0ab60c8cd0f71c29e7e29a2e52a1e184ad6f2be3b892e3")
    val decoder = VisionArtifact("ocr-manga-decoder","onnx-community","manga-ocr-base-ONNX",
        "f9023406bb2f6b17df67bc4a327c56ecd20611f0","onnx/decoder_model_int8.onnx",29627936,
        "2e7177d2b0a59f1c612b694ed70c13971bee765cc2b2bc7bc9376e4753652f27")
    val files = listOf(detector,encoder,decoder)
    const val vocabularySha256 = "edee83dd448425ef4de06704e0bc4e18ee8970a502a4b3a0913bd6e7085dc951"
}
