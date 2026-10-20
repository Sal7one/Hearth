package com.sal7one.transiber.settings

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText

/** Secondary file actions stay available without competing with voice selection and preview. */
@Composable
internal fun VoiceFileOptions(enabled: Boolean, importLabel: String, onImport: () -> Unit, onDownloads: () -> Unit) {
    val text=rememberUiText();var expanded by remember { mutableStateOf(false) }
    TextButton(onClick={expanded=!expanded}) { Text(text(R.string.voice_file_options)) }
    if(expanded) {
        OutlinedButton(enabled=enabled,onClick=onImport) { Text(importLabel) }
        if(com.sal7one.transiber.byok.ByokPolicy.FEATURE_BYOK)OutlinedButton(onClick=onDownloads) { Text(text(R.string.magpie_downloads)) }
    }
}
