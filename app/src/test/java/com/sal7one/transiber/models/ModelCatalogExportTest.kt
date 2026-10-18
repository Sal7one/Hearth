package com.sal7one.transiber.models

import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.BuildConfig
import com.sal7one.transiber.caption.CaptionEngineChoice
import com.sal7one.transiber.ocr.OcrCatalog
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.voice.VoiceCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI

/** Exports the actual installer catalog; the separate network audit never parses Kotlin literals. */
class ModelCatalogExportTest {
    @Test fun exportPinnedModelCatalogAndValidateProvenance() {
        val artifacts = mutableListOf<JSONObject>()
        val sources = linkedSetOf<String>()
        fun source(vararg urls: String) { sources.addAll(urls) }
        fun artifact(id: String, category: String, url: String, bytes: Long, sha256: String, license: String) {
            assertTrue("Missing model license for $id", license.isNotBlank())
            assertEquals("https", URI(url).scheme)
            assertTrue("Invalid artifact size for $id", bytes > 0)
            assertTrue("Invalid SHA-256 for $id", sha256.matches(Regex("[a-f0-9]{64}")))
            artifacts += JSONObject().put("id", id).put("category", category).put("url", url)
                .put("bytes", bytes).put("sha256", sha256).put("license", license)
        }
        SpeechArtifactCatalog.all.forEach {
            artifact(it.id, "speech", it.url, it.bytes, it.sha256, it.license)
            source(it.publisherUrl, it.filesUrl, it.licenseUrl)
        }
        CaptionEngineChoice.entries.flatMap(ModelSources::speech).forEach { source(it.publisher, it.files) }
        source(ModelSources.marian.publisher, ModelSources.marian.files)
        TranslationCatalog.models.forEach {
            artifact(it.id, "translation", it.url, it.bytes, it.sha256, it.license)
            source(it.modelCard, it.filesUrl, it.originalModelCard, it.licenseUrl)
        }
        MarianPackage.pairs.forEach { pair ->
            pair.parts.forEach { artifact(it.id, "translation", it.url, it.bytes, it.sha256, pair.license) }
            source(pair.modelCard, pair.filesUrl, pair.originalModelCard, pair.licenseUrl)
        }
        OcrCatalog.assets.forEach {
            artifact(it.id, "ocr", it.url, it.bytes, it.sha256, it.license)
            source(it.modelCard, it.filesUrl, it.originalModelCard, it.licenseUrl)
        }
        VoiceCatalog.assets.forEach { artifact(it.id, "voice", it.url, it.bytes, it.sha256, VoiceCatalog.license) }
        source(VoiceCatalog.modelCard, VoiceCatalog.projectUrl, VoiceCatalog.filesUrl, VoiceCatalog.licenseUrl)
        assertEquals("Duplicate artifact IDs", artifacts.size, artifacts.map { it.getString("id") }.toSet().size)
        sources.forEach { assertEquals("Invalid source $it", "https", URI(it).scheme) }
        val output = File("build/reports/model-catalog/${BuildConfig.FLAVOR}-${BuildConfig.BUILD_TYPE}.json")
        checkNotNull(output.parentFile).mkdirs()
        output.writeText(JSONObject().put("schemaVersion", 1).put("artifacts", JSONArray(artifacts))
            .put("sources", JSONArray(sources.toList().sorted())).toString(2) + "\n")
        println("Model catalog audit input: ${output.absolutePath} (${artifacts.size} artifacts, ${sources.size} sources)")
    }
}
