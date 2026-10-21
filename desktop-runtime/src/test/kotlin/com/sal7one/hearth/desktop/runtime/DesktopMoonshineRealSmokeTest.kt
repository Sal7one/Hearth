package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineInput
import com.sal7one.hearth.core.SpeechSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Owner opt-in only: proves the shared sherpa family can run through the same
 * DesktopBackendSpeechEngine as TranscribePsy/Nemotron. No new engine code.
 *
 * ```
 * HEARTH_MOONSHINE_SMOKE=1 HEARTH_SMOKE_MOONSHINE=<extracted model root> \
 *   HEARTH_SMOKE_WAV=<16 kHz wav> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopMoonshineRealSmokeTest*"
 * ```
 */
class DesktopMoonshineRealSmokeTest {
    private fun fixture(): Pair<Path, Path> {
        assumeTrue("Set HEARTH_MOONSHINE_SMOKE=1 and provide HEARTH_SMOKE_MOONSHINE and HEARTH_SMOKE_WAV", System.getenv("HEARTH_MOONSHINE_SMOKE") == "1")
        val root = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_MOONSHINE")) { "HEARTH_SMOKE_MOONSHINE is required" }).toRealPath()
        val wav = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_WAV")) { "HEARTH_SMOKE_WAV is required" }).toRealPath()
        return root to wav
    }

    @Test
    fun sharedSherpaBridgeTranscribesMoonshine() {
        val (root, wav) = fixture()
        val samples = readPcm16Mono(wav)
        val started = System.nanoTime()
        DesktopBackendSpeechEngine.open(
            "hearth_desktop_qwen3_asr", "moonshine", root.resolve("tokens.txt"), "en", 4,
            entrySymbol = "hearth_moonshine_backend_v1",
            encoder = root.resolve("encoder_model.ort"),
            decoder = root.resolve("decoder_model_merged.ort"),
        ).use { engine ->
            val loadMs = (System.nanoTime() - started) / 1_000_000
            val inferStarted = System.nanoTime()
            val text = engine.transcribe(samples)
            val inferMs = (System.nanoTime() - inferStarted) / 1_000_000
            println("MOONSHINE_SMOKE execution=${engine.executionInfo} loadMs=$loadMs inferMs=$inferMs text=$text")
            assertTrue(text.isNotBlank(), "Moonshine returned no text")
            assertContains(text.lowercase(), "museum")
            assertContains(text.lowercase(), "entrance")
        }
    }

    @Test
    fun desktopEnginePathUsesTheSameBackend() = runBlocking<Unit> {
        val (root, wav) = fixture()
        val directory = Files.createTempDirectory("hearth-moonshine-smoke-")
        val selection = EngineSelection("moonshine-local", "moonshine-tiny-en-v2", root.toString())
        val segments = mutableListOf<SpeechSegment>()
        val started = System.nanoTime()
        try {
            DesktopEngines(CredentialResolver { error("Local Moonshine smoke must never resolve cloud credentials") }, directory).use { engines ->
                assertTrue(engines.capabilities().single { it.id == "moonshine-local" }.available)
                engines.recognize(PipelineInput.AudioFile(wav.toString()), selection, "en") { segments += it }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val text = segments.joinToString(" ") { it.text }
        println("MOONSHINE_ENGINE_SMOKE ms=$elapsed segments=${segments.size} text=$text")
        assertTrue(text.isNotBlank(), "The desktop engine path returned no text")
        assertContains(text.lowercase(), "museum")
        assertContains(text.lowercase(), "entrance")
    }
}
