package com.sal7one.hearth.desktop.ui.language

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.*
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** Searchable real language choices, shared by live and typed input without separate settings. */
@Composable
internal fun LanguagePairPicker(state: PipelineLanguageState, onSource: (String) -> Unit, onTarget: (String) -> Unit,
    onSwap: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        LanguagePicker("Source", state.source, state.sources, onSource, enabled, compact)
        HearthButton("", onSwap, tone = ButtonTone.Ghost, size = ButtonSize.Small, icon = HearthIcons.Swap,
            enabled = enabled && state.canSwap)
        LanguagePicker("Output", state.target, state.targets, onTarget, enabled, compact)
    }
}

@Composable
private fun LanguagePicker(title: String, value: String, options: List<LanguageOption>, onSelect: (String) -> Unit, enabled: Boolean, compact: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Box {
        val label = if (!compact) languageLabel(value) else if (value == "auto") "Detect" else value.uppercase(java.util.Locale.ROOT)
        HearthButton(label, { query = ""; expanded = true },
            modifier = Modifier.semantics { contentDescription = "$title language: ${languageLabel(value)}" }, tone = ButtonTone.Secondary,
            size = ButtonSize.Small, icon = HearthIcons.ChevronDown, iconAfter = true, enabled = enabled && options.isNotEmpty())
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(min = 230.dp, max = 340.dp).heightIn(max = 400.dp)) {
            if (options.size > 10) {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search languages") }, singleLine = true,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).width(250.dp))
            }
            options.filter { query.isBlank() || it.label.contains(query, true) || it.code.contains(query, true) }.forEach { option ->
                DropdownMenuItem(text = { Text(option.label, style = HearthType.small,
                    color = if (option.code == value) HearthTheme.colors.accent else HearthTheme.colors.text) },
                    onClick = { expanded = false; onSelect(option.code) })
            }
        }
    }
}
