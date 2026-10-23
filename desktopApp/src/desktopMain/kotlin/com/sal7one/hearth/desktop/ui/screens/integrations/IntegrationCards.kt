package com.sal7one.hearth.desktop.ui.screens.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.HearthTextField
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** OBS overlay: a real URL while running, a plain statement when it is not. */
@Composable
internal fun OverlayIntegrationCard(
    state: IntegrationsState,
    actions: IntegrationsActions,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(IntegrationsCopy.OBS, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                Spacer(Modifier.height(3.dp))
                Text(IntegrationsCopy.OBS_DETAIL, style = HearthType.small, color = colors.muted)
            }
            Chip(IntegrationsCopy.RECOMMENDED, tone = ChipTone.Accent)
        }
        Spacer(Modifier.height(14.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(
                    Brush.linearGradient(listOf(colors.surface3, colors.bg)),
                    RoundedCornerShape(12.dp),
                )
                .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    if (state.running) "Captions appear here, over any scene." else IntegrationsCopy.NOT_RUNNING_NOTE,
                    style = HearthType.small,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(IntegrationsCopy.OVERLAY_URL.uppercase(), style = HearthType.micro, color = colors.muted)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.overlayUrlDisplay,
                style = HearthType.monoSmall,
                color = colors.textDim,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .background(colors.surface2, RoundedCornerShape(10.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(Modifier.width(8.dp))
            HearthButton(
                IntegrationsCopy.COPY,
                actions.onCopyOverlayUrl,
                tone = ButtonTone.Secondary,
                size = ButtonSize.Small,
                enabled = state.running,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(IntegrationsCopy.OVERLAY_NOTE, style = HearthType.small, color = colors.muted)
    }
}

/** External-app API: endpoint, token, and the limits the server actually enforces. */
@Composable
internal fun ApiIntegrationCard(
    state: IntegrationsState,
    actions: IntegrationsActions,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(IntegrationsCopy.API, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                Spacer(Modifier.height(3.dp))
                Text(IntegrationsCopy.API_DETAIL, style = HearthType.small, color = colors.muted)
            }
            Chip(IntegrationsCopy.BETA, tone = ChipTone.Neutral)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.running) state.baseUrl else "API address available after Start",
                style = HearthType.monoSmall, color = colors.textDim, modifier = Modifier.weight(1f))
            HearthButton("Copy address", actions.onCopyApiAddress, tone = ButtonTone.Secondary,
                size = ButtonSize.Small, enabled = state.running)
        }
        Spacer(Modifier.height(12.dp))
        Text(IntegrationsCopy.API_TOKEN.uppercase(), style = HearthType.micro, color = colors.muted)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.running) "hrth_live_••••••••••••" else "Not issued yet",
                style = HearthType.monoSmall,
                color = if (state.running) colors.textDim else colors.faint,
                modifier = Modifier.weight(1f),
            )
            HearthButton(
                IntegrationsCopy.COPY_TOKEN,
                actions.onCopyApiToken,
                tone = ButtonTone.Secondary,
                size = ButtonSize.Small,
                enabled = state.running,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(IntegrationsCopy.SEND.uppercase(), style = HearthType.micro, color = colors.muted)
        Spacer(Modifier.height(6.dp))
        val base = if (state.running) state.baseUrl else "http://127.0.0.1:—"
        Text(
            "curl -X POST $base/v1/sessions \\\n  -H \"Authorization: Bearer \$TOKEN\" \\\n  -d '{\"text\":\"Good evening\",\"targetLanguage\":\"ar\"}'",
            style = HearthType.monoSmall,
            color = colors.textDim,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bg, RoundedCornerShape(12.dp))
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                .padding(14.dp),
        )

        Spacer(Modifier.height(14.dp))
        Text(IntegrationsCopy.ACCEPTS.uppercase(), style = HearthType.micro, color = colors.muted)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Text" to "JSON, up to 12,000 characters",
                "Audio" to "WAV or PCM16, up to 120 seconds",
                "Media" to "MP4, MOV, M4A, MP3, AAC — 64 MiB",
                "Live" to "Streaming PCM frames, 200 ms each",
                "Images" to "PNG or JPEG, up to 16 MiB · Japanese OCR",
            ).forEach { (label, detail) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(label, tone = ChipTone.Outline)
                    Spacer(Modifier.width(10.dp))
                    Text(detail, style = HearthType.small, color = colors.muted)
                }
            }
        }
    }
}

@Composable
internal fun BrowserIntegrationCard(state: IntegrationsState, actions: IntegrationsActions) {
    val colors = HearthTheme.colors
    var origin by remember(state.browserOrigin) { mutableStateOf(state.browserOrigin) }
    HearthCard(Modifier.fillMaxWidth(), padding = 18.dp) {
        Text("Translate websites", style = HearthType.h3, color = colors.text)
        Spacer(Modifier.height(8.dp))
        Text("Use the Hearth browser extension for selectable website text. Links and controls keep working. " +
            "For manga images or another app, use Reading → Screen translation.", style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(12.dp))
        Text("Paste the extension origin shown in its popup, save access, then start the service. " +
            "Copy this service address and API token into the extension. Website origins cannot be trusted here.",
            style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(12.dp))
        HearthTextField(origin, { origin = it }, "chrome-extension://… or moz-extension://…")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HearthButton("Save browser access", { actions.onSaveBrowserOrigin(origin) },
                size = ButtonSize.Small, enabled = !state.busy)
            HearthButton("Remove access", { origin = ""; actions.onSaveBrowserOrigin("") },
                tone = ButtonTone.Secondary, size = ButtonSize.Small, enabled = !state.busy && state.browserOrigin.isNotEmpty())
        }
        Spacer(Modifier.height(10.dp))
        Text("Saving or removing access stops the service and invalidates its old token. " +
            "Provider keys stay in Hearth. The extension translates only after you start it for a tab.",
            style = HearthType.small, color = colors.muted)
    }
}
