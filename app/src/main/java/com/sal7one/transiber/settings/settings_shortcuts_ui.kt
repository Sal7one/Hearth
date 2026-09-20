package com.sal7one.transiber.settings

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.shortcuts.CaptionTileService
import com.sal7one.transiber.shortcuts.ReadingTileService

@Composable
internal fun SettingsShortcutsUi() {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    Text("Phone shortcuts", style = MaterialTheme.typography.titleMedium)
    Text("Start from your notification shade. Each tile checks setup before asking for Android capture access. Tap a running tile to recover its controls.", style = MaterialTheme.typography.bodySmall)
    listOf(
        Triple("Live captions", CaptionTileService::class.java, R.drawable.ic_tile_captions),
        Triple("Screen translation", ReadingTileService::class.java, R.drawable.ic_tile_reading),
    ).forEach { (label, service, icon) ->
        if (Build.VERSION.SDK_INT >= 33) OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
            try {
                context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                    ComponentName(context, service), label, Icon.createWithResource(context, icon), context.mainExecutor,
                ) { result -> status = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "$label added. Swipe down twice to use it."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "$label is already in Quick Settings."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "$label was not added. You can add it later from Quick Settings → Edit."
                    else -> "Android tile request returned $result. Add $label from Quick Settings → Edit."
                } }
            } catch (e: Exception) { status = e.message ?: e.toString() }
        }) { Text("Add $label tile") }
    }
    Text("You can also swipe down twice → Edit buttons, then drag Live captions and Screen translation into your tiles. Screen translation uses your Camera/OCR setup for manga and books.", style = MaterialTheme.typography.bodySmall)
    status?.let { Text(it) }
}
