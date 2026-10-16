package com.sal7one.transiber.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.transiber.R
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.i18n.*
import com.sal7one.transiber.reading.ReadingOverlayService
import com.sal7one.transiber.setup.rememberTvSetupStatus

/** Everyday TV use is one row of Start/Stop; configuration lives behind one setup action. */
@Composable
internal fun TvHomeScreen(onSetup: () -> Unit, onStart: () -> Unit) {
    val text = rememberUiText()
    val context = LocalContext.current
    val session by CaptionCaptureService.session.collectAsStateWithLifecycle()
    val reading by ReadingOverlayService.running.collectAsStateWithLifecycle()
    val config by remember(context) { CaptionConfigStore.config(context) }.collectAsStateWithLifecycle(initialValue = null)
    val setup = rememberTvSetupStatus(config)
    val active = session.running || reading
    val runtimeOwner by com.sal7one.transiber.runtime.LocalWorkGate.owner.collectAsStateWithLifecycle()
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(active, setup.loading, runtimeOwner) { if (!setup.loading && (active || runtimeOwner == null)) initialFocus.requestFocus() }
    var showError by remember { mutableStateOf(false) }
    val error = session.error ?: setup.error
    if (showError && error != null) AlertDialog(onDismissRequest = { showError = false },
        title = { Text(text(R.string.service_live_captions_error_9f465)) },
        text = { Text(error, Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { RemoteButton(text(R.string.ui_back_b52b3), onClick = { showError = false }) })

    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shade = listOf(Color(0xFA0C101A), Color(0xEB0C101A), Color(0x480C101A))
    Box(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.tv_setup_scene), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(if (rtl) shade.reversed() else shade)))
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(.58f).widthIn(max = 540.dp)
            .fillMaxHeight().verticalScroll(rememberScrollState()).padding(32.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            Text(text(when {
                session.error != null -> R.string.service_live_captions_error_9f465
                active -> if (session.paused) R.string.overlay_status_paused else R.string.overlay_status_listening
                setup.configured -> R.string.tv_setup_saved_status
                else -> R.string.tv_welcome
            }), style = MaterialTheme.typography.labelLarge, color = Color(0xFFD8CAFF),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text(text(R.string.tv_home_title), color = Color.White, style = MaterialTheme.typography.headlineLarge)
            Text(error ?: text(if (setup.configured) R.string.tv_dashboard_help else R.string.tv_welcome_help),
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color(0xFFF0EDF7), style = MaterialTheme.typography.bodyLarge)
            if (error != null) TextButton(onClick = { showError = true }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD8CAFF))) { Text(text(R.string.tv_error_details)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvPrimaryAction(text(R.string.tv_start_hearth), Icons.Default.PlayArrow, enabled = !active && !setup.loading && runtimeOwner == null,
                    focus = if (!active) initialFocus else null, modifier = Modifier.weight(1f),
                    onClick = { if (setup.configured) onStart() else onSetup() })
                TvPrimaryAction(text(R.string.overlay_stop_launcher_title), Icons.Default.Stop, enabled = active,
                    focus = if (active) initialFocus else null, modifier = Modifier.weight(1f), stop = true,
                    onClick = { stopHearthSessions(context) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteButton(text(R.string.tv_overlay_editor), modifier = Modifier.weight(1f), onClick = { OverlayControlsActivity.open(context) })
                if (setup.configured) RemoteButton(text(R.string.tv_manage_setup), modifier = Modifier.weight(1f), onClick = onSetup)
            }
            config?.takeIf { setup.configured }?.let {
                Text(text.label(it.source) + " · " + text.label(it.mode) + " · " + text(if (it.speakCaptions) R.string.tv_voice_on else R.string.tv_voice_off),
                    color = Color(0xFFD0CBDD), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun TvSetupArtwork(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.tv_setup_scene), contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(24.dp)))
}

@Composable
private fun TvPrimaryAction(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier,
    focus: FocusRequester?, stop: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = if (stop) Color(0xFFFFB4B4) else Color(0xFFD7C5FF)
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 108.dp).then(if (focus == null) Modifier else Modifier.focusRequester(focus))
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(20.dp), border = BorderStroke(if (focused) 3.dp else 1.dp, if (enabled) color else Color.White.copy(alpha = .18f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (focused) color.copy(alpha = .22f) else Color(0xEB191D2A),
            contentColor = color, disabledContainerColor = Color(0xB3191D2A), disabledContentColor = Color.White.copy(alpha = .35f))) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}
