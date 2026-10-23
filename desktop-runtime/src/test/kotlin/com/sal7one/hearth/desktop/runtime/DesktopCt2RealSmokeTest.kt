package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.translation.Ct2Native
import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.core.EngineSelection
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owner opt-in only: proves the packaged desktop CTranslate2 runtime translates a real
 * English sentence into Arabic with the pinned QuickMT en→ar model. No downloads, no network.
 *
 * ```
 * HEARTH_CT2_SMOKE=1 HEARTH_SMOKE_CT2_DIR=<quickmt-en-ar dir> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopCt2RealSmokeTest*"
 * ```
 */
class DesktopCt2RealSmokeTest {
    private val pins = mapOf(
        "sha256.model.bin" to "2b053bf9e989a3685f902eafac1d8128f8fcf833b90fdd142254f08bd73ca5af",
        "sha256.config.json" to "66edc5d58ceb812a41b4b784497001f815c35400884f40984b19de29e1bc3d69",
        "sha256.source_vocabulary.json" to "59b2accfaba2b0a8171deed0cce56afdf41d5d724da68c63057b2035c512cbbe",
        "sha256.target_vocabulary.json" to "5437c4de92543211144f9319f234bf2bcacda2ea454c3b26453da97d4d76e833",
        "sha256.src.spm.model" to "acf054421b5ed52bd6aee14564fb8595b9a6b492df669e971d43fba9edb33c16",
        "sha256.tgt.spm.model" to "c7b8bf117088a901628696f60ddd15e047057ccbdcf9b0996ca19e2779a234c7",
    )
    private val sentence = "The museum opens at nine in the morning."

    @Test
    fun realCt2TranslatesEnglishToArabic() {
        val model = fixture()
        assertEquals(2, Ct2Native.protocolVersion())
        val started = System.nanoTime()
        val handle = Ct2Native.create(model.toString().toByteArray(), 0)
        try {
            val loadMs = (System.nanoTime() - started) / 1_000_000
            val inferStarted = System.nanoTime()
            val translated = Ct2Native.translate(handle, sentence.toByteArray(), "ar".toByteArray()).toString(Charsets.UTF_8).trim()
            val inferMs = (System.nanoTime() - inferStarted) / 1_000_000
            println("CT2_SMOKE loadMs=$loadMs inferMs=$inferMs text=$translated")
            assertTrue(translated.isNotBlank(), "CTranslate2 returned no text")
            assertContains(translated, "المتحف")
        } finally {
            Ct2Native.destroy(handle)
        }
    }

    @Test
    fun desktopMarianEngineTranslatesThroughThePipeline() = runBlocking<Unit> {
        val model = fixture()
        val directory = Files.createTempDirectory("hearth-ct2-smoke-")
        val directoryFiles = model.toFile().listFiles().orEmpty().map { it.name }.toSet()
        assertTrue(directoryFiles.containsAll(pins.keys.map { it.removePrefix("sha256.") }))
        val selection = EngineSelection(
            "marian-local", "ct2-quickmt-en-ar", model.toString(),
            options = mapOf("profile" to "0", "sources" to "en", "targets" to "ar") + pins,
        )
        val started = System.nanoTime()
        try {
            DesktopEngines(CredentialResolver { error("Local CT2 smoke must never resolve cloud credentials") }, directory).use { engines ->
                assertTrue(engines.capabilities().single { it.id == "marian-local" }.available)
                val translated = engines.translate(sentence, selection, "en", "ar")
                val elapsed = (System.nanoTime() - started) / 1_000_000
                println("CT2_ENGINE_SMOKE ms=$elapsed text=$translated")
                assertTrue(translated.isNotBlank(), "The desktop engine path returned no text")
                assertContains(translated, "المتحف")
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun fixture(): Path {
        assumeTrue("Set HEARTH_CT2_SMOKE=1 and provide HEARTH_SMOKE_CT2_DIR", System.getenv("HEARTH_CT2_SMOKE") == "1")
        return Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_CT2_DIR")) { "HEARTH_SMOKE_CT2_DIR is required" }).toRealPath()
    }
}
