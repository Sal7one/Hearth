package com.sal7one.hearth.desktop

import com.sal7one.hearth.desktop.data.DesktopModelLibrary
import com.sal7one.hearth.desktop.runtime.MoonshineSpeechModel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The generated catalog and the engine must pin exactly the same Moonshine variants and files. */
class DesktopMoonshineCatalogParityTest {
    @Test fun catalogCardsMatchEnginePins() {
        val directory = Files.createTempDirectory("hearth-moonshine-catalog-")
        try {
            val cards = DesktopModelLibrary(directory).catalog.filter { it.engineId == MoonshineSpeechModel.ENGINE_ID }
            if (cards.isEmpty()) return // Cards are staged for macOS arm64 only.
            assertEquals(MoonshineSpeechModel.variants.size, cards.size, cards.map { it.id }.toString())
            for (variant in MoonshineSpeechModel.variants) {
                val card = cards.single { it.id == variant.id }
                assertEquals(variant.label, card.name)
                assertEquals(setOf(variant.language), card.languages)
                assertEquals(variant.license, card.license)
                assertEquals(setOf("macos-arm64"), card.platforms)
                assertEquals("STT", card.stage.name)
                assertEquals(variant.archiveName, card.artifacts.single().filename)
                val archive = requireNotNull(card.archive) { card.id }
                assertEquals("tar.bz2", archive.format)
                assertEquals(variant.archiveRoot, archive.root)
                assertEquals(variant.expandedBytes, card.installedBytes)
                assertEquals(variant.files.map { Triple(it.path, it.bytes, it.sha256) },
                    archive.files.map { Triple(it.path, it.bytes, it.sha256) })
            }
            assertTrue(cards.all { it.options.isEmpty() })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
