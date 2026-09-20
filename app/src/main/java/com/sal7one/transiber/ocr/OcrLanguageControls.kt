package com.sal7one.transiber.ocr

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.caption.CaptionLanguageChoices
import com.sal7one.transiber.caption.LanguagePickerContent

/** Both entry points expose the same labelled controls, selected-row picker and capability explanation. */
@Composable internal fun OcrLanguageControls(selection: OcrSelection, targets: Set<String>, translator: String,
    onChange: (OcrSelection) -> Unit, onModel: () -> Unit, enabled: Boolean = true) {
    val uiText = rememberUiText()

    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            listOf("source" to uiText(UiR.string.ui_text_in_image_a7bcf), "target" to uiText(UiR.string.ui_translate_to_a1ba6)).forEach { (which,title) ->
                val code=if(which=="source") selection.source else selection.target
                OutlinedCard(onClick={picker=which},enabled=enabled,modifier=Modifier.weight(1f).heightIn(min=80.dp)
                    .semantics { contentDescription="$title, ${uiText.languageName(code)}" }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(title,style=MaterialTheme.typography.labelMedium)
                        Text(LanguageCatalog.option(code).nativeName,style=MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        TextButton(onClick=onModel,enabled=enabled) { Text(uiText(UiR.string.ui_reader_1_s_change_f7947, uiText.label(selection.profile))) }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Switch(selection.translate,{onChange(selection.copy(translate=it))},enabled=enabled,
                modifier=Modifier.semantics { contentDescription=uiText(UiR.string.ui_translate_recognized_text_f69ef) })
            Column(Modifier.padding(start=8.dp)) {
                Text(if(selection.translate) uiText(UiR.string.ui_translate_recognized_text_f69ef) else uiText(UiR.string.ui_original_text_only_7381b),style=MaterialTheme.typography.bodyMedium)
                if(selection.translate)Text(translator,style=MaterialTheme.typography.bodySmall)
            }
        }
        if(selection.translate && selection.target !in targets) Text(
            if(selection.source==selection.target) uiText(UiR.string.ui_choose_a_different_destination_to_translate_or_turn_translation_o_55817)
            else uiText(UiR.string.ui_1_s_does_not_support_this_pair_yet_change_translate_to_or_choose_3834f, translator),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
    }
    picker?.let { which -> Dialog(onDismissRequest={picker=null},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) { LanguagePickerContent(
            title=if(which=="source") uiText(UiR.string.ui_text_in_image_a7bcf) else uiText(UiR.string.ui_translate_to_a1ba6),
            choices=CaptionLanguageChoices(if(which=="source") OcrSelection.sourceLanguages else targets,
                if(which=="source") uiText(UiR.string.ui_changing_language_selects_a_compatible_ocr_reader_when_needed_man_87d2e)
                else uiText(UiR.string.ui_1_s_translations_from_2_s_destinations_depend_on_the_translator_n_83b80, translator, uiText.languageName(selection.source))),
            selected=if(which=="source") selection.source else selection.target,
            onSelect={ code -> onChange(if(which=="source") selection.withSource(code) else selection.copy(target=code,translate=true));picker=null },
            onDismiss={picker=null}) }
    } }
}
