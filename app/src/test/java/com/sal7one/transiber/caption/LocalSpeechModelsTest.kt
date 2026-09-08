package com.sal7one.transiber.caption

import com.sal7one.common_jni.speech.SpeechProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSpeechModelsTest {
    private val asset = "GGUFtest fixture, not inference weights".toByteArray()
    private fun manifest() = JSONObject().put("schemaVersion", 1)
        .put("profile", "nemotron-3.5-asr-0.6b").put("roles", JSONObject().put("model", "model.gguf"))
        .put("files", JSONArray().put(JSONObject().put("path", "model.gguf").put("bytes", asset.size)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(asset).joinToString("") { "%02x".format(it) })))
        .toString().toByteArray()
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { z -> entries.forEach { (name, bytes) ->
            z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
        } }
    }.toByteArray()
    private fun validZip() = zip(mapOf("model.gguf" to asset, "hearth-speech.json" to manifest()))
    private fun inStore(block: (LocalSpeechModels, File) -> Unit) {
        val root = Files.createTempDirectory("speech-store").toFile()
        try { block(LocalSpeechModels(root), root) } finally { root.deleteRecursively() }
    }
    @Test fun importPublishesVerifiedImmutablePackagesAndSelectsOnlyMatchingEngine() = inStore { store, _ ->
        val first = store.importZip(validZip().inputStream())
        val second = store.importZip(validZip().inputStream())
        assertNotEquals(first.id, second.id)
        assertTrue(first.root.isDirectory)
        assertEquals(2, store.list().size)
        assertEquals(SpeechProfile.NEMOTRON_3_5_ASR_0_6B, first.profile)
        assertEquals(first, store.select(CaptionEngineChoice.NEMOTRON, first.id))
        assertThrows(IllegalStateException::class.java) { store.select(CaptionEngineChoice.QWEN, first.id) }
        assertThrows(IllegalStateException::class.java) { store.select(CaptionEngineChoice.NEMOTRON, "../outside") }
    }
    @Test fun corruptWeightsNeverPublish() = inStore { store, _ ->
        val bad = zip(mapOf("model.gguf" to asset.copyOf().also { it[4] = 0 }, "hearth-speech.json" to manifest()))
        assertThrows(Exception::class.java) { store.importZip(bad.inputStream()) }
        assertTrue(store.list().isEmpty())
    }
    @Test fun traversalNeverEscapesAndLeavesNoInstall() = inStore { store, root ->
        val bad = zip(mapOf("../escaped.gguf" to asset))
        assertThrows(Exception::class.java) { store.importZip(bad.inputStream()) }
        assertTrue(store.list().isEmpty())
        assertFalse(root.walkTopDown().any { it.name == "escaped.gguf" })
    }
    @Test fun bareWeightsAreNotACompleteModelPackage() = inStore { store, _ ->
        assertThrows(Exception::class.java) { store.importZip(zip(mapOf("model.gguf" to asset)).inputStream()) }
        assertTrue(store.list().isEmpty())
    }
    @Test fun cancellationLeavesNoSelectableInstall() = inStore { store, _ ->
        assertThrows(Exception::class.java) { store.importZip(validZip().inputStream()) { throw java.util.concurrent.CancellationException("cancelled") } }
        assertTrue(store.list().isEmpty())
    }
    @Test fun localRecognizersCannotAccidentallyUseWhisperTranslationTask() {
        for (engine in listOf(CaptionEngineChoice.QWEN, CaptionEngineChoice.NEMOTRON)) {
            for (target in TranslationTarget.entries) {
                val config = CaptionOverlayConfig(engine = engine, mode = CaptionMode.TRANSLATE, target = target, streamLanguage = "en")
                assertEquals(CaptionTranslationRoute.UNSUPPORTED, captionTranslationRoute(config, com.sal7one.transiber.byok.CloudConfigStore.SttMode.BATCH))
            }
        }
    }
}
