package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import com.sal7one.transiber.ui.theme.glassPanel
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Large labelled destinations; Local/Cloud remains explicit before entering setup. */
@Composable
internal fun SettingsFeatureGrid(location: SettingsLocation, onFeature: (SettingsFeature) -> Unit) {
    val uiText = rememberUiText()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        settingsFeatures(location).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { feature ->
                    Card(onClick = { onFeature(feature) }, modifier = Modifier.weight(1f).heightIn(min = 112.dp).glassPanel(Color(when(feature) {
                            SettingsFeature.SPEECH -> 0xFF448EFF
                            SettingsFeature.TRANSLATION -> 0xFFAC6CF6
                            SettingsFeature.VOICES -> 0xFF2FC5A8
                            SettingsFeature.CAMERA -> 0xFFEEA34D
                        })),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(when(feature) {
                                SettingsFeature.SPEECH -> Icons.Default.Mic
                                SettingsFeature.TRANSLATION -> Icons.Default.Translate
                                SettingsFeature.VOICES -> Icons.Default.RecordVoiceOver
                                SettingsFeature.CAMERA -> Icons.Default.CameraAlt
                            }, null, tint = MaterialTheme.colorScheme.primary)
                            Text(when(feature) {
                                SettingsFeature.SPEECH -> uiText(UiR.string.ui_speech_d00d8)
                                SettingsFeature.TRANSLATION -> uiText(UiR.string.ui_translation_ac26a)
                                SettingsFeature.VOICES -> uiText(UiR.string.ui_voices_40273)
                                SettingsFeature.CAMERA -> uiText(UiR.string.ui_camera_ocr_c1207)
                            }, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
                if(pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
