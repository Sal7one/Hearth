package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.core.EngineSelection
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

/**
 * Owner opt-in only: the experimental local Ming JNI engine through DesktopEngines.
 * Needs the 850 MB fusor-ai bundle.
 *
 * ```
 * HEARTH_MING_LOCAL_SMOKE=1 HEARTH_MING_LOCAL_BUNDLE=<bundle dir> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopMingLocalRealSmokeTest*"
 * ```
 */
class DesktopMingLocalRealSmokeTest {
    @Test
    fun desktopEngineSynthesizesMingLocally() = runBlocking<Unit> {
        assumeTrue("Set HEARTH_MING_LOCAL_SMOKE=1 and provide HEARTH_MING_LOCAL_BUNDLE", System.getenv("HEARTH_MING_LOCAL_SMOKE") == "1")
        val bundle = Path.of(requireNotNull(System.getenv("HEARTH_MING_LOCAL_BUNDLE"))).toRealPath()
        val directory = Files.createTempDirectory("hearth-ming-local-")
        val selection = EngineSelection(
            "ming-local", "ming-3.0-tts-0.5b-web", bundle.toString(),
            options = catalogPins() + mapOf("voice" to "US_Emma", "threads" to "4"),
        )
        try {
            val started = System.nanoTime()
            val audio = DesktopEngines(CredentialResolver { error("Local Ming smoke must never resolve cloud credentials") }, directory).use { engines ->
                assertTrue(engines.capabilities().single { it.id == "ming-local" }.available)
                engines.synthesize("Hello from Hearth.", selection, "en", "US_Emma")
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val bytes = Files.readAllBytes(Path.of(audio.path))
            assertTrue(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE", "Ming returned a non-WAV file")
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 12; var channels = -1; var rate = -1; var data = -1; var dataSize = -1
            while (offset + 8 <= bytes.size) {
                val id = String(bytes, offset, 4); val size = buffer.getInt(offset + 4); val body = offset + 8
                when (id) { "fmt " -> { channels = buffer.getShort(body + 2).toInt(); rate = buffer.getInt(body + 4) }; "data" -> { data = body; dataSize = size } }
                offset = body + size + (size and 1)
            }
            assertEquals(1, channels); assertEquals(44_100, rate); assertTrue(dataSize > 0)
            val samples = IntArray(dataSize / 2) { index -> buffer.getShort(data + index * 2).toInt() }
            val nonZero = samples.count { it != 0 }
            val peak = samples.maxOfOrNull { kotlin.math.abs(it) } ?: 0
            println("MING_LOCAL_SMOKE ms=$elapsed frames=${samples.size} peak=$peak nonZero=$nonZero/${samples.size} path=${audio.path}")
            assertTrue(samples.size > 22_050, "Ming returned less than 0.5 s of audio")
            assertTrue(nonZero > samples.size / 2, "Ming returned mostly silence")
            assertTrue(peak > 100, "Ming returned silent audio")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** Use reviewed catalog hashes, never a digest computed from the files under test. */
    private fun catalogPins(): Map<String, String> {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("desktop-data/src/main/resources/desktop-models.json")) }
        val catalog = Json.parseToJsonElement(Files.readString(repository.resolve("desktop-data/src/main/resources/desktop-models.json"))).jsonObject
        val model = catalog.getValue("models").jsonArray.single { it.jsonObject["id"]?.jsonPrimitive?.content == "ming-3.0-tts-0.5b-web" }.jsonObject
        return model.getValue("artifacts").jsonArray.associate { entry ->
            val artifact = entry.jsonObject
            "sha256.${artifact.getValue("filename").jsonPrimitive.content}" to artifact.getValue("sha256").jsonPrimitive.content
        }
    }
}
