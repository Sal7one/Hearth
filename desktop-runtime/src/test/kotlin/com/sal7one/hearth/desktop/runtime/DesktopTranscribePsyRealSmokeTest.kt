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
 * Owner opt-in only: proves the packaged TranscribePsy backend transcribes real speech.
 * No downloads, no recording, no playback.
 *
 * ```
 * HEARTH_TRANSCRIBEPSY_SMOKE=1 HEARTH_SMOKE_TRANSCRIBEPSY=<gguf> HEARTH_SMOKE_WAV=<16 kHz wav> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopTranscribePsyRealSmokeTest*"
 * ```
 */
class DesktopTranscribePsyRealSmokeTest {
    private val sha256 = "a93ef324d38e410b5e49389db95d06364eddbb890b74a69f311e482d2fda6f4b"

    @Test
    fun realTranscribePsyTranscribesSuppliedSpeech() {
        val (model, wav) = fixture()
        val samples = readPcm16Mono(wav)
        assertTrue(samples.size > 8000, "Expected at least half a second of audio")
        val started = System.nanoTime()
        DesktopBackendSpeechEngine.open("hearth_transcribepsy", "transcribepsy", model, "en", 4).use { engine ->
            val loadMs = (System.nanoTime() - started) / 1_000_000
            val inferStarted = System.nanoTime()
            val text = engine.transcribe(samples)
            val inferMs = (System.nanoTime() - inferStarted) / 1_000_000
            println("TRANSCRIBEPSY_SMOKE execution=${engine.executionInfo} loadMs=$loadMs inferMs=$inferMs language=${engine.language()} text=$text")
            assertTrue(text.isNotBlank(), "TranscribePsy returned no text")
            assertContains(text.lowercase(), "museum opens")
            assertContains(text.lowercase(), "east entrance")
        }
    }

    @Test
    fun desktopEnginePathCompletesTranscribePsy() = runBlocking<Unit> {
        val (model, wav) = fixture()
        val directory = Files.createTempDirectory("hearth-transcribepsy-smoke-")
        val selection = EngineSelection(
            "transcribepsy-local", "transcribepsy-250m-eu-sub-q8", model.toString(),
            options = mapOf("sha256" to sha256),
        )
        val segments = mutableListOf<SpeechSegment>()
        val started = System.nanoTime()
        try {
            DesktopEngines(CredentialResolver { error("Local TranscribePsy smoke must never resolve cloud credentials") }, directory).use { engines ->
                assertTrue(engines.capabilities().single { it.id == "transcribepsy-local" }.available)
                engines.recognize(PipelineInput.AudioFile(wav.toString()), selection, "en") { segments += it }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val text = segments.joinToString(" ") { it.text }
        println("TRANSCRIBEPSY_ENGINE_SMOKE ms=$elapsed segments=${segments.size} text=$text")
        assertTrue(text.isNotBlank(), "The desktop engine path returned no text")
        assertContains(text.lowercase(), "museum opens")
        assertContains(text.lowercase(), "east entrance")
    }

    private fun fixture(): Pair<Path, Path> {
        assumeTrue(
            "Set HEARTH_TRANSCRIBEPSY_SMOKE=1 and provide HEARTH_SMOKE_TRANSCRIBEPSY and HEARTH_SMOKE_WAV",
            System.getenv("HEARTH_TRANSCRIBEPSY_SMOKE") == "1",
        )
        val model = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_TRANSCRIBEPSY")) { "HEARTH_SMOKE_TRANSCRIBEPSY is required" }).toRealPath()
        val wav = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_WAV")) { "HEARTH_SMOKE_WAV is required" }).toRealPath()
        assertTrue(Files.size(model) == 304_879_584L, "The fixture is not the pinned TranscribePsy model")
        return model to wav
    }
}
