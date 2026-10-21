package com.sal7one.hearth.desktop.ui.screens.overlay

import com.sal7one.hearth.core.CaptionSegment
import com.sal7one.hearth.core.PipelineSnapshot
import com.sal7one.hearth.desktop.overlayCaption
import java.util.Locale

/**
 * Resolves the overlay's caption from the real session and the persisted settings.
 * Reuses [overlayCaption] — the same ordered/partial logic the previous UI used and
 * that its tests cover — rather than re-deriving "latest line" here.
 */
internal fun overlayState(
    snapshot: PipelineSnapshot?,
    translationEnabled: Boolean,
    targetLanguage: String,
    settings: OverlaySettings,
): OverlayState {
    val segments: List<CaptionSegment> = when {
        settings.showPartials -> snapshot?.segments.orEmpty()
        else -> snapshot?.segments.orEmpty().filter { it.final }
    }
    val caption = overlayCaption(
        segments = segments,
        showOriginal = settings.showOriginal,
        translationEnabled = translationEnabled,
        showOriginalWhilePending = true,
    )
    return OverlayState(
        settings = settings,
        caption = caption?.let { OverlayCaptionView(it.text, it.original, it.partial) },
        targetRtl = targetLanguage.lowercase().substringBefore('-') in RTL_LANGUAGES,
        translationEnabled = translationEnabled,
    )
}

private val RTL_LANGUAGES = setOf("ar", "he", "fa", "ur", "ps", "sd", "ug", "yi", "dv", "ku", "ckb")

/** "العربية" for ar, the tag itself when the JVM has no name for it. */
internal fun overlayLanguageName(code: String): String {
    if (code.isBlank() || code == "auto") return "Detect"
    val display = runCatching { Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH) }.getOrNull()
    return display?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code.uppercase()
}
