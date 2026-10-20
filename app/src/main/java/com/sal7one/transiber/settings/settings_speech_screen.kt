package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sal7one.transiber.caption.CaptionConfigStore
import com.sal7one.transiber.caption.CaptionOverlayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.sal7one.transiber.preferences.SetupMode
import com.sal7one.transiber.preferences.rememberSetupMode
import com.sal7one.transiber.runtime.LocalModelPolicy
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.R

@Composable
internal fun SettingsSpeechScreen(initialLocation: SettingsLocation, entryRevision: Int = 0, onHistory: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<CaptionOverlayConfig?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val text = rememberUiText()
    val advanced = rememberSetupMode() == SetupMode.ADVANCED
    val localAllowed = LocalModelPolicy.allowed(context)
    LaunchedEffect(Unit) {
        try { CaptionConfigStore.config(context).collect { config = it } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    SettingsTabs(initialLocation, entryRevision, localAvailable = localAllowed) { location ->
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (location == SettingsLocation.CLOUD) {
            SettingsSpeechCloudUi()
        } else if (!localAllowed) {
            com.sal7one.transiber.ui.components.LocalModelPolicyNotice()
        } else {
            val current = config
            if (current == null) {
                if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            } else SettingsSpeechLocalUi(current) { transform ->
                scope.launch {
                    try { CaptionConfigStore.update(context, transform); error = null }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: e.toString() }
                }
            }
        }
        if (advanced) {
            var history by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            androidx.compose.material3.TextButton(onClick = { history = !history }) { Text(text(R.string.settings_flow_history_options)) }
            if (history) com.sal7one.transiber.caption.history.CaptionHistorySettings(onHistory)
        }
    }
}
