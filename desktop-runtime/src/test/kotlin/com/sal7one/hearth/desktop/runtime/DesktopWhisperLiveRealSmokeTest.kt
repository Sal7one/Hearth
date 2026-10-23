package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.desktop.DesktopWhisperNative
import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PcmFrame
import com.sal7one.hearth.core.PipelineInput
import com.sal7one.hearth.core.SpeechSegment
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** File-only opt-in: HEARTH_WHISPER_LIVE_SMOKE=1 with HEARTH_SMOKE_WHISPER/WAV.
 * Replays existing audio through the production AudioStream route without clock pacing;
 * no microphone, playback, network or absolute performance threshold is involved.
 */
class DesktopWhisperLiveRealSmokeTest {
    private fun fixture(): Pair<Path, Path> {
        assumeTrue("Set HEARTH_WHISPER_LIVE_SMOKE=1 with pinned local fixtures", System.getenv("HEARTH_WHISPER_LIVE_SMOKE") == "1")
        return Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_WHISPER"))).toRealPath() to
            Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_WAV"))).toRealPath()
    }

    @Test fun residentLiveDecoderUsesOneLanguageDetectionAndRetainsGpuProof() {
        val (model, wav) = fixture()
        assumeTrue("This hardware proof requires Metal", DesktopWhisperNative.metalAvailable())
        val samples = readPcm16Mono(wav).take(4 * 16_000).toFloatArray()
        assertTrue(samples.size >= 8_000)
        val hash = checkedHash(model)
        val start = System.nanoTime()
        val handle = DesktopWhisperNative.createWithBackend(model.toString().toByteArray(), "auto".toByteArray(), 4, DesktopCompute.METAL, 0)
        println("WHISPER_LIVE_NATIVE sha256=$hash loadMs=${elapsed(start)} samples=${samples.size}")
        try {
            repeat(2) { pass ->
                val begin = System.nanoTime()
                val text = DesktopWhisperNative.transcribeLive(handle, samples).toString(Charsets.UTF_8).trim()
                val execution = DesktopWhisperNative.executionInfo(handle).toString(Charsets.UTF_8)
                println("WHISPER_LIVE_NATIVE pass=$pass inferenceMs=${elapsed(begin)} text=$text execution=$execution")
                assertTrue(text.isNotBlank())
                assertContains(execution, "Metal GPU")
                assertContains(execution, "total size")
                assertContains(execution, "completedInference=${pass + 1}")
                assertContains(execution, "decode=live; maxTokens=96")
                assertContains(execution, "languageDetections=1")
                assertContains(execution, "detectedLanguage=en")
            }
            // A file call on the same handle must not inherit the live cache.
            // Otherwise a mixed-language file would be pinned to its first chunk.
            repeat(2) { pass ->
                assertTrue(DesktopWhisperNative.transcribe(handle, samples).isNotEmpty())
                val execution = DesktopWhisperNative.executionInfo(handle).toString(Charsets.UTF_8)
                assertContains(execution, "decode=file; maxTokens=0")
                assertContains(execution, "languageDetections=${pass + 2}")
            }
        } finally { DesktopWhisperNative.destroy(handle) }
    }

    @Test fun ordinaryConfiguredLiveRouteRunsCpuAndMetalWithTheSamePinnedAudio() = runBlocking {
        val (model, wav) = fixture()
        val hashStart = System.nanoTime()
        val hash = checkedHash(model)
        val hashMs = elapsed(hashStart)
        val samples = readPcm16Mono(wav)
        assertTrue(samples.size in 8_000..30 * 16_000)
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort((it * 32768f).toInt().coerceIn(-32768, 32767).toShort()) }
        }.array()
        val directory = Files.createTempDirectory("hearth-whisper-live-proof")
        try {
            DesktopEngines(CredentialResolver { error("Local Whisper must never resolve cloud credentials") }, directory).use { engines ->
                val modes = listOf("cpu") + if (DesktopWhisperNative.metalAvailable()) listOf("metal") else emptyList()
                for (mode in modes) {
                    val segments = mutableListOf<SpeechSegment>()
                    val started = System.nanoTime()
                    var firstMs: Long? = null
                    val frames = flow {
                        var offset = 0
                        while (offset < pcm.size) {
                            val end = minOf(offset + 3_200, pcm.size)
                            emit(PcmFrame(pcm.copyOfRange(offset, end)))
                            offset = end
                        }
                    }
                    engines.recognize(PipelineInput.AudioStream(frames), EngineSelection(
                        "whisper-local", "whisper-live-fixture", model.toString(), options = mapOf("sha256" to hash, "acceleration" to mode, "threads" to "4"),
                    ), "auto") { segment ->
                        if (firstMs == null) firstMs = elapsed(started)
                        segments += segment
                    }
                    val totalMs = elapsed(started)
                    val text = segments.joinToString(" ") { it.text }
                    println("WHISPER_LIVE_ROUTE mode=$mode sha256=$hash audioMs=${samples.size / 16} standaloneHashMs=$hashMs startupToFirstMs=$firstMs totalMs=$totalMs segments=${segments.size} text=$text")
                    assertTrue(segments.isNotEmpty())
                    assertTrue(segments.all { it.final && it.endMs > it.startMs })
                    assertContains(text.lowercase(), "museum")
                    assertEquals("en", segments.first().language)
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    private fun checkedHash(model: Path): String = sha256(model).also {
        require(it in setOf(
            "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7", // multilingual tiny Q5_1
            "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1", // large-v3-turbo Q8_0
        )) { "Use the pinned multilingual tiny Q5_1 or large-v3-turbo Q8_0 fixture" }
    }
    private fun elapsed(start: Long): Long = (System.nanoTime() - start) / 1_000_000
}
