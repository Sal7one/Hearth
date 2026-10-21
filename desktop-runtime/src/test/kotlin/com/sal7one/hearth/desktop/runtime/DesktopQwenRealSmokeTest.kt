package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.desktop.DesktopQwenNative
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
 * Owner opt-in only: proves the packaged desktop Qwen3-ASR bundle turns real speech
 * into text on the current host. No downloads, no recording, no playback.
 *
 * ```
 * HEARTH_QWEN_SMOKE=1 HEARTH_SMOKE_QWEN_DIR=<extracted model dir> HEARTH_SMOKE_WAV=<16 kHz mono wav> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopQwenRealSmokeTest*"
 * ```
 */
class DesktopQwenRealSmokeTest {
    private val expected: List<String> = System.getenv("HEARTH_SMOKE_EXPECT").orEmpty()
        .split("|").map(String::trim).filter(String::isNotEmpty)
        .ifEmpty { listOf("museum opens at nine", "east entrance") }

    @Test
    fun realQwenTranscribesASuppliedWav() {
        val paths = fixtures()
        val model = paths.first
        val wav = paths.second
        val samples = readPcm16Mono(wav)
        assertTrue(samples.size > 8000, "Expected at least half a second of audio, got ${samples.size} samples")

        val verifyStarted = System.nanoTime()
        QwenSpeechModel.verify(model)
        val verifyMs = (System.nanoTime() - verifyStarted) / 1_000_000
        val createStarted = System.nanoTime()
        val handle = DesktopQwenNative.create(model.toString().toByteArray(), "en".toByteArray(), 4)
        try {
            val loadMs = (System.nanoTime() - createStarted) / 1_000_000
            val execution = DesktopQwenNative.executionInfo(handle).toString(Charsets.UTF_8)
            val started = System.nanoTime()
            val text = DesktopQwenNative.transcribe(handle, samples).toString(Charsets.UTF_8).trim()
            val inferMs = (System.nanoTime() - started) / 1_000_000
            val audioSeconds = samples.size / 16_000.0
            val language = QwenSpeechModel.detected(DesktopQwenNative.language(handle).toString(Charsets.UTF_8))
            println("QWEN_SMOKE execution=$execution")
            println("QWEN_SMOKE samples=${samples.size} audioSeconds=$audioSeconds verifyMs=$verifyMs loadMs=$loadMs inferMs=$inferMs rtf=${"%.3f".format(inferMs / 1000.0 / audioSeconds)}")
            println("QWEN_SMOKE language=$language text=$text")
            assertTrue(text.isNotBlank(), "Qwen3-ASR returned no text for real speech")
            for (phrase in expected) assertContains(text.lowercase(), phrase.lowercase(), message = "Transcript lost '$phrase'")
            assertEquals("en", language)
            receipt(mapOf(
                "status" to "COMPLETED", "engine" to QwenSpeechModel.ENGINE_ID, "execution" to execution,
                "samples" to samples.size.toString(), "audioSeconds" to audioSeconds.toString(),
                "verifyMs" to verifyMs.toString(), "loadMs" to loadMs.toString(), "inferMs" to inferMs.toString(),
                "language" to (language ?: ""), "text" to text,
            ))
        } finally {
            DesktopQwenNative.destroy(handle)
        }
    }

    @Test
    fun realQwenCompletesTheDesktopEnginePath() = runBlocking<Unit> {
        val (model, wav) = fixtures()
        val directory = Files.createTempDirectory("hearth-qwen-smoke-")
        val selection = EngineSelection(QwenSpeechModel.ENGINE_ID, "qwen3-asr-0.6b", model.toString(), options = mapOf("threads" to "4"))
        val segments = mutableListOf<SpeechSegment>()
        val started = System.nanoTime()
        try {
            DesktopEngines(CredentialResolver { error("Local Qwen smoke must never resolve cloud credentials") }, directory).use { engines ->
                assertTrue(engines.capabilities().single { it.id == QwenSpeechModel.ENGINE_ID }.available)
                engines.recognize(PipelineInput.AudioFile(wav.toString()), selection, "en") { segments += it }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val text = segments.joinToString(" ") { it.text }
        println("QWEN_ENGINE_SMOKE ms=$elapsed segments=${segments.size} text=$text")
        assertTrue(text.isNotBlank(), "The desktop engine path returned no text")
        assertTrue(segments.all { it.final }, "Qwen3-ASR must emit only final segments")
        for (phrase in expected) assertContains(text.lowercase(), phrase.lowercase(), message = "Transcript lost '$phrase'")
    }

    private fun fixtures(): Pair<Path, Path> {
        assumeTrue(
            "Set HEARTH_QWEN_SMOKE=1 and provide HEARTH_SMOKE_QWEN_DIR and HEARTH_SMOKE_WAV",
            System.getenv("HEARTH_QWEN_SMOKE") == "1",
        )
        val model = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_QWEN_DIR")) { "HEARTH_SMOKE_QWEN_DIR is required" }).toRealPath()
        val wav = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_WAV")) { "HEARTH_SMOKE_WAV is required" }).toRealPath()
        return model to wav
    }

    private fun receipt(values: Map<String, String>) {
        val directory = Path.of(System.getProperty("user.dir")).resolve("build/smoke/qwen")
        Files.createDirectories(directory)
        val json = values.entries.joinToString(",\n") { (key, value) ->
            "  \"${key}\": \"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        Files.writeString(directory.resolve("receipt.json"), "{\n$json\n}\n")
    }
}
