package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.ui.components.FeatureAction
import com.sal7one.transiber.ui.components.FeatureOptionsSheet

/** One shared setup sheet, reachable directly from every feature. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SettingsQuickSheet(
    scroll: ScrollState,
    sheetState: SheetState,
    config: com.sal7one.transiber.caption.CaptionOverlayConfig,
    onDismiss: () -> Unit,
    onFeature: (SettingsLocation, SettingsFeature) -> Unit,
    onTranslationModels: () -> Unit,
    onPage: (Int) -> Unit,
    onReading: () -> Unit,
) {
    val uiText = rememberUiText()
    val context = androidx.compose.ui.platform.LocalContext.current

    FeatureOptionsSheet(uiText(UiR.string.ui_settings_c7f73), onDismiss, scroll,
        Modifier.semantics { testTagsAsResourceId = true }.testTag("quick-settings-sheet"), sheetState = sheetState) {
        OutlinedButton(onClick = {
            com.sal7one.transiber.caption.OverlayControlsActivity.open(context)
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(uiText(UiR.string.overlay_controls_title)) }
        SettingsPipelineUi(onFeature, onTranslationModels, initialConfig = config)
        FeatureAction(uiText(UiR.string.ui_appearance_41def), Icons.Default.Palette, { onPage(13) },
            modifier = Modifier.testTag("quick-settings-appearance"))
        if (com.sal7one.transiber.runtime.LocalModelPolicy.allowed(context))
            FeatureAction(uiText(UiR.string.ui_downloads_imports_a2a3b), Icons.Default.Download, { onPage(2) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onPage(5) }) { Text(uiText(UiR.string.ui_caption_overlay_a4c78)) }
            TextButton(onClick = onReading) { Text(uiText(UiR.string.ui_screen_overlay_773a6)) }
        }
        OutlinedButton(onClick = { onPage(3) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(uiText(UiR.string.ui_all_settings_diagnostics_7b22b)) }
    }
}
