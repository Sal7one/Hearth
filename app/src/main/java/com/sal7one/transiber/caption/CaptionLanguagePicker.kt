package com.sal7one.transiber.caption

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.byok.CloudConfigStore

/** Shared app entry points; overlay supplies its own in-window presentation callback. */
@Composable
fun CaptionLanguageFields(
    config: CaptionOverlayConfig,
    onChange: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
    enabled: Boolean = true,
    showSource: Boolean = true,
    targetChoices: CaptionLanguageChoices? = null,
    onOpenOverlay: ((CaptionLanguagePicker) -> Unit)? = null,
    showTarget: Boolean = true,
) {
    val context = LocalContext.current
    val cloudMode = CloudConfigStore.sttMode(context)
    var picker by remember { mutableStateOf<CaptionLanguagePicker?>(null) }
    val model = rememberCaptionLanguageModel(config)
    val source = CaptionLanguages.source(config, cloudMode, model)
    val target = targetChoices ?: CaptionLanguages.target(config, cloudMode)
    fun open(which: CaptionLanguagePicker) { if (onOpenOverlay != null) onOpenOverlay(which) else picker = which }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showSource) {
            val sourceCode = if (config.engine == CaptionEngineChoice.VOSK) "model" else CaptionLanguages.effectiveSource(config, cloudMode, model)
            LanguageField("Spoken language (CC)", sourceCode, enabled && source.allowsSelection) { open(CaptionLanguagePicker.SOURCE) }
            Text(source.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (config.streamLanguage != sourceCode && config.streamLanguage != "auto") Text(
                "Saved hint ${LanguageCatalog.option(config.streamLanguage).englishName} is not used by this mode.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showTarget && (config.mode == CaptionMode.TRANSLATE || targetChoices != null)) {
            LanguageField("Translate to", config.target.languageTag, enabled) { open(CaptionLanguagePicker.TARGET) }
            if (config.target.languageTag !in target.codes) Text("Choose an output language supported by this setup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    picker?.let { which ->
        Dialog(onDismissRequest = { picker = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), color = MaterialTheme.colorScheme.surface) {
                LanguagePickerContent(
                    title = if (which == CaptionLanguagePicker.SOURCE) "Spoken language (CC)" else "Translate to",
                    choices = if (which == CaptionLanguagePicker.SOURCE) source else target,
                    selected = if (which == CaptionLanguagePicker.SOURCE) {
                        if (config.engine == CaptionEngineChoice.VOSK) "model" else CaptionLanguages.effectiveSource(config, cloudMode, model)
                    } else config.target.languageTag,
                    onSelect = { code ->
                        onChange { if (which == CaptionLanguagePicker.SOURCE) it.copy(streamLanguage = if (code == "model") "auto" else code)
                            else it.copy(target = TranslationTarget.of(code)) }
                        picker = null
                    }, onDismiss = { picker = null },
                )
            }
        }
    }
}

@Composable
private fun LanguageField(title: String, code: String, enabled: Boolean, onClick: () -> Unit) {
    val language = LanguageCatalog.option(code)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!enabled) {
            Text(language.label, Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics {
                contentDescription = "$title, ${language.englishName}"
            }, style = MaterialTheme.typography.bodyLarge)
        } else OutlinedButton(onClick = onClick, enabled = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                contentDescription = "$title, ${language.englishName}"
            }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            Text(language.flag, Modifier.padding(end = 12.dp).clearAndSetSemantics { })
            Text(language.label, Modifier.weight(1f), textAlign = TextAlign.Start)
            if (enabled) Icon(Icons.Default.ExpandMore, null, Modifier.padding(start = 8.dp))
        }
    }
}

/** A vertical list provides normal TalkBack scroll/select actions; flags never replace labels. */
@Composable
fun LanguagePickerContent(
    title: String,
    choices: CaptionLanguageChoices,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    searchable: Boolean = true,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(choices.codes, query) { LanguageCatalog.choices(choices.codes, query) }
    Column(modifier.fillMaxSize().semantics { paneTitle = title }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back without changing language")
            }
            Text(title, Modifier.weight(1f).padding(start = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        }
        if (searchable) OutlinedTextField(value = query, onValueChange = { query = it },
            label = { Text("Search languages") }, placeholder = { Text("Native name, English name or code") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().selectableGroup(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "note") { Text(choices.note, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (rows.isEmpty()) item(key = "empty") { Text("No matching languages", Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
            items(rows, key = { it.code }) { language ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .selectable(selected = language.code == selected, role = Role.RadioButton, onClick = { onSelect(language.code) })
                    .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(language.flag, Modifier.padding(end = 16.dp).clearAndSetSemantics { })
                    Column(Modifier.weight(1f)) {
                        Text(language.nativeName, style = MaterialTheme.typography.bodyLarge)
                        if (language.nativeName != language.englishName) Text(language.englishName,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RadioButton(selected = language.code == selected, onClick = null, modifier = Modifier.padding(start = 8.dp))
                }
            }

        }
    }
}
