package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.desktop.DesktopBackendSpeechNative
import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineInput
import com.sal7one.hearth.core.SpeechSegment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /**
     * Owner opt-in only, on a Windows computer with an NVIDIA GPU (RTX 30-series or newer):
     * `HEARTH_NEMOTRON_CUDA_SMOKE=1` beside the variables above. Before it loads a model on a
     * GPU other work shares, check that GPU is free. A flag, an empty transcript, a CPU
     * fallback or an absent device cannot pass: the device must report less free memory while
     * the model is loaded, and the session must name it.
     */
    @Test
    fun realNemotronTranscribesOnAnNvidiaGpuAndNeverFallsBack() = runBlocking<Unit> {
        assumeTrue("Set HEARTH_NEMOTRON_CUDA_SMOKE=1 on a computer with an NVIDIA GPU", System.getenv("HEARTH_NEMOTRON_CUDA_SMOKE") == "1")
        val (model, wav) = fixture()
        val samples = readPcm16Mono(wav)
        val library = DesktopNativeLibraries.libraryPath(NEMOTRON_CUDA_LIBRARY).toString().toByteArray()
        fun freeBytes() = Json.parseToJsonElement(DesktopBackendSpeechNative.gpuDevices(library).toString(Charsets.UTF_8))
            .jsonArray.first().jsonObject.getValue("freeBytes").jsonPrimitive.long
        val devices = DesktopBackendSpeechEngine.gpuDevices(NEMOTRON_CUDA_LIBRARY, "cuda")
        println("NEMOTRON_CUDA_SMOKE devices=$devices")
        assertTrue(devices.isNotEmpty() && devices.first().index == 0 && (devices.first().memoryBytes ?: 0) > 0, "No usable NVIDIA device was listed")

        val cpuStarted = System.nanoTime()
        val cpuText = DesktopBackendSpeechEngine.open("hearth_nemotron", "nemotron_3_5", model, "en", 4).use { it.transcribe(samples); it.transcribe(samples) }
        val cpuMs = (System.nanoTime() - cpuStarted) / 1_000_000

        val before = freeBytes()
        val loadStarted = System.nanoTime()
        DesktopBackendSpeechEngine.open(NEMOTRON_CUDA_LIBRARY, "nemotron_3_5", model, "en", 4, acceleration = "cuda:0").use { engine ->
            val loadMs = (System.nanoTime() - loadStarted) / 1_000_000
            val first = System.nanoTime()
            val text = engine.transcribe(samples)
            val firstMs = (System.nanoTime() - first) / 1_000_000
            // The weights go to the device on first use, so its memory is read after one transcription.
            val held = before - freeBytes()
            val second = System.nanoTime()
            val again = engine.transcribe(samples)
            val warmMs = (System.nanoTime() - second) / 1_000_000
            println("NEMOTRON_CUDA_SMOKE execution=${engine.executionInfo} loadMs=$loadMs firstMs=$firstMs warmMs=$warmMs heldBytes=$held " +
                "audioMs=${samples.size / 16} cpuTwoRunsMs=$cpuMs text=$text")
            assertContains(engine.executionInfo, "CUDA")
            assertContains(engine.executionInfo, devices.first().description)
            assertTrue(held > 256L * 1024 * 1024, "The model was not placed on the GPU: it holds only $held bytes of device memory")
            assertContains(text.lowercase(), "museum opens")
            assertContains(text.lowercase(), "east entrance")
            assertEquals(text, again, "The same audio must give the same transcript on the GPU")
            assertEquals(cpuText, text, "The GPU transcript differs from the CPU transcript")
        }
        assertTrue(before - freeBytes() < 64L * 1024 * 1024, "Closing the session did not return the device memory")

        // A device that is not there, and a request this library cannot serve, fail with the reason.
        val absent = assertFailsWith<Exception> { DesktopBackendSpeechEngine.open(NEMOTRON_CUDA_LIBRARY, "nemotron_3_5", model, "en", 4, acceleration = "cuda:7").close() }
        assertContains(absent.message.orEmpty(), "device 7")
        val wrong = assertFailsWith<Exception> { DesktopBackendSpeechEngine.open(NEMOTRON_CUDA_LIBRARY, "nemotron_3_5", model, "en", 4).close() }
        assertContains(wrong.message.orEmpty(), "CUDA only")

        // The same choice through the app's engine path: offered, listed, and used.
        val directory = Files.createTempDirectory("hearth-nemotron-cuda-")
        val selection = EngineSelection("nemotron-asr-local", "nemotron-3.5-asr-0.6b", model.toString(),
            options = mapOf("sha256" to sha256, "acceleration" to "cuda", "gpuDevice" to "0"))
        val segments = mutableListOf<SpeechSegment>()
        try {
            DesktopEngines(CredentialResolver { error("Local Nemotron smoke must never resolve cloud credentials") }, directory).use { engines ->
                assertContains(engines.capabilities().single { it.id == "nemotron-asr-local" }.acceleration, "cuda")
                assertEquals(devices, engines.gpuDevices("nemotron-asr-local", "cuda"))
                val started = System.nanoTime()
                engines.recognize(PipelineInput.AudioFile(wav.toString()), selection, "en") { segments += it }
                println("NEMOTRON_CUDA_ENGINE_SMOKE ms=${(System.nanoTime() - started) / 1_000_000} text=${segments.joinToString(" ") { it.text }}")
            }
        } finally { directory.toFile().deleteRecursively() }
        assertContains(segments.joinToString(" ") { it.text }.lowercase(), "east entrance")
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
