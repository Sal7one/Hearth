package com.sal7one.transiber.caption

/** Provenance of the engine's primary text, independent of the selected display mode. */
enum class CaptionTextRole { SOURCE, TARGET, INTERMEDIATE }

internal fun captionPrimaryRole(route: CaptionTranslationRoute, engineTranslatesToTarget: Boolean): CaptionTextRole = when {
    engineTranslatesToTarget || route == CaptionTranslationRoute.ENGLISH_TASK -> CaptionTextRole.TARGET
    route == CaptionTranslationRoute.ENGLISH_PIVOT -> CaptionTextRole.INTERMEDIATE
    else -> CaptionTextRole.SOURCE
}

internal data class CaptionVisiblePair(val original: String? = null, val translation: String? = null) {
    val empty: Boolean get() = original == null && translation == null
    val text: String get() = listOfNotNull(original, translation).joinToString("\n")
}

internal enum class CaptionEmptyText { LISTENING, WAITING_TRANSLATION, ORIGINAL_UNAVAILABLE }

/** Phone, TV and clipboard use the same strict choice; missing translation never exposes source text. */
internal object CaptionPresentation {
    fun display(config: CaptionOverlayConfig): CaptionDisplay =
        if (config.mode == CaptionMode.CAPTIONS) CaptionDisplay.ORIGINAL else config.display

    fun pair(original: String, translation: String?, role: CaptionTextRole, display: CaptionDisplay): CaptionVisiblePair {
        val source = original.takeIf { role == CaptionTextRole.SOURCE && it.isNotBlank() }
        val target = translation?.takeIf { it.isNotBlank() }
            ?: original.takeIf { role == CaptionTextRole.TARGET && it.isNotBlank() }
        return CaptionVisiblePair(
            original = source.takeUnless { display == CaptionDisplay.TRANSLATED },
            translation = target.takeUnless { display == CaptionDisplay.ORIGINAL },
        )
    }

    fun line(line: CaptionLine, config: CaptionOverlayConfig): CaptionVisiblePair =
        pair(line.original, line.translation, line.primaryRole, display(config))

    fun partial(content: CaptionReadingSnapshot, config: CaptionOverlayConfig): CaptionVisiblePair =
        pair(content.partial, content.partialTranslation, content.partialRole, display(config))

    fun finals(content: CaptionReadingSnapshot, config: CaptionOverlayConfig, reading: Boolean): List<CaptionLine> =
        CaptionReading.visibleFinals(content.copy(history = content.history.filter { !line(it, config).empty }),
            config.historyLines, reading)

    /** Copies the held snapshot's selected text, without status/error messages or hidden source captions. */
    fun copyText(content: CaptionReadingSnapshot, config: CaptionOverlayConfig): String =
        (content.history.map { line(it, config).text } + partial(content, config).text)
            .filter(String::isNotBlank).joinToString("\n")

    fun emptyText(content: CaptionReadingSnapshot, config: CaptionOverlayConfig): CaptionEmptyText {
        val hasText = content.history.any { it.original.isNotBlank() || !it.translation.isNullOrBlank() } ||
            content.partial.isNotBlank() || !content.partialTranslation.isNullOrBlank()
        return when {
            !hasText -> CaptionEmptyText.LISTENING
            display(config) == CaptionDisplay.ORIGINAL -> {
                val targetOnly = content.history.any { it.primaryRole != CaptionTextRole.SOURCE && it.original.isNotBlank() } ||
                    (content.partialRole != CaptionTextRole.SOURCE && content.partial.isNotBlank())
                if (targetOnly) CaptionEmptyText.ORIGINAL_UNAVAILABLE else CaptionEmptyText.LISTENING
            }
            else -> CaptionEmptyText.WAITING_TRANSLATION
        }
    }
}
