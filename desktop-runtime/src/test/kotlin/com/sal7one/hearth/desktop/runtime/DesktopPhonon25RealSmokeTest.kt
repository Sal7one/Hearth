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

/** Explicit opt-in with real, pinned model files; exercises the production JNI and engine route. */
class DesktopPhonon25RealSmokeTest {
    @Test fun multilingualCheckpointTranscribesEnglishAndRussianWithoutInventingAutoLanguage() = runBlocking<Unit> {
        assumeTrue("Set HEARTH_PHONON_25_SMOKE=1 and HEARTH_SMOKE_PHONON_25", System.getenv("HEARTH_PHONON_25_SMOKE") == "1")
        val model = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_PHONON_25"))).toRealPath()
        val audio = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_AUDIO_ROOT")))
        val directory = Files.createTempDirectory("hearth-phonon25-smoke-")
        try {
            DesktopEngines(CredentialResolver { error("Local speech must never resolve a provider credential") }, directory).use { engines ->
                val selection = EngineSelection("phonon-local", "phonon-2.5", model.toString())
                for ((spoken, fixture) in listOf("en" to "en", "ru" to "ru", "auto" to "ru")) {
                    val segments = mutableListOf<SpeechSegment>()
                    val started = System.nanoTime()
                    engines.recognize(PipelineInput.AudioFile(audio.resolve("$fixture/1897.wav").toString()), selection, spoken) { segments += it }
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    val text = segments.joinToString(" ") { it.text }
                    println("PHONON_25_ENGINE_SMOKE source=$spoken fixture=$fixture ms=$elapsed segments=${segments.size} text=$text")
                    assertTrue(segments.isNotEmpty() && segments.all { it.final && it.text.isNotBlank() })
                    assertEquals(segments.size, segments.map { it.id }.distinct().size, "Final segment IDs must be unique")
                    assertTrue(text.any { if (fixture == "ru") it in '\u0400'..'\u04ff' else it in 'a'..'z' }, "Expected $fixture recognition")
                    assertContains(text.lowercase(), if (fixture == "ru") "атлантическим" else "atlantic ocean")
                    assertTrue(segments.all { it.language == spoken.takeUnless { source -> source == "auto" } }, "Source identity must reflect actual adapter knowledge")
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
