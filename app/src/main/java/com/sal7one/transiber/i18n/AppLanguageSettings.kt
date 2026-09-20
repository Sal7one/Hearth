package com.sal7one.transiber.i18n

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R

@Composable
internal fun AppLanguageSettings() {
    val text = rememberUiText()
    val revision by AppLocale.revision.collectAsState()
    val selected = remember(revision, text) { AppLocale.selected().substringBefore('-') }
    Text(text(R.string.app_language), style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() })
    Text(text(R.string.app_language_detail), style = MaterialTheme.typography.bodySmall)
    listOf("" to R.string.follow_phone, "en" to R.string.locale_en,
        "ar" to R.string.locale_ar, "zh" to R.string.locale_zh).forEach { (code, label) ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected == code, role = Role.RadioButton) { AppLocale.select(code) }
            .padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected == code, onClick = null)
            Text(text(label), Modifier.padding(start = 8.dp))
        }
    }
}
