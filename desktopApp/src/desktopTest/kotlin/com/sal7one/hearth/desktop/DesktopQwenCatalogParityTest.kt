package com.sal7one.hearth.desktop

import com.sal7one.hearth.desktop.data.DesktopModelLibrary
import com.sal7one.hearth.desktop.runtime.QwenSpeechModel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The generated catalog and the engine must pin exactly the same six model files. */
class DesktopQwenCatalogParityTest {
    @Test fun catalogCardMatchesEnginePins() {
        val directory = Files.createTempDirectory("hearth-qwen-catalog-")
        try {
            val card = DesktopModelLibrary(directory).catalog.singleOrNull { it.id == "qwen3-asr-0.6b" } ?: return
            assertEquals(QwenSpeechModel.ENGINE_ID, card.engineId)
            assertEquals(QwenSpeechModel.languages, card.languages)
            assertEquals(878_702_423L, card.bytes)
            assertEquals(987_015_347L, card.installedBytes)
            val archive = requireNotNull(card.archive)
            assertEquals("tar.bz2", archive.format)
            assertEquals("sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25", archive.root)
            assertEquals(
                QwenSpeechModel.files.map { Triple(it.path, it.bytes, it.sha256) },
                archive.files.map { Triple(it.path, it.bytes, it.sha256) },
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
