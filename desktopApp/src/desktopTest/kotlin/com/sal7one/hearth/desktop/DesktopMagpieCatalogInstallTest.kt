package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.desktop.data.DesktopModelLibrary
import com.sal7one.hearth.desktop.runtime.DesktopEngines
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
 * Owner opt-in only: runs the actual `magpie-tts` catalog install (two pinned GGUF
 * downloads plus authenticated tokenizer byte ranges), verifies every component,
 * then synthesizes through DesktopEngines. Set HEARTH_MAGPIE_MODEL_CACHE to a directory
 * holding the two already-verified GGUF files to skip the 647 MB re-download.
 *
 * `HEARTH_MAGPIE_INSTALL_SMOKE=1 ./gradlew -PhearthDesktopOnly=true :desktopApp:desktopTest --tests "*DesktopMagpieCatalogInstallTest*"`
 */
class DesktopMagpieCatalogInstallTest {
    @Test
    fun installsThePinnedMagpieCardAndSynthesizes() = runBlocking<Unit> {
        assumeTrue("Set HEARTH_MAGPIE_INSTALL_SMOKE=1", System.getenv("HEARTH_MAGPIE_INSTALL_SMOKE") == "1")
        val cache = System.getenv("HEARTH_MAGPIE_MODEL_CACHE")?.let(Path::of)
        val directory = Files.createTempDirectory("hearth-magpie-install-")
        try {
            val library = DesktopModelLibrary(directory)
            val card = library.find("magpie-tts")
            assertEquals("magpie-tts-local", card.engineId)
            assertEquals(2, card.artifacts.size)
            assertEquals(31_076_864L, requireNotNull(card.rangePackage).bytes)
            assertEquals(568_663_392L + 78_823_104L + 31_076_864L, card.bytes)
            if (cache != null) {
                val target = directory.resolve("models/magpie-tts")
                Files.createDirectories(target)
                for (artifact in card.artifacts) {
                    val source = cache.resolve(artifact.filename)
                    check(Files.isRegularFile(source)) { "Cached Magpie weight is missing: $source" }
                    runCatching { Files.createLink(target.resolve(artifact.filename), source) }
                        .getOrElse { Files.copy(source, target.resolve(artifact.filename)) }
                }
            }
            val selection = library.download("magpie-tts")
            assertEquals("magpie-tts-local", selection.engineId)
            val root = Path.of(selection.modelPath)
            assertTrue(Files.isRegularFile(root.resolve("magpie_tts_multilingual_357m.v2607.f16.gguf")))
            assertTrue(Files.isRegularFile(root.resolve("nemo_nano_codec_22khz_1.89kbps_21.5fps.decoder.f16.gguf")))
            assertTrue(Files.isRegularFile(root.resolve("tokenizer/41913ebaa70342058574da74293b7630_magpie_tts_multilingual_357m.nemo.speakers.json")))
            assertEquals(10, Files.list(root.resolve("tokenizer")).use { it.count() })
            assertEquals("magpie-tts", library.installedSelection("magpie-tts")?.modelId)
            val started = System.nanoTime()
            val audio = DesktopEngines(CredentialResolver { error("Local Magpie install smoke must never resolve cloud credentials") }, directory).use { engines ->
                assertTrue(engines.capabilities().single { it.id == "magpie-tts-local" }.available)
                engines.synthesize("Hello from Hearth.", selection, "en", "Aria")
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val wav = Path.of(audio.path)
            assertTrue(Files.isRegularFile(wav) && Files.size(wav) > 44, "Catalog install produced no WAV")
            val format = AudioSystem.getAudioInputStream(wav.toFile()).use { it.format }
            check(format.encoding == AudioFormat.Encoding.PCM_SIGNED && format.sampleSizeInBits == 16 && format.channels == 1) { "Unexpected WAV format: $format" }
            assertEquals(22_050, format.sampleRate.roundToInt())
            val samples = AudioSystem.getAudioInputStream(wav.toFile()).use { stream ->
                val bytes = stream.readBytes()
                FloatArray(bytes.size / 2) { index ->
                    val low = bytes[index * 2].toInt() and 0xFF
                    val high = bytes[index * 2 + 1].toInt()
                    ((high shl 8) or low) / 32_768f
                }
            }
            val duration = samples.size / 22_050.0
            val peak = samples.maxOfOrNull { abs(it) } ?: 0f
            val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)
            val nonZero = samples.count { it != 0f }
            println("MAGPIE_INSTALL_SMOKE ms=$elapsed duration=$duration rate=22050 peak=$peak rms=$rms nonZero=$nonZero/${samples.size} wav=$wav")
            assertTrue(duration > 0.5, "Magpie catalog install returned too little audio: $duration s")
            assertTrue(nonZero > samples.size * 4 / 5, "Magpie catalog install returned mostly silence")
            assertTrue(peak > 0.01f, "Magpie catalog install returned silent audio")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
