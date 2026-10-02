package com.sal7one.transiber.caption

import com.sal7one.transiber.caption.history.SavedCaption

internal fun knownCaptionHistoryLanguage(value: String?): String? =
    value?.takeUnless { it in setOf("", "auto", "model", "und", "mul") }

/** Whisper translation tasks and GPT live translation expose target text only.
 * Never relabel it as original CC. The English pivot can later be replaced by the target translation.
 * Soniox uses per-run translated metadata instead: independent runs are not assumed word-aligned.
 */
internal fun captionHistoryText(id: Long, text: String, created: Long, route: CaptionTranslationRoute,
    engineTranslatesToTarget: Boolean, target: String, sourceLanguage: String?, engine: String, source: String): SavedCaption {
    val translated = engineTranslatesToTarget || route.sttToEnglish
    return SavedCaption(id, created, original = if (translated) "" else text,
        translation = if (translated) text else "", sourceLanguage = knownCaptionHistoryLanguage(sourceLanguage),
        translationLanguage = if (engineTranslatesToTarget) target else if (route.sttToEnglish) "en" else null,
        engine = engine, source = source)
}
