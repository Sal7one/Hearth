package com.sal7one.transiber.translation

import com.sal7one.common_jni.translation.TranslationCatalog

/** Runtime capabilities shared by setup, overlay and inference routing. */
object TranslationOptions {
    const val ML_KIT = "ml-kit"
    val mlKitCodes = "af sq ar be bn bg ca zh hr cs da nl en eo et fi fr gl ka de el gu ht he hi hu is id ga it ja kn ko lv lt mk ms mt mr no fa pl pt ro ru sk sl es sw sv fil ta te th tr uk ur vi cy".split(' ').toSet()
    fun languages(id: String): Set<String> = if (id == ML_KIT) mlKitCodes
        else TranslationCatalog.models.firstOrNull { it.id == id }?.targetLanguages.orEmpty()
    fun label(id: String): String = if (id == ML_KIT) "ML Kit · lightweight language packs"
        else TranslationCatalog.models.firstOrNull { it.id == id }?.label ?: "Select a translator"
}
