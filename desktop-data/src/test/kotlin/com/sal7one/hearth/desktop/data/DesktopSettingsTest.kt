package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class DesktopSettingsTest {
    @Test fun dataStorePersistsIndependentRoutesAndUiPreferences() = runBlocking {
        val directory = Files.createTempDirectory("hearth-settings-test-")
        val configured = PipelineConfig(
            speech = EngineSelection("whisper-local", "tiny", "/models/whisper.bin", options = mapOf("sha256" to "a".repeat(64))),
            translation = EngineSelection("openai-translation", "chosen-model", endpoint = "https://api.example.com/v1", credentialId = "translation-provider"),
            voice = EngineSelection("system-tts"), sourceLanguage = "ar", targetLanguage = "en", voiceId = "male",
        )
        try {
            DesktopSettingsStore(directory).let { store ->
                store.save(configured); store.saveUi(mapOf("inputMode" to "Text", "overlay" to "translation")); store.closeAndJoin()
            }
            DesktopSettingsStore(directory).let { store ->
                assertEquals(configured, store.load())
                assertFalse(Files.readAllBytes(directory.resolve("settings.preferences_pb")).isEmpty())
                store.closeAndJoin()
            }
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun portableExportRemovesPathsAndCredentialsAndImportIgnoresInjectedLocalPaths() = runBlocking {
        val directory = Files.createTempDirectory("hearth-setup-test-")
        val store = DesktopSettingsStore(directory.resolve("data"))
        val setup = directory.resolve("setup.json")
        try {
            store.save(PipelineConfig(translation = EngineSelection("llama-translation-local", "eurollm-17b-q4", "/private/models/voice.gguf", credentialId = "secret-ref", options = mapOf("promptMode" to "euro"))))
            store.exportSetup(setup)
            val exported = Files.readString(setup)
            assertFalse(exported.contains("/private/models")); assertFalse(exported.contains("secret-ref"))
            Files.writeString(setup, exported.replace("\"modelPath\": \"\"", "\"modelPath\": \"/attacker/file.gguf\""))
            store.importSetup(setup)
            assertEquals("", store.config.value.translation!!.modelPath)
            assertEquals("euro", store.config.value.translation!!.options["promptMode"])
        } finally { store.closeAndJoin(); directory.toFile().deleteRecursively() }
    }
    @Test fun setupCannotInjectSecretsOrRemotePlainHttpEndpoints() {
        assertFailsWith<IllegalArgumentException> { SetupCodec.encode(PipelineConfig(translation = EngineSelection("openai-translation", options = mapOf("apiKey" to "secret")))) }
        val json = SetupCodec.encode(PipelineConfig(translation = EngineSelection("openai-translation", endpoint = "https://example.com/v1")))
        assertFailsWith<IllegalArgumentException> { SetupCodec.decode(json.replace("https://example.com", "http://example.com")) }
        assertFailsWith<IllegalArgumentException> { SetupCodec.decode(json.replace("https://example.com", "https://secret@example.com")) }
        assertFailsWith<IllegalArgumentException> { SetupCodec.decode(json.replace("https://example.com/v1", "https://example.com/v1?api_key=SECRET")) }
        assertFailsWith<IllegalArgumentException> { SetupCodec.encode(PipelineConfig(translation = EngineSelection("openai-translation", endpoint = "https://example.com/v1?api_key=SECRET"))) }
        assertNotNull(SetupCodec.decode(json.replace("https://example.com", "http://127.0.0.1:8080")).translation)
    }
    @Test fun catalogHasOnlyPinnedArtifactsAndRejectsModifiedImport() = runBlocking {
        val directory = Files.createTempDirectory("hearth-model-test-")
        try {
            val library = DesktopModelLibrary(directory)
            val byStage = library.catalog.groupingBy { it.stage.name }.eachCount()
            assertEquals(mapOf("STT" to 27, "TRANSLATION" to 14, "TTS" to 1), byStage)
            assertEquals(1, library.catalog.filter { it.stage.name == "STT" }.map { it.family }.distinct().size)
            // Every shipped translator must use a prompt family the desktop engine implements,
            // and the option the engine reads must be the catalog's own family, not a fallback.
            val supportedFamilies = setOf("eurollm", "riva-translate", "milmmt-46", "hy-mt2", "index-translate", "translategemma")
            val translators = library.catalog.filter { it.stage.name == "TRANSLATION" }
            assertTrue(translators.all { it.family in supportedFamilies }, translators.map { it.family }.toString())
            assertTrue(translators.all { it.options["family"] == it.family }, translators.map { it.id to it.options }.toString())
            assertTrue(translators.all { it.artifacts.size == 1 && it.artifacts.single().filename.endsWith(".gguf") })
            assertTrue(library.catalog.flatMap { it.artifacts }.all { it.url.startsWith("https://") && it.sha256.length == 64 && it.bytes > 0 })
            val fake = directory.resolve("ggml-tiny-q5_1.bin"); Files.write(fake, byteArrayOf(1, 2, 3))
            assertFailsWith<IllegalStateException> { library.verify("whisper-tiny-q5_1", fake) }
            library.refresh(); assertTrue(library.installations.value.values.none { it.ready })
        } finally { directory.toFile().deleteRecursively() }
    }
}
