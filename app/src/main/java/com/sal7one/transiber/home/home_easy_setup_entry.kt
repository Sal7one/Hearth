package com.sal7one.transiber.home

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeEasySetupEntry(onSetup: () -> Unit) {
    val uiText = rememberUiText()

    val context = LocalContext.current
    var collapsed by remember { mutableStateOf(HomeServiceStore.easySetupCollapsed(context)) }
    fun collapse(value: Boolean) {
        collapsed = value
        HomeServiceStore.setEasySetupCollapsed(context, value)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (collapsed) {
            FilledTonalIconButton(onClick = { collapse(false) },
                modifier = Modifier.size(48.dp), shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)) {
                Icon(Icons.Default.ChevronRight, uiText(UiR.string.ui_expand_easy_setup_f2672))
            }
        } else {
            Spacer(Modifier.width(16.dp))
            FilledTonalIconButton(onClick = { collapse(true) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.ChevronLeft, uiText(UiR.string.ui_collapse_easy_setup_6b1ce))
            }
            OutlinedButton(onClick = onSetup,
                modifier = Modifier.weight(1f).padding(start = 8.dp, end = 24.dp).heightIn(min = 48.dp)) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(uiText(UiR.string.ui_easy_setup_35fb5))
            }
        }
    }
}
