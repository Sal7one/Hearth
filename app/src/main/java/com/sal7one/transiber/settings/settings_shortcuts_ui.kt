package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

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
    val uiText = rememberUiText()

    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    Text(uiText(UiR.string.ui_phone_shortcuts_b969d), style = MaterialTheme.typography.titleMedium)
    Text(uiText(UiR.string.ui_start_from_your_notification_shade_each_tile_checks_setup_before_63465), style = MaterialTheme.typography.bodySmall)
    listOf(
        Triple(uiText(UiR.string.ui_live_captions_83fd1), CaptionTileService::class.java, R.drawable.ic_tile_captions),
        Triple(uiText(UiR.string.ui_screen_translation_d51de), ReadingTileService::class.java, R.drawable.ic_tile_reading),
    ).forEach { (label, service, icon) ->
        if (Build.VERSION.SDK_INT >= 33) OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
            try {
                context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                    ComponentName(context, service), label, Icon.createWithResource(context, icon), context.mainExecutor,
                ) { result -> status = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> uiText(UiR.string.ui_1_s_added_swipe_down_twice_to_use_it_7f59b, label)
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> uiText(UiR.string.ui_1_s_is_already_in_quick_settings_90c4c, label)
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> uiText(UiR.string.ui_1_s_was_not_added_you_can_add_it_later_from_quick_settings_edit_ef39d, label)
                    else -> uiText(UiR.string.ui_android_tile_request_returned_1_s_add_2_s_from_quick_settings_edi_0ed53, result, label)
                } }
            } catch (e: Exception) { status = e.message ?: e.toString() }
        }) { Text(uiText(UiR.string.ui_add_1_s_tile_0b83f, label)) }
    }
    Text(uiText(UiR.string.ui_you_can_also_swipe_down_twice_edit_buttons_then_drag_live_caption_da189), style = MaterialTheme.typography.bodySmall)
    status?.let { Text(it) }
}
