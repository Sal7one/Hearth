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
            val macosArm = desktopPlatform() == "macos-arm64"
            val qwen = if (macosArm) 1 else 0
            val transcribepsy = if (macosArm) 1 else 0
            val nemotron = if (macosArm) 1 else 0
            val moonshine = if (macosArm) 1 else 0
            val ct2 = if (macosArm) 3 else 0
            val moss = if (macosArm) 1 else 0
            val byStage = library.catalog.groupingBy { it.stage.name }.eachCount()
            assertEquals(mapOf("STT" to 27 + qwen + transcribepsy + nemotron + moonshine, "TRANSLATION" to 14 + ct2, "TTS" to 1 + moss), byStage)
            assertEquals(if (qwen + transcribepsy + nemotron + moonshine > 0) 5 else 1, library.catalog.filter { it.stage.name == "STT" }.map { it.family }.distinct().size)
            if (qwen == 1) {
                val card = library.catalog.single { it.id == "qwen3-asr-0.6b" }
                assertEquals("qwen3-asr-local", card.engineId)
                assertEquals(setOf("macos-arm64"), card.platforms)
                assertEquals(878_702_423L, card.bytes)
                assertEquals(987_015_347L, card.installedBytes)
                assertEquals(6, card.archive?.files?.size)
                assertTrue(card.archive!!.files.all { it.sha256.length == 64 && it.bytes > 0 })
                assertEquals("393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96", card.artifacts.single().sha256)
            }
            if (transcribepsy == 1) {
                val card = library.catalog.single { it.id == "transcribepsy-250m-eu-sub-q8" }
                assertEquals("transcribepsy-local", card.engineId)
                assertEquals(304_879_584L, card.bytes)
                assertEquals("a93ef324d38e410b5e49389db95d06364eddbb890b74a69f311e482d2fda6f4b", card.artifacts.single().sha256)
                assertEquals(null, card.archive)
            }
            if (nemotron == 1) {
                val card = library.catalog.single { it.id == "nemotron-3.5-asr-0.6b" }
                assertEquals("nemotron-asr-local", card.engineId)
                assertEquals(741_548_352L, card.bytes)
                assertEquals("a5c435f294eea8f88ce68dd27b8c3bfea7f777cb2fbba04fcd30eaa555f429ae", card.artifacts.single().sha256)
                assertEquals("OpenMDW-1.1", card.license)
                assertEquals(null, card.archive)
            }
            if (moonshine == 1) {
                val card = library.catalog.single { it.id == "moonshine-tiny-en-v2" }
                assertEquals("moonshine-local", card.engineId)
                assertEquals(4, card.archive?.files?.size)
                assertEquals("9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d", card.artifacts.single().sha256)
            }
            if (moss == 1) {
                val card = library.catalog.single { it.id == "moss-tts-nano" }
                assertEquals("moss-tts-local", card.engineId)
                assertEquals(14, card.artifacts.size)
                assertEquals(717_868_602L, card.bytes)
                assertEquals("Ava", card.options["voice"])
            }
            // Every shipped llama translator must use a prompt family the desktop engine implements,
            // and the option the engine reads must be the catalog's own family, not a fallback.
            val supportedFamilies = setOf("eurollm", "riva-translate", "milmmt-46", "hy-mt2", "index-translate", "translategemma")
            val translators = library.catalog.filter { it.engineId == "llama-translation-local" }
            assertTrue(translators.all { it.family in supportedFamilies }, translators.map { it.family }.toString())
            assertTrue(translators.all { it.options["family"] == it.family }, translators.map { it.id to it.options }.toString())
            assertTrue(translators.all { it.artifacts.size == 1 && it.artifacts.single().filename.endsWith(".gguf") })
            if (ct2 > 0) {
                val marian = library.catalog.filter { it.engineId == "marian-local" }
                assertEquals(3, marian.size, marian.map { it.id }.toString())
                assertTrue(marian.all { it.artifacts.size > 1 }, marian.map { it.id to it.artifacts.size }.toString())
                val quickmt = marian.single { it.id == "ct2-quickmt-en-ar" }
                assertEquals("0", quickmt.options["profile"])
                assertEquals("en", quickmt.options["sources"])
                assertEquals("ar", quickmt.options["targets"])
                assertEquals(6, quickmt.artifacts.size)
            }
            assertTrue(library.catalog.flatMap { it.artifacts }.all { it.url.startsWith("https://") && it.sha256.length == 64 && it.bytes > 0 })
            val fake = directory.resolve("ggml-tiny-q5_1.bin"); Files.write(fake, byteArrayOf(1, 2, 3))
            assertFailsWith<IllegalStateException> { library.verify("whisper-tiny-q5_1", fake) }
            library.refresh(); assertTrue(library.installations.value.values.none { it.ready })
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun setupRoundTripsMultiComponentPins() {
        val options = mapOf(
            "profile" to "0", "sources" to "en", "targets" to "ar",
            "sha256.model.bin" to "a".repeat(64),
        )
        val config = PipelineConfig(translation = EngineSelection("marian-local", "ct2-quickmt-en-ar", "/private/model", options = options))
        val decoded = SetupCodec.decode(SetupCodec.encode(config))
        assertEquals(options, decoded.translation!!.options)
        assertEquals("/private/model", decoded.translation!!.modelPath)
    }
}
