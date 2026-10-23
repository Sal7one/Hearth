package com.sal7one.hearth.desktop.ui.screens.integrations

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.*
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** Real controls remain separate from decorative art; no fake URL, preview or connection claim. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun OverlayIntegrationCard(state: IntegrationsState, actions: IntegrationsActions, modifier: Modifier = Modifier) {
    val colors = HearthTheme.colors
    var troubleshooting by remember { mutableStateOf(false) }
    val canCopy = state.running && state.overlayUrl.isNotBlank() && !state.busy
    HearthCard(modifier, padding = 22.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 760.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource("obs-integration-hero.png"), contentDescription = null,
                        modifier = Modifier.width(230.dp).height(148.dp), contentScale = ContentScale.Fit)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(IntegrationsCopy.OBS, style = HearthType.h2, color = colors.text)
                        Text(IntegrationsCopy.OBS_DETAIL, style = HearthType.body, color = colors.muted)
                        Text("OBS and Hearth must run on this computer. Captions use your existing speech and translation choices.",
                            style = HearthType.small, color = colors.muted)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(painterResource("obs-integration-hero.png"), contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(132.dp), contentScale = ContentScale.Fit)
                    Text(IntegrationsCopy.OBS, style = HearthType.h2, color = colors.text)
                    Text(IntegrationsCopy.OBS_DETAIL, style = HearthType.body, color = colors.muted)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        ObsStep(1, "Start the Hearth connection", "Use Start connection above. Leave Hearth open while you stream.")
        Spacer(Modifier.height(14.dp))
        ObsStep(2, "Copy your OBS link", "Use Copy OBS URL below. This is a caption-only link; OBS does not need the API token.")
        Spacer(Modifier.height(10.dp))
        Text(state.overlayUrlDisplay, style = HearthType.monoSmall, color = colors.textDim,
            modifier = Modifier.fillMaxWidth().background(colors.surface2, RoundedCornerShape(10.dp))
                .border(1.dp, colors.border, RoundedCornerShape(10.dp)).padding(12.dp), maxLines = 1)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HearthButton(IntegrationsCopy.COPY, actions.onCopyOverlayUrl, tone = ButtonTone.Primary, enabled = canCopy)
            HearthButton("Preview in browser", actions.onPreviewOverlay, tone = ButtonTone.Secondary, enabled = canCopy)
        }
        Spacer(Modifier.height(8.dp))
        Text(IntegrationsCopy.OVERLAY_NOTE, style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(18.dp))
        ObsStep(3, "In OBS, add a Browser source",
            "Sources → + → Browser → Create new. Name it Hearth captions, leave Local file off and paste the complete URL. " +
            "Set Width and Height to your OBS canvas size; 1920 × 1080 is a common starting point. Click OK and keep this source above your video sources.")
        Spacer(Modifier.height(10.dp))
        Text("The background is already transparent. Keep the default transparent Custom CSS. " +
            "For uninterrupted scene changes, leave Shutdown source when not visible off. No Display Capture is required for the caption layer.",
            style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(18.dp))
        ObsStep(4, "Start captions in Hearth",
            "Open Captions, choose microphone or system audio, then start. Play your audio and check that words appear in Hearth. " +
            "The same text then appears in OBS; translation appears when your translation stage is enabled and produces a result.")
        Spacer(Modifier.height(10.dp))
        HearthButton("Open Captions", actions.onOpenCaptions, tone = ButtonTone.Secondary, size = ButtonSize.Small)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().background(colors.surface2, RoundedCornerShape(12.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(state.captionStatus, style = HearthType.rowTitle, color = colors.text)
            Text(state.captionDetail, style = HearthType.small, color = colors.muted)
        }
        Spacer(Modifier.height(12.dp))
        HearthButton(if (troubleshooting) "Hide OBS help" else "No captions in OBS?", { troubleshooting = !troubleshooting },
            tone = ButtonTone.Ghost, size = ButtonSize.Small)
        if (troubleshooting) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ObsHelp("Nothing appears", "First check Captions in Hearth. If Hearth has no text, fix the input, permissions or model there. " +
                    "If Hearth has text, make sure the OBS source is visible and above your video, then refresh its Browser source.")
                ObsHelp("Disconnected after a restart", "Stopping or restarting the connection creates a new link and may change its port. " +
                    "Copy OBS URL again and replace the entire URL in OBS. Keep the final # section. No router port forwarding or public address is needed.")
                ObsHelp("Only the original text appears", "Enable translation in Hearth and choose a supported source/destination pair. " +
                    "This OBS page shows the original and any returned translation. Desktop floating-overlay style controls do not change the OBS Browser Source.")
                ObsHelp("Old captions remain", "OBS can keep the last completed result. Start a new caption session and wait for a real result; " +
                    "starting this connection alone does not listen to audio or prove that OBS is connected.")
                ObsHelp("Hearth is on another computer", "This private connection accepts only apps on the same computer. " +
                    "Run Hearth beside OBS; do not remove the loopback address or expose the service to your network.")
            }
        }
    }
}

@Composable
private fun ObsStep(number: Int, title: String, detail: String) {
    val colors = HearthTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).background(colors.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            Text(number.toString(), style = HearthType.label, color = colors.accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = HearthType.rowTitle, color = colors.text)
            Text(detail, style = HearthType.small, color = colors.muted)
        }
    }
}

@Composable
private fun ObsHelp(title: String, detail: String) {
    val colors = HearthTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = HearthType.rowTitle, color = colors.text)
        Text(detail, style = HearthType.small, color = colors.muted)
    }
}
