package com.sal7one.transiber.ocr

import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.UiText
import com.sal7one.transiber.translation.CloudTranslationLanguages
import com.sal7one.transiber.translation.TextTranslationProvider
import com.sal7one.transiber.translation.TranslationOptions

internal enum class OcrTranslationProblem { CHOOSE_MODEL, UNKNOWN_MODEL, EXPLICIT_SOURCE, SAME_LANGUAGE, UNKNOWN_PROVIDER, CLOUD_LANGUAGES, OFFLINE_PROVIDER, UNSUPPORTED_PAIR }

/** Reading uses the declared image language, never a speech auto-detection preference. */
internal fun ocrTranslationProblem(selection:OcrSelection,localId:String,cloud:CloudTranslationLanguages?,networkAllowed:Boolean):OcrTranslationProblem? {
    if(!selection.translate)return null
    if(selection.source in setOf("", "auto", "model", "und", "mul"))return OcrTranslationProblem.EXPLICIT_SOURCE
    if(selection.source==selection.target)return OcrTranslationProblem.SAME_LANGUAGE
    if(selection.providerId.isNotBlank() && selection.providerId!="local") {
        if(TextTranslationProvider.entries.none {it.id==selection.providerId})return OcrTranslationProblem.UNKNOWN_PROVIDER
        if(!networkAllowed)return OcrTranslationProblem.OFFLINE_PROVIDER
        if(cloud==null)return OcrTranslationProblem.CLOUD_LANGUAGES
    } else {
        if(localId.isBlank())return OcrTranslationProblem.CHOOSE_MODEL
        if(localId==TranslationOptions.ML_KIT && !networkAllowed)return OcrTranslationProblem.OFFLINE_PROVIDER
        if(TranslationOptions.languages(localId).isEmpty())return OcrTranslationProblem.UNKNOWN_MODEL
    }
    return if(selection.target in ocrTranslationTargets(selection,localId,cloud,networkAllowed))null else OcrTranslationProblem.UNSUPPORTED_PAIR
}
internal fun UiText.ocrTranslationIssue(selection:OcrSelection,localId:String,cloud:CloudTranslationLanguages?,networkAllowed:Boolean):String? {
    val label=TextTranslationProvider.entries.firstOrNull {it.id==selection.providerId}?.label ?: TranslationOptions.label(localId)
    return when(ocrTranslationProblem(selection,localId,cloud,networkAllowed)) {
        null -> null
        OcrTranslationProblem.CHOOSE_MODEL -> invoke(R.string.ocr_translation_choose_model)
        OcrTranslationProblem.UNKNOWN_MODEL -> invoke(R.string.ocr_translation_unknown_model,localId)
        OcrTranslationProblem.EXPLICIT_SOURCE -> invoke(R.string.ocr_translation_explicit_source)
        OcrTranslationProblem.SAME_LANGUAGE -> invoke(R.string.ui_choose_a_different_destination_to_translate_or_turn_translation_o_55817)
        OcrTranslationProblem.UNKNOWN_PROVIDER -> invoke(R.string.ocr_translation_unknown_provider,selection.providerId)
        OcrTranslationProblem.CLOUD_LANGUAGES -> invoke(R.string.ocr_translation_cloud_needs_languages,label)
        OcrTranslationProblem.OFFLINE_PROVIDER -> invoke(R.string.ocr_translation_offline_provider,label)
        OcrTranslationProblem.UNSUPPORTED_PAIR -> invoke(R.string.ui_1_s_does_not_support_2_s_3_s_choose_a_supported_language_pair_or_c8a2b,label,selection.source,selection.target)
    }
}
