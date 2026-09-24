package com.sal7one.transiber.translation

import android.content.Context
import com.sal7one.common_jni.translation.TranslationCatalog
import java.io.File

/** Runtime capabilities shared by setup, overlay and inference routing. */
object TranslationOptions {
    const val ML_KIT = "ml-kit"
    const val MARIAN_EN_AR = "marian-en-ar"
    const val MARIAN_RU_EN = "marian-ru-en"
    const val MARIAN_ZH_EN = "marian-zh-en"
    const val MARIAN_RU_AR_VIA_EN = "marian-ru-ar-via-en"
    const val MARIAN_ZH_AR_VIA_EN = "marian-zh-ar-via-en"
    val mlKitCodes: Set<String> get() = PlatformTranslation.languages
    fun sourceLanguages(id: String): Set<String> = if (id == ML_KIT) mlKitCodes
        else if (MarianPackage.find(id) != null) setOf(checkNotNull(MarianPackage.find(id)).source)
        else if (MarianCascade.find(id) != null) setOf(checkNotNull(MarianCascade.find(id)).source)
        else TranslationCatalog.models.firstOrNull { it.id == id }?.sourceLanguages.orEmpty()
    fun targetLanguages(id: String, source: String = "auto"): Set<String> = if (id == ML_KIT) mlKitCodes
        else if (MarianPackage.find(id) != null) setOf(checkNotNull(MarianPackage.find(id)).target)
        else if (MarianCascade.find(id) != null) setOf(checkNotNull(MarianCascade.find(id)).target)
        else TranslationCatalog.models.firstOrNull { it.id == id }?.let { spec ->
            if (source in setOf("auto", "model", "und", "mul", "")) spec.targetLanguages
            else spec.targetLanguages.filterTo(mutableSetOf()) { spec.supports(source, it) }
        }.orEmpty()
    fun languages(id: String): Set<String> = sourceLanguages(id) + targetLanguages(id)
    fun supports(id: String, source: String, target: String): Boolean = source != target &&
        if (id == ML_KIT) source in mlKitCodes && target in mlKitCodes
        else if (MarianPackage.find(id) != null) MarianPackage.find(id)?.let { source == it.source && target == it.target } == true
        else if (MarianCascade.find(id) != null) MarianCascade.find(id)?.let { source == it.source && target == it.target } == true
        else TranslationCatalog.models.firstOrNull { it.id == id }?.supports(source, target) == true
    /** Use the same installed-model identity in setup, captions and shortcut preflight. */
    fun installed(context: Context, id: String): Boolean = when {
        id == ML_KIT -> PlatformTranslation.available
        MarianPackage.find(id) != null -> MarianPackage.installed(context, checkNotNull(MarianPackage.find(id))) != null
        MarianCascade.find(id) != null -> MarianCascade.installed(context, checkNotNull(MarianCascade.find(id)))
        TranslationCatalog.models.any { it.id == id } ->
            LocalTranslationModels(File(context.filesDir, "translation-models")).installed().any { it.id == id }
        else -> false
    }
    fun label(id: String): String = if (id == ML_KIT) "ML Kit · lightweight language packs"
        else if (MarianPackage.find(id) != null) MarianPackage.find(id)!!.let {
            "Marian / OPUS-MT · ${com.sal7one.common_jni.translation.TranslationLanguages.label(it.source)} → " +
                "${com.sal7one.common_jni.translation.TranslationLanguages.label(it.target)} · ${it.downloadBytes / 1_048_576} MiB"
        }
        else if (MarianCascade.find(id) != null) MarianCascade.find(id)!!.let {
            "Marian / OPUS-MT · ${com.sal7one.common_jni.translation.TranslationLanguages.label(it.source)} → English → " +
                "${com.sal7one.common_jni.translation.TranslationLanguages.label(it.target)} · 2 models · ${it.downloadBytes / 1_048_576} MiB"
        }
        else TranslationCatalog.models.firstOrNull { it.id == id }?.label ?: "Select a translator"
}
