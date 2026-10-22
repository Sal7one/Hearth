package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.core.EngineSelection
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
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
 * Owner opt-in only: proves the staged MagpieTTS runtime synthesizes real, non-silent
 * 22.05 kHz PCM through DesktopEngines → DesktopVoiceCache → VoiceEngine.
 *
 * The model directory must contain the two pinned GGUF files and the extracted
 * `tokenizer/` directory from `scripts/voice/fetch-magpie-test-assets.py`.
 *
 * ```
 * HEARTH_MAGPIE_SMOKE=1 HEARTH_SMOKE_MAGPIE=<model dir> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopMagpieRealSmokeTest*"
 * ```
 */
class DesktopMagpieRealSmokeTest {
    private data class Measurement(val path: Path, val rate: Int, val durationSeconds: Double,
                                   val peak: Double, val rms: Double, val nonZero: Int, val samples: Int)

    @Test
    fun desktopEngineSynthesizesNonSilentArabicAndEnglish() = runBlocking<Unit> {
        assumeTrue("Set HEARTH_MAGPIE_SMOKE=1 and provide HEARTH_SMOKE_MAGPIE", System.getenv("HEARTH_MAGPIE_SMOKE") == "1")
        val model = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_MAGPIE")) { "HEARTH_SMOKE_MAGPIE is required" }).toRealPath()
        val receipt = System.getenv("HEARTH_MAGPIE_SMOKE_RECEIPT")?.let(Path::of)
        val directory = Files.createTempDirectory("hearth-magpie-smoke-")
        val cases = mutableListOf<String>()
        try {
            DesktopEngines(CredentialResolver { error("Local Magpie smoke must never resolve cloud credentials") }, directory).use { engines ->
                val descriptor = engines.capabilities().single { it.id == "magpie-tts-local" }
                assertTrue(descriptor.available, "Magpie is unavailable: ${descriptor.unavailableReason}")
                assertEquals(setOf("cpu"), descriptor.acceleration)
                for ((language, text) in listOf("en" to "Hello from Hearth.", "ar" to "مرحبا بكم في هيرث.")) {
                    val selection = EngineSelection(
                        "magpie-tts-local", "magpie-v2607", model.toString(),
                        options = mapOf("voice" to "John", "threads" to "2", "acceleration" to "cpu"),
                    )
                    val started = System.nanoTime()
                    val audio = engines.synthesize(text, selection, language, "John")
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    val wav = Path.of(audio.path)
                    assertTrue(Files.isRegularFile(wav) && Files.size(wav) > 44, "Magpie produced no WAV for $language")
                    val native = measure(wav)
                    assertEquals(22_050, native.rate, "MagpieTTS NanoCodec must keep its native 22.05 kHz rate")
                    assertTrue(native.durationSeconds > 0.5, "Magpie returned too little audio for $language: ${native.durationSeconds} s")
                    assertTrue(native.nonZero > native.samples * 4 / 5, "Magpie returned mostly silence for $language")
                    assertTrue(native.peak > 0.01, "Magpie returned silent audio for $language")
                    val rtf = (elapsedMs / 1000.0) / native.durationSeconds
                    println(
                        "MAGPIE_SMOKE language=$language ms=$elapsedMs duration=${native.durationSeconds} " +
                            "rate=${native.rate} peak=${native.peak} rms=${native.rms} nonzero=${native.nonZero}/${native.samples} " +
                            "rtf=$rtf wav=$wav"
                    )
                    cases += """{"language":"$language","elapsedMs":$elapsedMs,"durationSeconds":${native.durationSeconds},""" +
                        """"sampleRate":${native.rate},"peak":${native.peak},"rms":${native.rms},""" +
                        """"nonZeroSamples":${native.nonZero},"samples":${native.samples},"rtf":$rtf,""" +
                        """"wavBytes":${Files.size(wav)}}"""
                    if (receipt != null) {
                        val target = receipt.toAbsolutePath().parent.resolve(wav.fileName)
                        Files.createDirectories(target.parent)
                        Files.copy(wav, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            if (receipt != null) {
                Files.createDirectories(receipt.toAbsolutePath().parent)
                Files.writeString(receipt, """{"schemaVersion":1,"engineId":"magpie-tts-local","modelId":"magpie-v2607",""" +
                    """"nativeSampleRate":22050,"networkProvidersCalled":false,"audioPlayed":false,"cases":[${cases.joinToString(",")}]}""" + "\n")
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
}
