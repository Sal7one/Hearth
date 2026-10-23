package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.tts.SpeechCancellation
import com.sal7one.hearth.tts.SpeechOptions
import com.sal7one.hearth.tts.SpeechVoices
import com.sal7one.hearth.tts.VoicePreference
import com.sal7one.hearth.tts.moss.MossSpeechEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Owner opt-in only: proves the packaged MOSS-TTS Nano runtime synthesizes a real,
 * non-silent WAV through the same DesktopEngines/VoiceEngine path as Supertonic.
 *
 * ```
 * HEARTH_MOSS_SMOKE=1 HEARTH_SMOKE_MOSS=<14-file model dir> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopMossRealSmokeTest*"
 * ```
 */
class DesktopMossRealSmokeTest {
    @Test
    fun directEngineEmitsAllChunks() {
        assumeTrue("Set HEARTH_MOSS_SMOKE=1 and provide HEARTH_SMOKE_MOSS", System.getenv("HEARTH_MOSS_SMOKE") == "1")
        val model = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_MOSS")) { "HEARTH_SMOKE_MOSS is required" }).toRealPath()
        val chunks = mutableListOf<Int>()
        MossSpeechEngine(model.toFile(), 2).use { engine ->
            engine.synthesize("Hello from Hearth.", "en", engine.voices.single { it.id == "Ava" }, SpeechOptions(), SpeechCancellation()) { chunks += it.samples.size }
        }
        println("MOSS_DIRECT chunks=$chunks total=${chunks.sum()}")
        val resolved = SpeechVoices.resolve(MossSpeechEngine(model.toFile(), 2).let { it.voices.also { _ -> it.close() } }, "en", VoicePreference(id = "Ava"))
        println("MOSS_RESOLVED ${resolved.voice.id}")
        assertTrue(chunks.sum() > 0, "MOSS direct engine returned no audio")
    }

    @Test
    fun desktopEngineSynthesizesNonSilentMossAudio() = runBlocking<Unit> {
        assumeTrue("Set HEARTH_MOSS_SMOKE=1 and provide HEARTH_SMOKE_MOSS", System.getenv("HEARTH_MOSS_SMOKE") == "1")
        val model = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_MOSS")) { "HEARTH_SMOKE_MOSS is required" }).toRealPath()
        val directory = Files.createTempDirectory("hearth-moss-smoke-")
        val selection = EngineSelection(
            "moss-tts-local", "moss-tts-nano", model.toString(),
            options = mapOf("voice" to "Ava", "threads" to "2"),
        )
        try {
            val started = System.nanoTime()
            val audio = DesktopEngines(CredentialResolver { error("Local MOSS smoke must never resolve cloud credentials") }, directory).use { engines ->
                assertTrue(engines.capabilities().single { it.id == "moss-tts-local" }.available)
                engines.synthesize("Hello from Hearth.", selection, "en", "Ava")
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val wav = Path.of(audio.path)
            assertTrue(Files.isRegularFile(wav) && Files.size(wav) > 44, "MOSS produced no WAV")
            val samples = readPcm16Mono(wav)
            val duration = samples.size / 48_000.0
            val nonZero = samples.count { it != 0f }
            val peak = samples.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
            val rms = kotlin.math.sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)
            println("MOSS_SMOKE ms=$elapsed durationSeconds=$duration peak=$peak rms=$rms nonZero=$nonZero/${samples.size} path=$wav")
            assertTrue(duration > 0.25, "MOSS returned too little audio: $duration s")
            assertTrue(nonZero > samples.size * 9 / 10, "MOSS returned mostly silence")
            assertTrue(peak > 0.01f, "MOSS returned silent audio")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
