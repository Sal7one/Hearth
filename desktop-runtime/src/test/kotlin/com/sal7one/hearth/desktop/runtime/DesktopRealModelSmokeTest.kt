package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.translation.LocalTranslationNative
import com.sal7one.common_jni.desktop.DesktopWhisperNative
import com.sal7one.hearth.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Explicit owner opt-in only: never downloads weights, records audio, plays sound or calls providers. */
class DesktopRealModelSmokeTest {
    @Test fun realWhisperEuroAndInstalledVoiceCompleteTheSharedPipeline() = runBlocking<Unit> {
        assumeTrue("Set HEARTH_DESKTOP_REAL_SMOKE=1 and provide the three local fixture paths", System.getenv("HEARTH_DESKTOP_REAL_SMOKE") == "1")
        withTimeout(120_000) {
            fun fixture(variable: String) = Path.of(requireNotNull(System.getenv(variable)) { "$variable is required" }).toRealPath()
            val whisper = fixture("HEARTH_SMOKE_WHISPER")
            val translation = fixture("HEARTH_SMOKE_TRANSLATION")
            val wav = fixture("HEARTH_SMOKE_WAV")
            val whisperSha = sha256(whisper)
            val translationSha = sha256(translation)
            assertEquals("c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b", whisperSha, "Smoke requires the pinned Whisper tiny.en Q5_1 file")
            assertEquals("f22ab4b628f6e3cd12ec2e5aa6d1e460be2fa83f25cb6a8f5dd6d4d77b285b66", translationSha, "Smoke requires the pinned EuroLLM Q4_K_M file")
            val timings = mutableMapOf<String, Long>()
            suspend fun <T> measured(name: String, block: suspend () -> T): T {
                val started = System.nanoTime()
                return block().also { timings[name] = (System.nanoTime() - started) / 1_000_000 }
            }
            val output = Files.createTempDirectory("hearth-real-desktop-smoke")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val installed = DesktopSystemSpeech.voices().firstOrNull { it.language.startsWith("en", true) }
                    ?: error("A verified installed English system voice is required for this smoke")
                var placement = ""
                var snapshot: PipelineSnapshot? = null
                var cpuEnglish = ""
                var gpuEnglish = ""
                var wavBytes = 0L
                DesktopEngines(CredentialResolver { error("This smoke must never resolve a cloud credential") }, output).use { engines ->
                    val wrongLanguage = assertFailsWith<IllegalStateException> {
                        withContext(Dispatchers.IO) { DesktopWhisperNative.create(whisper.toString().toByteArray(), "ar".toByteArray(), 2, false) }
                    }
                    assertContains(wrongLanguage.message.orEmpty(), "English-only")
                    val capabilities = engines.capabilities().associateBy { it.id }
                    for (id in listOf("whisper-local", "llama-translation-local")) {
                        assertTrue(capabilities.getValue(id).available, capabilities.getValue(id).unavailableReason)
                        assertTrue("metal" in capabilities.getValue(id).acceleration, "$id must expose a real Metal device")
                    }
                    val translationOptions = mapOf("sha256" to translationSha, "threads" to "2", "promptMode" to "euro", "family" to "eurollm")
                    val gpu = EngineSelection("llama-translation-local", modelPath = translation.toString(), options = translationOptions + ("acceleration" to "metal"))
                    // Independently capture the actual loader offload, not merely a requested flag.
                    measured("metalPlacementLoadMs") {
                        val handle = AtomicLong()
                        try {
                            joinedBlocking({ handle.get().takeIf { it != 0L }?.let(LocalTranslationNative::cancel) }) {
                                handle.set(LocalTranslationNative.create(translation.toString().toByteArray(), 2, 2, 99))
                            }
                            placement = LocalTranslationNative.executionInfo(handle.get()).toString(Charsets.UTF_8)
                        } finally { withContext(NonCancellable + Dispatchers.IO) { handle.getAndSet(0).takeIf { it != 0L }?.let(LocalTranslationNative::destroy) } }
                    }
                    assertContains(placement, "offloaded ")
                    val config = PipelineConfig(
                        speech = EngineSelection("whisper-local", modelPath = whisper.toString(), options = mapOf("sha256" to whisperSha, "threads" to "2", "acceleration" to "metal")),
                        translation = gpu,
                        voice = EngineSelection("system-tts"),
                        sourceLanguage = "en", targetLanguage = "ar", voiceId = installed.id,
                        // The host's verified English voice reads the original; no Arabic voice is assumed.
                        speakTranslatedText = false,
                    )
                    val pipeline = HearthPipeline(engines, scope)
                    snapshot = measured("speechTranslationVoicePipelineMs") {
                        val session = pipeline.start(PipelineRequest(PipelineInput.AudioFile(wav.toString()), config))
                        try { session.state.first { it.terminal } } finally { session.cancel() }
                    }
                    val result = requireNotNull(snapshot)
                    assertEquals(PipelineStatus.COMPLETED, result.status, result.error)
                    assertTrue(result.transcript.contains("not send", true), result.transcript)
                    assertTrue(result.transcript.contains("42"), result.transcript)
                    assertTrue(result.translatedText.any { it in '\u0600'..'\u06ff' }, "Expected actual Arabic output: ${result.translatedText}")
                    assertEquals(1, result.audio.size)
                    val audio = Path.of(result.audio.single().path)
                    assertTrue(DesktopAudioFiles.supports(audio), "System TTS must produce an actual readable PCM WAV")
                    wavBytes = Files.size(audio)
                    assertTrue(wavBytes > 44)
                    assertEquals(result.transcript, result.audio.single().text)
                    val arabic = "لا ترسل الدفعة اليوم. المجموع ٤٢ دولارًا. أبقِ الباب مغلقًا."
                    cpuEnglish = measured("arabicToEnglishCpuMs") { engines.translate(arabic, gpu.copy(options = translationOptions + ("acceleration" to "cpu")), "ar", "en") }
                    gpuEnglish = measured("arabicToEnglishMetalMs") { engines.translate(arabic, gpu, "ar", "en") }
                    assertTrue(cpuEnglish.isNotBlank())
                    assertTrue(gpuEnglish.isNotBlank())
                }
                val result = requireNotNull(snapshot)
                val receipt = buildJsonObject {
                    put("schemaVersion", 1); put("asOf", "2026-10-20"); put("platform", DesktopNativeLibraries.platform)
                    put("status", result.status.name)
                    put("englishOnlyWhisperRejectsExplicitArabic", true)
                    require(System.getProperty("hearth.native.dir") == null) { "This packaged-bundle smoke does not accept external native overrides" }
                    val nativeManifest = javaClass.getResourceAsStream("/native/${DesktopNativeLibraries.platform}/manifest.json")
                        ?.use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject }
                        ?: error("The packaged native provenance manifest is missing")
                    put("nativeSourceSetSha256", nativeManifest.getValue("sourceSetSha256"))
                    put("modelWeightsBundled", false); put("networkProvidersCalled", false); put("microphoneUsed", false); put("audioPlayed", false)
                    putJsonObject("models") {
                        putJsonObject("speech") { put("filename", whisper.fileName.toString()); put("sha256", whisperSha); put("source", "https://huggingface.co/ggerganov/whisper.cpp") }
                        putJsonObject("translation") {
                            put("filename", translation.fileName.toString()); put("sha256", translationSha)
                            put("source", "https://huggingface.co/QuantFactory/EuroLLM-1.7B-Instruct-GGUF/resolve/4126f034ddfe7fd39ca3378ae58d3b1d74065315/EuroLLM-1.7B-Instruct.Q4_K_M.gguf")
                        }
                    }
                    put("fixtureSha256", sha256(wav)); put("translationExecutionInfo", placement)
                    put("transcript", result.transcript); put("arabicTranslation", result.translatedText)
                    put("arabicToEnglishCpu", cpuEnglish); put("arabicToEnglishMetal", gpuEnglish)
                    putJsonObject("tts") {
                        put("provider", "installed system voice"); put("voice", installed.name); put("language", installed.language)
                        put("gender", installed.gender); put("reads", "original English transcript"); put("wavBytes", wavBytes)
                    }
                    putJsonObject("timingsMs") { timings.forEach { (key, value) -> put(key, value) } }
                    putJsonArray("limits") {
                        add("Single local fixture and host; timings include model load and kernel caching and are not a benchmark.")
                        add("Translation meaning, Arabic pronunciation, and phone/Windows behavior require separate owner evaluation.")
                        add("System TTS read the original English transcript; no installed Arabic voice was assumed.")
                        add("No Supertonic model inference was performed by this test.")
                    }
                }
                val destination = Path.of(requireNotNull(System.getenv("HEARTH_DESKTOP_SMOKE_RECEIPT")) { "Set the receipt output path explicitly" })
                Files.createDirectories(destination.toAbsolutePath().parent)
                Files.writeString(destination, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), receipt) + "\n")
            } finally {
                scope.coroutineContext[Job]?.cancelAndJoin()
                withContext(NonCancellable + Dispatchers.IO) { output.toFile().deleteRecursively() }
            }
        }
    }
}
