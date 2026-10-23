package com.sal7one.hearth.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.sal7one.hearth.core.PipelineSnapshot
import com.sal7one.hearth.core.PipelineStatus
import com.sal7one.hearth.desktop.ui.screens.integrations.*
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Headless content only: no OBS process, display, provider or audio session is opened. */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopObsIntegrationTest {
    @Test fun connectionReadinessNeverPretendsCaptionsOrObsAreConnected() {
        val connected = integrationsState(DesktopUiState(integration = IntegrationView("http://127.0.0.1:12345", "$URL#private")))
        assertTrue(connected.running)
        assertEquals("No active captions", connected.captionStatus)
        assertTrue(connected.captionDetail.contains("Open Captions"))
        assertFalse(connected.captionStatus.contains("shared", ignoreCase = true))
        val audio = integrationsState(DesktopUiState(integration = IntegrationView("http://127.0.0.1:12345", "$URL#private"),
            input = WorkspaceInput.SYSTEM_AUDIO, snapshot = PipelineSnapshot("audio", PipelineStatus.RUNNING)))
        assertEquals("Audio session running", audio.captionStatus)
        val text = integrationsState(DesktopUiState(input = WorkspaceInput.TEXT, snapshot = PipelineSnapshot("text", PipelineStatus.RUNNING)))
        assertEquals("Text session running", text.captionStatus)
        val failed = integrationsState(DesktopUiState(snapshot = PipelineSnapshot("error", PipelineStatus.FAILED, error = "Native speech failed")))
        assertEquals("Last session failed", failed.captionStatus); assertEquals("Native speech failed", failed.captionDetail)
        val retrying = integrationsState(DesktopUiState(starting = true, snapshot = PipelineSnapshot("error", PipelineStatus.FAILED, error = "Old failure")))
        assertEquals("Starting a session…", retrying.captionStatus); assertFalse(retrying.captionDetail.contains("Old failure"))
    }

    @Test fun displayedObsLinkConcealsReadOnlySecretAndNeverOffersStaleStoppedAddress() {
        val active = IntegrationsState(running = true, overlayUrl = "$URL#readonly-private-secret")
        assertEquals("$URL#…", active.overlayUrlDisplay)
        assertFalse(active.overlayUrlDisplay.contains("readonly-private-secret"))
        assertFalse(active.copy(running = false).overlayUrlDisplay.contains("127.0.0.1"))
        assertFalse(active.copy(overlayUrl = "").overlayUrlDisplay.contains("#"))
    }

    @Test fun obsGuideAndGeneratedArtRenderAtCompactAndWideWidthsWithRealStates() {
        val active = IntegrationsState(running = true, baseUrl = "http://127.0.0.1:12345", overlayUrl = "$URL#private",
            captionStatus = "Audio session running", captionDetail = "Captions are being recognized from system audio.")
        val states = listOf("running" to active, "stopped" to IntegrationsState())
        val directory = System.getenv("HEARTH_OBS_PREVIEW_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
        directory?.let(Files::createDirectories)
        for ((name, state) in states) for (width in listOf(700, 1200)) for (dark in listOf(true, false)) {
            val scene = ImageComposeScene(width = width, height = 1500, density = Density(1f)) {
                HearthTheme(dark = dark) { OverlayIntegrationCard(state, IntegrationsActions()) }
            }
            try {
                repeat(3) { scene.render(System.nanoTime()).close() }
                val bytes = scene.render(System.nanoTime()).use { image ->
                    assertEquals(width, image.width); assertEquals(1500, image.height)
                    checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
                }
                assertTrue(bytes.size > 10_000, "The actual OBS guide/art produced an empty or implausibly small render")
                directory?.resolve("obs-$name-$width-${if (dark) "dark" else "light"}.png")?.let { Files.write(it, bytes) }
            } finally { scene.close() }
        }
    }
    companion object { private const val URL = "http://127.0.0.1:12345/overlay/" }
}
