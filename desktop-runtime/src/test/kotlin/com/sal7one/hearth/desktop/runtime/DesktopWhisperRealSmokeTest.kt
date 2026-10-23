package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.desktop.DesktopWhisperNative
import org.junit.Assume.assumeTrue
import java.nio.file.Path
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
            val text = DesktopWhisperNative.transcribe(handle, samples).toString(Charsets.UTF_8).trim()
            val execution = DesktopWhisperNative.executionInfo(handle).toString(Charsets.UTF_8)
            println("WHISPER_SMOKE execution=$execution")
            println("WHISPER_SMOKE samples=${samples.size} text=$text")
            assertTrue(text.isNotBlank(), "Whisper returned no text for real speech")
            assertContains(execution, "completedInference=1")
            assertContains(execution, "decode=file; maxTokens=0")
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

}
