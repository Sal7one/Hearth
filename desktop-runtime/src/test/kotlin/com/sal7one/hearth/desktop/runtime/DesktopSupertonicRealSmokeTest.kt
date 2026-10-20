package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.*
import com.sal7one.hearth.tts.supertonic.SupertonicCatalog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.test.*

/** Opt-in real inference. Never downloads models, plays audio, uses a microphone or calls providers. */
class DesktopSupertonicRealSmokeTest {
    @Test fun arabicAndEnglishFemaleAndMaleVoicesCompleteTheSharedPipeline() = runBlocking<Unit> {
        val supplied = System.getenv("HEARTH_SUPERTONIC_SMOKE_ROOT")
        assumeTrue("Supply the pinned Supertonic 3 model directory to opt into real synthesis", !supplied.isNullOrBlank())
        withTimeout(180_000) {
            val models = Path.of(requireNotNull(supplied)).toRealPath()
            val directory = Files.createTempDirectory("hearth-supertonic-real-smoke-")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val cases = mutableListOf<JsonObject>()
            try {
                DesktopEngines(CredentialResolver { error("Local voice smoke must never resolve cloud credentials") }, directory).use { engines ->
                    assertTrue(engines.capabilities().single { it.id == "supertonic-local" }.available)
                    val pipeline = HearthPipeline(engines, scope)
                    val selection = EngineSelection("supertonic-local", "supertonic-3", models.toString(), options = mapOf("steps" to "5"))
                    for ((language, text) in listOf("en" to "Hello. The article is ready to read.", "ar" to "مرحبًا. هذا المقال جاهز للقراءة.")) {
                        for (voice in listOf("F1", "M1")) {
                            val started = System.nanoTime()
                            val session = pipeline.start(PipelineRequest(PipelineInput.Text(text), PipelineConfig(
                                voice = selection, sourceLanguage = language, voiceId = voice,
                            )))
                            val final = session.state.first { it.terminal }
                            assertEquals(PipelineStatus.COMPLETED, final.status, final.error)
                            assertEquals(text, final.transcript)
                            assertEquals(1, final.audio.size)
                            val output = Path.of(final.audio.single().path)
                            var durationMs = 0L
                            var peak = 0
                            AudioSystem.getAudioInputStream(output.toFile()).use { audio ->
                                assertEquals(44_100f, audio.format.sampleRate)
                                assertEquals(1, audio.format.channels)
                                assertEquals(16, audio.format.sampleSizeInBits)
                                durationMs = audio.frameLength * 1000 / 44_100
                                val pcm = ByteBuffer.wrap(audio.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                while (pcm.hasRemaining()) peak = maxOf(peak, abs(pcm.get().toInt()))
                            }
                            assertTrue(durationMs in 100..60_000, "Invalid output duration for $language/$voice")
                            assertTrue(peak > 100, "Output is silent for $language/$voice")
                            cases += buildJsonObject {
                                put("language", language); put("voice", voice); put("status", final.status.name)
                                put("synthesisMs", (System.nanoTime() - started) / 1_000_000)
                                put("audioDurationMs", durationMs); put("wavBytes", Files.size(output)); put("peakPcm16", peak)
                            }
                        }
                    }
                }
                System.getenv("HEARTH_SUPERTONIC_SMOKE_RECEIPT")?.let { destination ->
                    val report = buildJsonObject {
                        put("schemaVersion", 1); put("asOf", java.time.LocalDate.now().toString())
                        put("platform", DesktopNativeLibraries.platform); put("status", "COMPLETED")
                        put("modelFamily", "Supertonic 3"); put("acceleration", "cpu")
                        put("networkProvidersCalled", false); put("microphoneUsed", false); put("audioPlayed", false)
                        put("modelFiles", buildJsonObject {
                            SupertonicCatalog.assets.forEach { asset -> put(asset.filename, sha256(models.resolve(asset.filename))) }
                        })
                        put("cases", JsonArray(cases))
                        put("limits", JsonArray(listOf(
                            "Single host and short fixtures; first call includes loading and all calls reuse the same resident engine.",
                            "Non-silent WAV generation proves execution, not pronunciation, voice preference or broad language quality.",
                            "No audio was played; owner listening evaluation remains necessary.",
                        ).map(::JsonPrimitive)))
                    }
                    val path = Path.of(destination)
                    path.toAbsolutePath().parent.let(Files::createDirectories)
                    Files.writeString(path, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
                }
            } finally {
                scope.cancel(); scope.coroutineContext[Job]?.join()
                directory.toFile().deleteRecursively()
            }
        }
    }
}
