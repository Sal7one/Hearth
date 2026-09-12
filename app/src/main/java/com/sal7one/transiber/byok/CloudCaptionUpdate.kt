package com.sal7one.transiber.byok

/** Source and translated runs have separate identities; token positions are not word alignment. */
data class CloudCaptionUpdate(
    val id: Long, val revision: Long, val text: String, val language: String?,
    val translated: Boolean, val complete: Boolean,
)
interface StructuredCaptionClient : StreamingSttClient {
    var onCaption: ((CloudCaptionUpdate) -> Unit)?
    var onTranslatedInterim: ((String) -> Unit)?
}
