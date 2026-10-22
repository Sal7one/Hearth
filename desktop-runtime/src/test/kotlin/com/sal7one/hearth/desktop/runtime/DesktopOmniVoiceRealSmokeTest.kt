package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.core.EngineSelection
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owner opt-in only: proves the staged OmniVoice runtime synthesizes real, non-silent
 * 24 kHz PCM through DesktopEngines → DesktopVoiceCache → VoiceEngine.
 *
 * OmniVoice (DAC codec) is natively 24 kHz. This smoke also writes a derived 48 kHz
 * compatibility WAV with an explicit 2× linear resampler so the artifact can be opened
 * by 44.1/48 kHz-only desktop consumers; the second file is labelled derived and is never
 * presented as native engine output.
 *
 * ```
 * HEARTH_OMNIVOICE_SMOKE=1 HEARTH_SMOKE_OMNIVOICE=<2-file model dir> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopOmniVoiceRealSmokeTest*"
 * ```
 */
class DesktopOmniVoiceRealSmokeTest {
    private data class Measurement(val path: Path, val rate: Int, val durationSeconds: Double,
                                   val peak: Double, val rms: Double, val nonZero: Int, val samples: Int)

    @Test
    fun desktopEngineSynthesizesNonSilentArabicAndEnglish() = runBlocking<Unit> {
        assumeTrue("Set HEARTH_OMNIVOICE_SMOKE=1 and provide HEARTH_SMOKE_OMNIVOICE", System.getenv("HEARTH_OMNIVOICE_SMOKE") == "1")
        val model = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_OMNIVOICE")) { "HEARTH_SMOKE_OMNIVOICE is required" }).toRealPath()
        val receipt = System.getenv("HEARTH_OMNIVOICE_SMOKE_RECEIPT")?.let(Path::of)
        val directory = Files.createTempDirectory("hearth-omnivoice-smoke-")
        val cases = mutableListOf<String>()
        try {
            DesktopEngines(CredentialResolver { error("Local OmniVoice smoke must never resolve cloud credentials") }, directory).use { engines ->
                val descriptor = engines.capabilities().single { it.id == "omnivoice-tts-local" }
                assertTrue(descriptor.available, "OmniVoice is unavailable: ${descriptor.unavailableReason}")
                assertEquals(setOf("cpu"), descriptor.acceleration)
                for ((language, text) in listOf("en" to "Hello from Hearth.", "ar" to "مرحبا بكم في هيرث.")) {
                    val selection = EngineSelection(
                        "omnivoice-tts-local", "omnivoice-tts", model.toString(),
                        options = mapOf("voice" to "design-female", "threads" to "2"),
                    )
                    val started = System.nanoTime()
                    val audio = engines.synthesize(text, selection, language, "design-female")
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    val wav = Path.of(audio.path)
                    assertTrue(Files.isRegularFile(wav) && Files.size(wav) > 44, "OmniVoice produced no WAV for $language")
                    val native = measure(wav)
                    assertEquals(24_000, native.rate, "OmniVoice must keep its native 24 kHz rate")
                    assertTrue(native.durationSeconds > 0.5, "OmniVoice returned too little audio for $language: ${native.durationSeconds} s")
                    assertTrue(native.nonZero > native.samples * 4 / 5, "OmniVoice returned mostly silence for $language")
                    assertTrue(native.peak > 0.01, "OmniVoice returned silent audio for $language")
                    val rtf = (elapsedMs / 1000.0) / native.durationSeconds
                    val derived = resampleTo48k(wav, native)
                    val resampled = measure(derived)
                    assertEquals(48_000, resampled.rate)
                    assertEquals(1, AudioSystem.getAudioInputStream(derived.toFile()).use { it.format.channels })
                    assertEquals(16, AudioSystem.getAudioInputStream(derived.toFile()).use { it.format.sampleSizeInBits })
                    assertTrue(resampled.nonZero > resampled.samples * 4 / 5, "Derived 48 kHz WAV is silent")
                    assertTrue(abs(resampled.durationSeconds - native.durationSeconds) < 0.01, "Derived 48 kHz duration drifted")
                    println(
                        "OMNIVOICE_SMOKE language=$language ms=$elapsedMs duration=${native.durationSeconds} " +
                            "rate=${native.rate} peak=${native.peak} rms=${native.rms} nonzero=${native.nonZero}/${native.samples} " +
                            "rtf=$rtf derived48k=${derived.fileName} derivedFrames=${resampled.samples} wav=$wav"
                    )
                    cases += """{"language":"$language","elapsedMs":$elapsedMs,"durationSeconds":${native.durationSeconds},""" +
                        """"sampleRate":${native.rate},"peak":${native.peak},"rms":${native.rms},""" +
                        """"nonZeroSamples":${native.nonZero},"samples":${native.samples},"rtf":$rtf,""" +
                        """"wavBytes":${Files.size(wav)},"derived48kBytes":${Files.size(derived)}}"""
                    if (receipt != null) {
                        // Keep both artifacts beside the receipt instead of the deleted temp directory.
                        val target = receipt.toAbsolutePath().parent.resolve(wav.fileName)
                        Files.createDirectories(target.parent)
                        Files.copy(wav, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        Files.copy(derived, target.resolveSibling("${wav.fileNameWithoutExtension}-48k.wav"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            if (receipt != null) {
                Files.createDirectories(receipt.toAbsolutePath().parent)
                Files.writeString(receipt, """{"schemaVersion":1,"engineId":"omnivoice-tts-local","modelId":"omnivoice-tts",""" +
                    """"nativeSampleRate":24000,"derivedSampleRate":48000,"networkProvidersCalled":false,"audioPlayed":false,"cases":[${cases.joinToString(",")}]}""" + "\n")
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun measure(path: Path): Measurement {
        val format = AudioSystem.getAudioInputStream(path.toFile()).use { it.format }
        check(format.encoding == AudioFormat.Encoding.PCM_SIGNED && format.sampleSizeInBits == 16 && format.channels == 1) { "Unexpected WAV format: $format" }
        val rate = format.sampleRate.roundToInt()
        val samples = readPcm16(path, rate)
        var peak = 0.0
        var energy = 0.0
        var nonZero = 0
        for (sample in samples) {
            val value = sample.toDouble()
            peak = maxOf(peak, abs(value))
            energy += value * value
            if (sample != 0f) nonZero++
        }
        return Measurement(path, rate, samples.size.toDouble() / rate, peak, sqrt(energy / samples.size), nonZero, samples.size)
    }

    private fun readPcm16(path: Path, expectedRate: Int): FloatArray {
        AudioSystem.getAudioInputStream(path.toFile()).use { stream ->
            assertEquals(expectedRate, stream.format.sampleRate.roundToInt())
            val bytes = stream.readBytes()
            val floats = FloatArray(bytes.size / 2)
            for (index in floats.indices) {
                val low = bytes[index * 2].toInt() and 0xFF
                val high = bytes[index * 2 + 1].toInt()
                floats[index] = ((high shl 8) or low) / 32_768f
            }
            return floats
        }
    }

    /** Explicit 2× linear interpolation; a derived compatibility copy, not engine output. */
    private fun resampleTo48k(wav: Path, native: Measurement): Path {
        val input = readPcm16(wav, native.rate)
        val output = FloatArray(native.samples * 2)
        for (index in output.indices) {
            val position = index / 2.0
            val lower = position.toInt().coerceIn(0, input.lastIndex)
            val upper = (lower + 1).coerceAtMost(input.lastIndex)
            val fraction = position - lower
            output[index] = (input[lower] * (1.0 - fraction) + input[upper] * fraction).toFloat()
        }
        val target = wav.resolveSibling("${wav.fileNameWithoutExtension}-48k.wav")
        WaveWriter(target, 48_000).use { writer -> writer.append(output); writer.publish() }
        return target
    }

    private val Path.fileNameWithoutExtension: String get() = fileName.toString().substringBeforeLast('.')
}
