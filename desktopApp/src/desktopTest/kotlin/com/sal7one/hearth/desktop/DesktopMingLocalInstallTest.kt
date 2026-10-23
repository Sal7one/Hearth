package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.desktop.data.DesktopModelLibrary
import com.sal7one.hearth.desktop.runtime.DesktopEngines
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owner opt-in only: downloads the actual 850 MB Ming catalog card, verifies every
 * file, installs it flat, materializes parts/, and synthesizes through DesktopEngines.
 *
 * `HEARTH_MING_INSTALL_SMOKE=1 ./gradlew -PhearthDesktopOnly=true :desktopApp:desktopTest --tests "*DesktopMingLocalInstallTest*"`
 */
class DesktopMingLocalInstallTest {
    @Test
    fun installsThePinnedMingCardAndSynthesizes() = runBlocking<Unit> {
        assumeTrue("Set HEARTH_MING_INSTALL_SMOKE=1", System.getenv("HEARTH_MING_INSTALL_SMOKE") == "1")
        val directory = Files.createTempDirectory("hearth-ming-install-")
        try {
            val library = DesktopModelLibrary(directory)
            val selection = library.download("ming-3.0-tts-0.5b-web")
            assertEquals("ming-local", selection.engineId)
            val root = Path.of(selection.modelPath)
            assertTrue(Files.isRegularFile(root.resolve("manifest.json")))
            assertTrue(Files.isRegularFile(root.resolve("tokenizer.json")))
            assertTrue((0..14).all { Files.isRegularFile(root.resolve("part-%03d.bin".format(it))) })
            val started = System.nanoTime()
            val audio = DesktopEngines(CredentialResolver { error("Local Ming install smoke must never resolve cloud credentials") }, directory).use { engines ->
                assertTrue(engines.capabilities().single { it.id == "ming-local" }.available)
                engines.synthesize("Hello from Hearth.", selection, "en", "US_Emma")
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val bytes = Files.readAllBytes(Path.of(audio.path))
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 12; var channels = -1; var rate = -1; var data = -1; var dataSize = -1
            while (offset + 8 <= bytes.size) {
                val id = String(bytes, offset, 4); val size = buffer.getInt(offset + 4); val body = offset + 8
                when (id) { "fmt " -> { channels = buffer.getShort(body + 2).toInt(); rate = buffer.getInt(body + 4) }; "data" -> { data = body; dataSize = size } }
                offset = body + size + (size and 1)
            }
            assertEquals(1, channels); assertEquals(44_100, rate); assertTrue(dataSize > 22_050)
            val samples = IntArray(dataSize / 2) { index -> buffer.getShort(data + index * 2).toInt() }
            val nonZero = samples.count { it != 0 }
            println("MING_INSTALL_SMOKE ms=$elapsed frames=${samples.size} nonZero=$nonZero/${samples.size} path=${audio.path}")
            assertTrue(nonZero > samples.size / 2, "Ming install produced mostly silence")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
