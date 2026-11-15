package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.core.HearthPipeline
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.vision.MangaArtifacts
import com.sal7one.hearth.desktop.data.DesktopModelLibrary
import com.sal7one.hearth.desktop.runtime.DesktopEngines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopMangaLineRealTest {
    @Test fun pinnedModelsReadAndTranslateThroughTheDesktopPageController() = runBlocking {
        val models = System.getenv("HEARTH_MANGA_LINE_MODELS")
        val page = System.getenv("HEARTH_MANGA_LINE_PAGE")
        assumeTrue("Provide pinned manga model files and a local page", models != null && page != null)
        val root = Files.createTempDirectory("hearth-manga-line-test")
        try {
            val library = DesktopModelLibrary(root)
            val selection = library.verify(MangaArtifacts.lineProfileId, Path.of(requireNotNull(models)))
            val quickMt = System.getenv("HEARTH_MANGA_QUICKMT_MODELS")?.let {
                library.verify("ct2-quickmt-ja-en-v2", Path.of(it))
            }
            val config = MutableStateFlow(PipelineConfig(sourceLanguage = "ja", targetLanguage = "en", translation = quickMt))
            DesktopEngines(CredentialResolver { null }, root).use { engines ->
                val capabilities = engines.capabilities()
                if (quickMt != null) assertTrue(capabilities.any { it.id == quickMt.engineId && it.available })
                val controller = DesktopReadingController(HearthPipeline(engines, this), config, this)
                try {
                    controller.openImage(Path.of(requireNotNull(page)))?.join()
                    val folder = Path.of(selection.modelPath)
                    suspend fun read(translate: Boolean): Pair<DesktopReadingState, Long> {
                        val start = System.nanoTime()
                        controller.read(folder, translate, MangaArtifacts.lineProfileId)?.join()
                        val elapsed = (System.nanoTime() - start) / 1_000_000
                        val result = controller.state.value
                        assertNull(result.error)
                        assertTrue(result.text.size >= 2, "Provide a multi-line manga page")
                        assertTrue(result.text.all { item -> result.regions.any { it.bounds == item.region.bounds } })
                        return result to elapsed
                    }
                    val (first, cold) = read(false)
                    repeat(2) { read(false) }
                    val times = ArrayList<Long>()
                    val totals = ArrayList<Long>()
                    repeat(20) {
                        val (result, elapsed) = read(false)
                        assertEquals(first.text, result.text)
                        assertEquals(first.regions, result.regions)
                        times += assertNotNull(result.ocrElapsedMs)
                        totals += elapsed
                    }
                    times.sort(); totals.sort()
                    val p95 = ceil(times.size * .95).toInt() - 1
                    println("MANGA_LINE_APP lines=${first.text.size} cold_ms=$cold ocr_p50_ms=${times[9]} ocr_p95_ms=${times[p95]} ocr_max_ms=${times.last()} controller_p95_ms=${totals[p95]}")
                    if (quickMt != null) {
                        val (translated, full) = read(true)
                        assertEquals(translated.text.size, translated.translations.size)
                        assertTrue(translated.translations.values.all(String::isNotBlank))
                        val latinLines = translated.translations.values.count { it.any { char -> char in 'a'..'z' || char in 'A'..'Z' } }
                        assertTrue(latinLines > translated.translations.size / 2)
                        val (cached, repeated) = read(true)
                        assertEquals(translated.translations, cached.translations)
                        println("MANGA_LINE_TRANSLATION lines=${translated.translations.size} latin_lines=$latinLines full_page_ms=$full repeat_ms=$repeated")
                    }
                } finally { controller.close() }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
