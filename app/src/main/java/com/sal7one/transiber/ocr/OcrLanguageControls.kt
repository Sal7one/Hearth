package com.sal7one.transiber.ocr

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
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            listOf("source" to "Text in image", "target" to "Translate to").forEach { (which,title) ->
                val code=if(which=="source") selection.source else selection.target
                OutlinedCard(onClick={picker=which},enabled=enabled,modifier=Modifier.weight(1f).heightIn(min=80.dp)
                    .semantics { contentDescription="$title, ${LanguageCatalog.option(code).englishName}" }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(title,style=MaterialTheme.typography.labelMedium)
                        Text(LanguageCatalog.option(code).nativeName,style=MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        TextButton(onClick=onModel,enabled=enabled) { Text("Reader: ${selection.profile.label} · Change") }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Switch(selection.translate,{onChange(selection.copy(translate=it))},enabled=enabled,
                modifier=Modifier.semantics { contentDescription="Translate recognized text" })
            Column(Modifier.padding(start=8.dp)) {
                Text(if(selection.translate) "Translate recognized text" else "Original text only",style=MaterialTheme.typography.bodyMedium)
                if(selection.translate)Text(translator,style=MaterialTheme.typography.bodySmall)
            }
        }
        if(selection.translate && selection.target !in targets) Text(
            if(selection.source==selection.target) "Choose a different destination to translate, or turn translation off for original text."
            else "$translator does not support this pair yet. Change Translate to or choose another translator in settings.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
    }
    picker?.let { which -> Dialog(onDismissRequest={picker=null},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) { LanguagePickerContent(
            title=if(which=="source") "Text in image" else "Translate to",
            choices=CaptionLanguageChoices(if(which=="source") OcrSelection.sourceLanguages else targets,
                if(which=="source") "Changing language selects a compatible OCR reader when needed. Manga and Meiki read Japanese; use Change reader to choose between them."
                else "$translator · translations from ${LanguageCatalog.option(selection.source).englishName}. Destinations depend on the translator, not the OCR reader."),
            selected=if(which=="source") selection.source else selection.target,
            onSelect={ code -> onChange(if(which=="source") selection.withSource(code) else selection.copy(target=code,translate=true));picker=null },
            onDismiss={picker=null}) }
    } }
}
