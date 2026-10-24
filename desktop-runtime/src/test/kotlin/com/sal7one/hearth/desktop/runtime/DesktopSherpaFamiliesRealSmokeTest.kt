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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owner opt-in only: proves Omnilingual CTC 300M and Phonon 2 run through the same
 * [DesktopBackendSpeechEngine] and [DesktopEngines] path as TranscribePsy/Nemotron/
 * Moonshine. No family-specific engine code.
 *
 * ```
 * HEARTH_SHERPA_FAMILIES_SMOKE=1 \
 *   HEARTH_SMOKE_OMNILINGUAL=<extracted omnilingual root> \
 *   HEARTH_SMOKE_PHONON=<extracted phonon-2 root> \
 *   HEARTH_SMOKE_WAV=<16 kHz wav> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopSherpaFamiliesRealSmokeTest*"
 * ```
 */
class DesktopSherpaFamiliesRealSmokeTest {
    private data class Fixture(val omnilingual: Path, val phonon: Path, val wav: Path)

    private fun fixture(): Fixture {
        assumeTrue(
            "Set HEARTH_SHERPA_FAMILIES_SMOKE=1 and provide HEARTH_SMOKE_OMNILINGUAL, HEARTH_SMOKE_PHONON and HEARTH_SMOKE_WAV",
            System.getenv("HEARTH_SHERPA_FAMILIES_SMOKE") == "1",
        )
        fun root(name: String) = Path.of(requireNotNull(System.getenv(name)) { "$name is required" }).toRealPath()
        val wav = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_WAV")) { "HEARTH_SMOKE_WAV is required" }).toRealPath()
        return Fixture(root("HEARTH_SMOKE_OMNILINGUAL"), root("HEARTH_SMOKE_PHONON"), wav)
    }

    @Test
    fun sharedBridgeTranscribesOmnilingualCtc() {
        val fixture = fixture()
        val samples = readPcm16Mono(fixture.wav)
        val started = System.nanoTime()
        DesktopBackendSpeechEngine.open(
            "hearth_desktop_qwen3_asr", "omnilingual_ctc", fixture.omnilingual.resolve("model.int8.onnx"), "auto", 4,
            entrySymbol = "hearth_omnilingual_backend_v1",
            tokenizer = fixture.omnilingual.resolve("tokens.txt"),
            maximumMs = 15000,
        ).use { engine ->
            val loadMs = (System.nanoTime() - started) / 1_000_000
            val inferStarted = System.nanoTime()
            val text = engine.transcribe(samples)
            val inferMs = (System.nanoTime() - inferStarted) / 1_000_000
            println("OMNILINGUAL_SMOKE execution=${engine.executionInfo} loadMs=$loadMs inferMs=$inferMs language=${engine.language()} text=$text")
            assertTrue(text.isNotBlank(), "Omnilingual CTC returned no text")
            assertContains(text.lowercase(), "museum")
            assertContains(text.lowercase(), "entrance")
        }
    }

    @Test
    fun sharedBridgeTranscribesPhonon() {
        val fixture = fixture()
        val samples = readPcm16Mono(fixture.wav)
        val started = System.nanoTime()
        DesktopBackendSpeechEngine.open(
            "hearth_desktop_qwen3_asr", "phonon", fixture.phonon.resolve("tokens.txt"), "en", 4,
            entrySymbol = "hearth_phonon_backend_v1",
            encoder = fixture.phonon.resolve("encoder.int8.onnx"),
            decoder = fixture.phonon.resolve("decoder.int8.onnx"),
            convFrontend = fixture.phonon.resolve("joiner.int8.onnx"),
            maximumMs = 15000,
        ).use { engine ->
            val loadMs = (System.nanoTime() - started) / 1_000_000
            val inferStarted = System.nanoTime()
            val text = engine.transcribe(samples)
            val inferMs = (System.nanoTime() - inferStarted) / 1_000_000
            println("PHONON_SMOKE execution=${engine.executionInfo} loadMs=$loadMs inferMs=$inferMs language=${engine.language()} text=$text")
            assertTrue(text.isNotBlank(), "Phonon 2 returned no text")
            assertContains(text.lowercase(), "museum")
            assertContains(text.lowercase(), "entrance")
            assertEquals("en", engine.language())
        }
    }

    @Test
    fun desktopEnginePathUsesBothFamilies() = runBlocking<Unit> {
        val fixture = fixture()
        val directory = Files.createTempDirectory("hearth-sherpa-families-smoke-")
        try {
            DesktopEngines(CredentialResolver { error("Local sherpa smoke must never resolve cloud credentials") }, directory).use { engines ->
                val capabilities = engines.capabilities().associateBy { it.id }
                assertTrue(capabilities.getValue("omnilingual-local").available)
                assertTrue(capabilities.getValue("phonon-local").available)
                assertEquals(setOf("en", "ar", "ru", "zh"), capabilities.getValue("omnilingual-local").languages)
                assertEquals(PHONON_25_LANGUAGES, capabilities.getValue("phonon-local").languages)
                for ((id, profile, root) in listOf(
                    Triple("omnilingual-local", "omnilingual-ctc-300m-v2-int8", fixture.omnilingual),
                    Triple("phonon-local", "phonon-2", fixture.phonon),
                )) {
                    val segments = mutableListOf<SpeechSegment>()
                    val started = System.nanoTime()
                    engines.recognize(PipelineInput.AudioFile(fixture.wav.toString()), EngineSelection(id, profile, root.toString()), "auto") { segments += it }
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    val text = segments.joinToString(" ") { it.text }
                    println("SHERPA_ENGINE_SMOKE id=$id ms=$elapsed segments=${segments.size} text=$text")
                    assertTrue(text.isNotBlank(), "The desktop engine path returned no text for $id")
                    assertContains(text.lowercase(), "museum")
                    assertContains(text.lowercase(), "entrance")
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
