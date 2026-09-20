package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy

@Composable
internal fun VoiceModelCard(title: String, runtime: String, coverage: String, license: String, url: String, linkLabel: String, onError: (String?)->Unit) {
    val uiText = rememberUiText()

    val context=LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium)
            Text(runtime,style=MaterialTheme.typography.labelLarge)
            Text(coverage,style=MaterialTheme.typography.bodyMedium)
            Text(license,style=MaterialTheme.typography.bodySmall)
            if(ByokPolicy.FEATURE_BYOK)TextButton(onClick={
                try {context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)))}
                catch(e: Exception){onError(e.message ?: e.toString())}
            }){Text(linkLabel)}
            else {
                Text(uiText(UiR.string.ui_publisher_address_select_to_copy_import_its_files_using_the_butto_23eb3),style=MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.text.selection.SelectionContainer {Text(url,style=MaterialTheme.typography.bodySmall)}
            }
        }
    }
}
