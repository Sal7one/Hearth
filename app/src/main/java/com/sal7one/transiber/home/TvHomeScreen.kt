package com.sal7one.transiber.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.transiber.R
import com.sal7one.transiber.caption.CaptionCaptureService
import com.sal7one.transiber.caption.OverlayControlsActivity
import com.sal7one.transiber.caption.RemoteButton
import com.sal7one.transiber.i18n.rememberUiText

/** TV starts with speech and captions; every destination can be reached with D-pad + OK. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvHomeScreen(onFeature: (com.sal7one.transiber.setup.TvSetupFeature) -> Unit, onStart: () -> Unit, onFullInterface: () -> Unit) {
    val text = rememberUiText()
    val context = LocalContext.current
    val session by CaptionCaptureService.session.collectAsStateWithLifecycle()
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(session.running) { initialFocus.requestFocus() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(text(R.string.tv_home_title), style = MaterialTheme.typography.headlineLarge)
        Text(text(R.string.tv_home_intro), style = MaterialTheme.typography.titleMedium)
        if (session.running) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(session.error ?: text(if (session.paused) R.string.overlay_status_paused else R.string.overlay_status_listening),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RemoteButton(text(R.string.overlay_controls_title), focus = initialFocus,
                            onClick = { OverlayControlsActivity.open(context) })
                        RemoteButton(text(R.string.ui_stop_captions_58b64), onClick = { CaptionCaptureService.stop(context) })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            com.sal7one.transiber.setup.TvSetupFeature.entries.forEach { feature ->
                TvStepCard("${feature.ordinal + 1}", text(feature.title), text(feature.detail), Modifier.weight(1f)) {
                    RemoteButton(text(feature.action), modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp), focus = if (!session.running && feature.ordinal == 0) initialFocus else null,
                        onClick = { onFeature(feature) })
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteButton(text(R.string.ui_start_captions_68e1c), enabled = !session.running, onClick = onStart)
            RemoteButton(text(R.string.overlay_controls_title), onClick = { OverlayControlsActivity.open(context) })
        }
        if (session.running) Text(text(R.string.tv_start_help), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onFullInterface) { Text(text(R.string.tv_full_interface)) }
        Text(text(R.string.tv_home_recovery), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun TvStepCard(number: String, title: String, detail: String, modifier: Modifier = Modifier, action: @Composable () -> Unit) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(number, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyLarge)
            action()
        }
    }
}
