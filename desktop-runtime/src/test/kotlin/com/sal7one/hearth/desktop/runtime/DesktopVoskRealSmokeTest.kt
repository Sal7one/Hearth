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
 * Owner opt-in only: proves the pinned Vosk v0.3.42 adapter and the shared desktop
 * engine path. The adapter re-derives the model-tree digest before loading.
 *
 * ```
 * HEARTH_VOSK_SMOKE=1 HEARTH_SMOKE_VOSK=<extracted English model> HEARTH_SMOKE_WAV=<16 kHz wav> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopVoskRealSmokeTest*"
 * ```
 */
class DesktopVoskRealSmokeTest {
    private val tree = "1855931c4d84fa0f731a5a7906c1356837335d49ee7f2ca6a6a32389bac7f8e8"

    @Test
    fun sharedBridgeTranscribesVoskEnglish() {
        val (model, wav) = fixture()
        val samples = readPcm16Mono(wav)
        val started = System.nanoTime()
        DesktopBackendSpeechEngine.open("hearth_vosk", "vosk", model, "en", 4).use { engine ->
            val loadMs = (System.nanoTime() - started) / 1_000_000
            val inferStarted = System.nanoTime()
            val text = engine.transcribe(samples)
            val inferMs = (System.nanoTime() - inferStarted) / 1_000_000
            println("VOSK_SMOKE execution=${engine.executionInfo} loadMs=$loadMs inferMs=$inferMs text=$text")
            assertTrue(text.isNotBlank(), "Vosk returned no text")
            assertContains(text.lowercase(), "museum opens")
            assertContains(text.lowercase(), "east entrance")
        }
    }

    @Test
    fun desktopEnginePathUsesVosk() = runBlocking<Unit> {
        val (model, wav) = fixture()
        val directory = Files.createTempDirectory("hearth-vosk-smoke-")
        val selection = EngineSelection(
            "vosk-local", "vosk-model-small-en-us-0.15", model.toString(),
            options = mapOf("treeSha256" to tree),
        )
        val segments = mutableListOf<SpeechSegment>()
        val started = System.nanoTime()
        try {
            DesktopEngines(CredentialResolver { error("Local Vosk smoke must never resolve cloud credentials") }, directory).use { engines ->
                assertTrue(engines.capabilities().single { it.id == "vosk-local" }.available)
                engines.recognize(PipelineInput.AudioFile(wav.toString()), selection, "en") { segments += it }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val text = segments.joinToString(" ") { it.text }
        println("VOSK_ENGINE_SMOKE ms=$elapsed segments=${segments.size} text=$text")
        assertTrue(text.isNotBlank(), "The desktop engine path returned no text")
        assertContains(text.lowercase(), "museum opens")
        assertContains(text.lowercase(), "east entrance")
    }

    private fun fixture(): Pair<Path, Path> {
        assumeTrue("Set HEARTH_VOSK_SMOKE=1 and provide HEARTH_SMOKE_VOSK and HEARTH_SMOKE_WAV", System.getenv("HEARTH_VOSK_SMOKE") == "1")
        val model = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_VOSK")) { "HEARTH_SMOKE_VOSK is required" }).toRealPath()
        val wav = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_WAV")) { "HEARTH_SMOKE_WAV is required" }).toRealPath()
        return model to wav
    }
}
