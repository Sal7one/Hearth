package com.sal7one.hearth.integration

/** MIME routing only. The platform pipeline owns container/codec validation and audio decoding. */
internal object IntegrationMedia {
    const val MAX_BYTES = 64 * 1024 * 1024
    val extensions = mapOf(
        "video/mp4" to ".mp4",
        "video/quicktime" to ".mov",
        "audio/mp4" to ".m4a",
        "audio/x-m4a" to ".m4a",
        "audio/mpeg" to ".mp3",
        "audio/aac" to ".aac",
    )
}
