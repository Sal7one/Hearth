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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owner opt-in only: drives the real shared pipeline (audio file -> Whisper ->
 * installed system voice) through [HearthPipeline], not just the JNI primitive.
 *
 * Translation is deliberately off so this needs one 32 MB model instead of a
 * multi-gigabyte GGUF. No downloads, no recording, no playback.
 */
class DesktopSpeechVoiceRealSmokeTest {
    @Test
    fun speechAndVoiceCompleteTheSharedPipeline() = runBlocking<Unit> {
        assumeTrue(
            "Set HEARTH_WHISPER_SMOKE=1 and provide HEARTH_SMOKE_WHISPER and HEARTH_SMOKE_WAV",
            System.getenv("HEARTH_WHISPER_SMOKE") == "1",
        )
        withTimeout(180_000) {
            val whisper = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_WHISPER"))).toRealPath()
            val wav = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_WAV"))).toRealPath()
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(whisper))
                .joinToString("") { "%02x".format(it) }

            val voice = DesktopSystemSpeech.voices().firstOrNull { it.language.startsWith("en", true) }
                ?: error("An installed English system voice is required for this smoke")
            val output = Files.createTempDirectory("hearth-speech-voice-smoke")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            DesktopEngines(CredentialResolver { error("Local smoke must never resolve a cloud credential") }, output).use { engines ->
                val pipeline = HearthPipeline(engines, scope)
                val config = PipelineConfig(
                    speech = EngineSelection(
                        engineId = "whisper-local",
                        modelPath = whisper.toString(),
                        options = mapOf("sha256" to digest, "threads" to "2", "acceleration" to "metal"),
                    ),
                    translation = null,
                    voice = EngineSelection("system-tts"),
                    sourceLanguage = "en",
                    targetLanguage = "ar",
                    voiceId = voice.id,
                    // The English voice reads the original; no Arabic voice is assumed.
                    speakTranslatedText = false,
                )
                val session = pipeline.start(PipelineRequest(PipelineInput.AudioFile(wav.toString()), config))
                val final = try {
                    session.state.first { it.terminal }
                } finally {
                    session.cancel()
                }

                println("SPEECH_SMOKE status=${final.status} segments=${final.segments.size} audio=${final.audio.size}")
                println("SPEECH_SMOKE transcript=${final.transcript}")
                assertEquals(PipelineStatus.COMPLETED, final.status, final.error)
                assertTrue(final.transcript.isNotBlank(), "Pipeline produced no transcript")
                for (phrase in System.getenv("HEARTH_SMOKE_EXPECT").orEmpty().split("|").filter(String::isNotBlank)) {
                    assertContains(final.transcript.lowercase(), phrase.trim().lowercase(), message = "Lost '$phrase'")
                }
                assertTrue(final.audio.isNotEmpty(), "The voice stage produced no audio for the finished line")
            }
        }
    }
}
