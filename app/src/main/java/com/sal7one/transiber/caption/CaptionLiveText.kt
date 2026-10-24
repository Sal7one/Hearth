package com.sal7one.transiber.caption

/** Presentation only: the engine's utterance boundaries remain intact in history. */
internal data class CaptionLiveChunk(val text: String, val faded: Boolean)

internal data class CaptionLiveParagraph(
    val translated: Boolean,
    val chunks: List<CaptionLiveChunk>,
) {
    val text: String get() = chunks.joinToString(" ") { it.text }
}

/**
 * A short recognition window is not a visual newline. Flow the selected live
 * utterances through the available width, keeping the two language tracks
 * separate. The existing previous-segment limit and display/provenance rules
 * still decide exactly which text can be shown.
 */
internal object CaptionLiveText {
    fun paragraphs(content: CaptionReadingSnapshot, config: CaptionOverlayConfig): List<CaptionLiveParagraph> {
        val finals = CaptionPresentation.finals(content, config, reading = false)
        val partial = CaptionPresentation.partial(content, config)
        val ordered = mutableListOf<CaptionLiveParagraph>()
        fun appendText(text: String, faded: Boolean, translated: Boolean) {
            val chunk = CaptionLiveChunk(text, faded)
            // Keep source/target chronology in bilingual mode and when showing
            // a temporary pending source. Following the live bottom must never
            // put older translated words below the newest source caption.
            val last = ordered.lastOrNull()
            if (last?.translated == translated) {
                ordered[ordered.lastIndex] = last.copy(chunks = last.chunks + chunk)
            } else ordered += CaptionLiveParagraph(translated, listOf(chunk))
        }
        fun append(pair: CaptionVisiblePair, faded: Boolean) {
            pair.original?.let { appendText(it, faded, translated = false) }
            pair.translation?.let { appendText(it, faded, translated = true) }
        }
        finals.forEachIndexed { index, line ->
            append(CaptionPresentation.line(line, config), !partial.empty || index < finals.lastIndex)
        }
        if (!partial.empty) append(partial, faded = false)
        return ordered
    }
}
