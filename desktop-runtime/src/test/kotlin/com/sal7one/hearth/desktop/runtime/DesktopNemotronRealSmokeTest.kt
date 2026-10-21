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
 * Owner opt-in only: proves the packaged Nemotron 3.5 streaming backend transcribes real
 * speech. No downloads, no recording, no playback.
 *
 * ```
 * HEARTH_NEMOTRON_SMOKE=1 HEARTH_SMOKE_NEMOTRON=<gguf> HEARTH_SMOKE_WAV=<16 kHz wav> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopNemotronRealSmokeTest*"
 * ```
 */
class DesktopNemotronRealSmokeTest {
    private val sha256 = "a5c435f294eea8f88ce68dd27b8c3bfea7f777cb2fbba04fcd30eaa555f429ae"

    @Test
    fun realNemotronTranscribesSuppliedSpeech() {
        val (model, wav) = fixture()
        val samples = readPcm16Mono(wav)
        assertTrue(samples.size > 8000, "Expected at least half a second of audio")
        val started = System.nanoTime()
        DesktopBackendSpeechEngine.open("hearth_nemotron", "nemotron_3_5", model, "en", 4).use { engine ->
            val loadMs = (System.nanoTime() - started) / 1_000_000
            val inferStarted = System.nanoTime()
            val text = engine.transcribe(samples)
            val inferMs = (System.nanoTime() - inferStarted) / 1_000_000
            println("NEMOTRON_SMOKE execution=${engine.executionInfo} loadMs=$loadMs inferMs=$inferMs language=${engine.language()} text=$text")
            assertTrue(text.isNotBlank(), "Nemotron returned no text")
            assertContains(text.lowercase(), "museum opens")
            assertContains(text.lowercase(), "east entrance")
        }
    }

    @Test
    fun desktopEnginePathCompletesNemotron() = runBlocking<Unit> {
        val (model, wav) = fixture()
        val directory = Files.createTempDirectory("hearth-nemotron-smoke-")
        val selection = EngineSelection(
            "nemotron-asr-local", "nemotron-3.5-asr-0.6b", model.toString(),
            options = mapOf("sha256" to sha256),
        )
        val segments = mutableListOf<SpeechSegment>()
        val started = System.nanoTime()
        try {
            DesktopEngines(CredentialResolver { error("Local Nemotron smoke must never resolve cloud credentials") }, directory).use { engines ->
                assertTrue(engines.capabilities().single { it.id == "nemotron-asr-local" }.available)
                engines.recognize(PipelineInput.AudioFile(wav.toString()), selection, "en") { segments += it }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val text = segments.joinToString(" ") { it.text }
        println("NEMOTRON_ENGINE_SMOKE ms=$elapsed segments=${segments.size} text=$text")
        assertTrue(text.isNotBlank(), "The desktop engine path returned no text")
        assertContains(text.lowercase(), "museum opens")
        assertContains(text.lowercase(), "east entrance")
    }

    private fun fixture(): Pair<Path, Path> {
        assumeTrue(
            "Set HEARTH_NEMOTRON_SMOKE=1 and provide HEARTH_SMOKE_NEMOTRON and HEARTH_SMOKE_WAV",
            System.getenv("HEARTH_NEMOTRON_SMOKE") == "1",
        )
        val model = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_NEMOTRON")) { "HEARTH_SMOKE_NEMOTRON is required" }).toRealPath()
        val wav = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_WAV")) { "HEARTH_SMOKE_WAV is required" }).toRealPath()
        assertTrue(Files.size(model) == 741_548_352L, "The fixture is not the pinned Nemotron model")
        return model to wav
    }
}
