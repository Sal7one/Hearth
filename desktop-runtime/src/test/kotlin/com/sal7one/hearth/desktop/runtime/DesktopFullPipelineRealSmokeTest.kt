package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Owner opt-in only: the whole desktop chain in one session — file audio,
 * Whisper (Metal), local GGUF translation, installed system voice.
 *
 * ```
 * HEARTH_FULL_SMOKE=1 HEARTH_SMOKE_WHISPER=<ggml> HEARTH_SMOKE_TRANSLATION=<gguf> \
 * HEARTH_SMOKE_WAV=<wav> ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test \
 *   --tests "*DesktopFullPipelineRealSmokeTest*"
 * ```
 */
class DesktopFullPipelineRealSmokeTest {
    @Test
    fun speechTranslationAndVoiceCompleteInOneSession() = runBlocking<Unit> {
        assumeTrue(
            "Set HEARTH_FULL_SMOKE=1 with the whisper, translation and wav fixtures",
            System.getenv("HEARTH_FULL_SMOKE") == "1",
        )
        withTimeout(300_000) {
            fun fixture(name: String) = Path.of(requireNotNull(System.getenv(name)) { "$name is required" }).toRealPath()
            val whisper = fixture("HEARTH_SMOKE_WHISPER")
            val translation = fixture("HEARTH_SMOKE_TRANSLATION")
            val wav = fixture("HEARTH_SMOKE_WAV")
            fun digest(path: Path): String {
                val md = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(path).use { stream ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        md.update(buffer, 0, read)
                    }
                }
                return md.digest().joinToString("") { "%02x".format(it) }
            }

            val voice = DesktopSystemSpeech.voices().firstOrNull { it.language.startsWith("en", true) }
                ?: error("An installed English system voice is required")
            val output = Files.createTempDirectory("hearth-full-smoke")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            DesktopEngines(CredentialResolver { error("Local smoke must never resolve a cloud credential") }, output).use { engines ->
                val pipeline = HearthPipeline(engines, scope)
                val config = PipelineConfig(
                    speech = EngineSelection(
                        engineId = "whisper-local",
                        modelPath = whisper.toString(),
                        options = mapOf("sha256" to digest(whisper), "threads" to "2", "acceleration" to "metal"),
                    ),
                    translation = EngineSelection(
                        engineId = "llama-translation-local",
                        modelPath = translation.toString(),
                        options = mapOf(
                            "sha256" to digest(translation),
                            "threads" to "2",
                            "promptMode" to "raw",
                            "family" to "milmmt-46",
                            "acceleration" to "metal",
                        ),
                    ),
                    voice = EngineSelection("system-tts"),
                    sourceLanguage = "en",
                    targetLanguage = "ar",
                    voiceId = voice.id,
                    // The installed English voice reads the original; Arabic speech is not assumed.
                    speakTranslatedText = false,
                )
                val started = System.nanoTime()
                val session = pipeline.start(PipelineRequest(PipelineInput.AudioFile(wav.toString()), config))
                val final = try {
                    session.state.first { it.terminal }
                } finally {
                    session.cancel()
                }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000

                println("FULL_SMOKE status=${final.status} ms=$elapsedMs segments=${final.segments.size} audio=${final.audio.size}")
                println("FULL_SMOKE original=${final.transcript}")
                println("FULL_SMOKE translated=${final.translatedText}")
                assertEquals(PipelineStatus.COMPLETED, final.status, final.error)
                assertTrue(final.transcript.contains("museum", ignoreCase = true), "Speech stage lost the transcript")
                assertTrue(final.translatedText.isNotBlank(), "Translation stage produced nothing")
                assertNotEquals(final.transcript.trim(), final.translatedText.trim(), "Translation returned the source unchanged")
                assertTrue(final.audio.isNotEmpty(), "Voice stage produced no audio")
            }
        }
    }
}
