package com.sal7one.hearth.desktop

import com.sal7one.hearth.desktop.data.DesktopModelLibrary
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CTranslate2 cards are multi-component; the llama.cpp path stays single-GGUF. */
class DesktopCt2CatalogTest {
    @Test fun ct2CardsCarryEveryPinnedComponentAndLlamaStaysSingleGguf() {
        val directory = Files.createTempDirectory("hearth-ct2-catalog-")
        try {
            val catalog = DesktopModelLibrary(directory).catalog
            val ct2 = catalog.filter { it.engineId == "marian-local" }
            // The desktop CT2 cards are macOS-arm64 only; other hosts correctly filter them out.
            if (ct2.isEmpty()) return
            assertEquals(7, ct2.size, ct2.map { it.id }.toString())
            assertTrue(ct2.all { it.artifacts.size > 1 || it.archive?.files?.size == 7 }, ct2.map { it.id to it.artifacts.size }.toString())
            assertTrue(ct2.all { card -> card.artifacts.all { it.url.startsWith("https://") && it.sha256.length == 64 && it.bytes > 0 } })
            val quickmt = ct2.single { it.id == "ct2-quickmt-en-ar" }
            assertEquals(6, quickmt.artifacts.size)
            assertEquals("0", quickmt.options["profile"])
            assertEquals("en", quickmt.options["sources"])
            assertEquals("ar", quickmt.options["targets"])
            assertTrue(quickmt.artifacts.map { it.filename }.containsAll(listOf("model.bin", "config.json", "src.spm.model", "tgt.spm.model")))
            val jisr = ct2.filter { it.id.startsWith("ct2-jisr-") }
            assertEquals(4, jisr.size)
            jisr.forEach { card ->
                assertEquals(1, card.artifacts.size)
                assertEquals("zip", card.archive?.format)
                assertEquals("", card.archive?.root)
                assertEquals(7, card.archive?.files?.size)
                assertTrue(card.archive!!.files.any { it.path == "hearth-translation.json" })
                assertTrue(card.artifacts.single().url.contains("/v0.27.3/"))
            }
            val llama = catalog.filter { it.engineId == "llama-translation-local" }
            assertTrue(llama.isNotEmpty())
            assertTrue(llama.all { it.artifacts.size == 1 && it.artifacts.single().filename.endsWith(".gguf") }, "The llama path must remain single-GGUF")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
