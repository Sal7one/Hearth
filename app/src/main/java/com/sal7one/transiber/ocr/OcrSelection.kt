package com.sal7one.transiber.ocr

import com.sal7one.transiber.translation.CloudTranslationLanguages
import com.sal7one.transiber.translation.TranslationOptions

/** The reader and destination have independent capabilities. Never silently replace a destination. */
internal data class OcrSelection(
    val profileId: String = "latin", val source: String = "en", val target: String = "ar",
    val providerId: String = "local", val translate: Boolean = true,
) {
    val profile get() = OcrCatalog.profile(profileId)
    fun withProfile(id: String): OcrSelection {
        val next = OcrCatalog.profile(id)
        return copy(profileId=id, source=source.takeIf { it in next.languages } ?: next.languages.first())
    }
    fun withSource(code: String): OcrSelection {
        val next = if(code in profile.languages) profile else
            OcrCatalog.profiles.firstOrNull { code in it.languages } ?: error("No OCR model supports $code")
        return copy(profileId=next.id, source=code)
    }
    companion object {
        val sourceLanguages get() = OcrCatalog.profiles.flatMap { it.languages }.toSet()
    }
}

/** Unknown cloud capabilities must not fall back to a different local translator. */
internal fun ocrTranslationTargets(selection: OcrSelection, localId: String,
    cloud: CloudTranslationLanguages?, networkAllowed: Boolean): Set<String> =
    if(selection.providerId != "local" && selection.providerId.isNotBlank()) {
        if(networkAllowed) cloud?.targetLanguages.orEmpty().filter { cloud?.supports(selection.source,it)==true }.toSet()
        else emptySet()
    } else if(localId == TranslationOptions.ML_KIT && !networkAllowed) emptySet()
    else TranslationOptions.languages(localId).filter { TranslationOptions.supports(localId,selection.source,it) }.toSet()
