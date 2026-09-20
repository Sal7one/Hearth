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

@Composable
internal fun SettingsSpeechScreen(initialLocation: SettingsLocation, entryRevision: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<CaptionOverlayConfig?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { CaptionConfigStore.config(context).collect { config = it } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    SettingsTabs(initialLocation, entryRevision) { location ->
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (location == SettingsLocation.CLOUD) {
            SettingsSpeechCloudUi()
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
    }
}
