package com.sal7one.transiber.translation

import com.sal7one.common_jni.translation.TranslationCatalog

/** Runtime capabilities shared by setup, overlay and inference routing. */
object TranslationOptions {
    const val ML_KIT = "ml-kit"
    val mlKitCodes: Set<String> get() = PlatformTranslation.languages
    fun languages(id: String): Set<String> = if (id == ML_KIT) mlKitCodes
        else TranslationCatalog.models.firstOrNull { it.id == id }?.targetLanguages.orEmpty()
    fun supports(id: String, source: String, target: String): Boolean = source != target &&
        if (id == ML_KIT) source in mlKitCodes && target in mlKitCodes
        else TranslationCatalog.models.firstOrNull { it.id == id }?.supports(source, target) == true
    fun label(id: String): String = if (id == ML_KIT) "ML Kit · lightweight language packs"
        else TranslationCatalog.models.firstOrNull { it.id == id }?.label ?: "Select a translator"
}
