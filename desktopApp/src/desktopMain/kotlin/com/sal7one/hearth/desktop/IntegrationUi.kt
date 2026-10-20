package com.sal7one.hearth.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.Component
import java.awt.Desktop
import java.net.URI

@Composable
internal fun IntegrationUi(controller: DesktopWorkspaceController, state: DesktopUiState, parent: Component?) {
    var apiDetails by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard("OBS & other apps", "Start a local server only when you need it. It accepts connections from this computer.") {
            val running = state.integration
            if (running == null) {
                Text("Stopped", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { controller.startIntegration() }, enabled = !state.integrationBusy) { Text(if (state.integrationBusy) "Starting…" else "Start local integration") }
            } else {
                SelectionContainer { Text("Listening at ${running.baseUrl}") }
                Text("In OBS, add a Browser source and paste the overlay URL. The overlay can read captions; it cannot start or cancel your pipeline.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { try { copyToClipboard(running.overlayUrl) } catch(e: Exception) { controller.showError(e.message ?: e.toString()) } }) { Text("Copy OBS overlay URL") }
                    OutlinedButton(onClick = { try { Desktop.getDesktop().browse(URI(running.overlayUrl)) } catch(e: Exception) { controller.showError(e.message ?: e.toString()) } }) { Text("Open overlay preview") }
                }
                TextButton(onClick = { apiDetails = !apiDetails }) { Text(if (apiDetails) "Hide application API" else "Connect another application") }
                if (apiDetails) {
                    Text("The separate API bearer token permits submitting and cancelling requests. Keep it private. Provider keys are never included in API responses.", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text("GET /v1/capabilities\nPOST /v1/sessions\nPOST /v1/sessions/audio\nGET /v1/events", style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { controller.copyApiToken(::copyToClipboard) }) { Text("Copy private API token") }
                }
                OutlinedButton(onClick = { controller.stopIntegration() }, enabled = !state.integrationBusy) { Text(if (state.integrationBusy) "Stopping…" else "Stop local integration") }
            }
        }
        SectionCard("Runtime diagnostics") {
            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${System.getProperty("os.name")} ${System.getProperty("os.version")} · ${System.getProperty("os.arch")}")
                Text("Java ${System.getProperty("java.version")}")
                Text("Data: ${controller.dataDirectory}", style = MaterialTheme.typography.bodySmall)
                state.snapshot?.let { Text("Session ${it.id}\nStatus: ${it.status}${it.stage?.let { stage -> " · $stage" }.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
            } }
            controller.capabilities.forEach { engine ->
                Text("${engine.name} · ${if (engine.available) "available" else engine.unavailableReason ?: "unavailable"}",
                    color = if (engine.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
