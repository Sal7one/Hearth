package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.ui.components.ModelSearchField

@Composable
internal fun VoiceMenu(label: String, selected: String, values: List<Pair<String, String>>, choose: (String) -> Unit) {
    val uiText = rememberUiText()
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    OutlinedButton(onClick = { query = ""; expanded = true }, enabled = values.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = SettingsLayout.touchTarget)) {
        Text("$label: ${values.firstOrNull { it.first == selected }?.second ?: uiText(UiR.string.choose_value)}")
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(label) },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text(uiText(UiR.string.ui_close_bbfa7)) } },
        text = {
            val matches = values.filter { (id, title) -> voiceMenuMatches(query, id, title) }
            val scroll = rememberLazyListState(initialFirstVisibleItemIndex = values.indexOfFirst { it.first == selected }.coerceAtLeast(0))
            LaunchedEffect(query) { if (query.isNotBlank()) scroll.scrollToItem(0) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (values.size > 6) ModelSearchField(query, { query = it }, label = uiText(UiR.string.model_picker_search))
                if (matches.isEmpty()) Text(uiText(UiR.string.model_picker_no_matches), style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.heightIn(max = 360.dp), state = scroll) {
                    items(matches.size, key = { matches[it].first }) { index ->
                        val (id, text) = matches[index]
                        TextButton(onClick = { choose(id); expanded = false }, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsLayout.touchTarget).semantics { this.selected = id == selected }) {
                            Text(if (id == selected) "✓ $text" else text)
                        }
                    }
                }
            }
        })
}
