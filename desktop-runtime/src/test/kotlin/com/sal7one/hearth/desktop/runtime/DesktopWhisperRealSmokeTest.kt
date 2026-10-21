package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.desktop.DesktopWhisperNative
import org.junit.Assume.assumeTrue
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Owner opt-in only: proves the packaged desktop Whisper bundle turns real speech
 * into text on the current host. No downloads, no recording, no playback.
 *
 * ```
 * HEARTH_WHISPER_SMOKE=1 HEARTH_SMOKE_WHISPER=<ggml model> HEARTH_SMOKE_WAV=<16 kHz mono wav> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopWhisperRealSmokeTest*"
 * ```
 */
class DesktopWhisperRealSmokeTest {
    @Test
    fun realWhisperTranscribesASuppliedWav() {
        assumeTrue(
            "Set HEARTH_WHISPER_SMOKE=1 and provide HEARTH_SMOKE_WHISPER and HEARTH_SMOKE_WAV",
            System.getenv("HEARTH_WHISPER_SMOKE") == "1",
        )
        val model = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_WHISPER")) { "HEARTH_SMOKE_WHISPER is required" }).toRealPath()
        val wav = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_WAV")) { "HEARTH_SMOKE_WAV is required" }).toRealPath()
        val samples = readPcm16Mono(wav)
        assertTrue(samples.size > 8000, "Expected at least half a second of audio, got ${samples.size} samples")

        val metal = DesktopWhisperNative.metalAvailable()
        val handle = DesktopWhisperNative.create(model.toString().toByteArray(), "en".toByteArray(), 2, metal)
        try {
            val execution = DesktopWhisperNative.executionInfo(handle).toString(Charsets.UTF_8)
            val text = DesktopWhisperNative.transcribe(handle, samples).toString(Charsets.UTF_8).trim()
            println("WHISPER_SMOKE execution=$execution")
            println("WHISPER_SMOKE samples=${samples.size} text=$text")
            assertTrue(text.isNotBlank(), "Whisper returned no text for real speech")
            val expected = System.getenv("HEARTH_SMOKE_EXPECT").orEmpty()
                .split("|")
                .map(String::trim)
                .filter(String::isNotEmpty)
            for (phrase in expected) {
                assertContains(text.lowercase(), phrase.lowercase(), message = "Transcript lost '$phrase'")
            }
        } finally {
            DesktopWhisperNative.destroy(handle)
        }
    }

    /** Converts any readable WAV to [−1, 1] mono floats at 16 kHz, the rate Whisper expects. */
    private fun readPcm16Mono(path: Path): FloatArray {
        AudioSystem.getAudioInputStream(path.toFile()).use { raw ->
            val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16_000f, 16, 1, 2, 16_000f, false)
            AudioSystem.getAudioInputStream(target, raw).use { converted ->
                val bytes = converted.readBytes()
                val floats = FloatArray(bytes.size / 2)
                for (index in floats.indices) {
                    val low = bytes[index * 2].toInt() and 0xFF
                    val high = bytes[index * 2 + 1].toInt()
                    floats[index] = ((high shl 8) or low) / 32_768f
                }
                return floats
            }
        }
    }
}
