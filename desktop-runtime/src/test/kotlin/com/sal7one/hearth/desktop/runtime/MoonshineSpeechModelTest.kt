package com.sal7one.hearth.desktop.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoonshineSpeechModelTest {
    @Test fun pinsDescribeTheTenDownloadedPackages() {
        assertEquals(10, MoonshineSpeechModel.variants.size)
        assertEquals(setOf("en", "ar", "es", "ja", "ko", "uk", "vi", "zh"), MoonshineSpeechModel.languages)
        assertEquals(listOf("moonshine-tiny-en-v2", "moonshine-base-en-v2", "moonshine-base-ar-v2", "moonshine-base-es-v2",
            "moonshine-tiny-ja-v2", "moonshine-tiny-ko-v2", "moonshine-base-ja-v2", "moonshine-base-uk-v2",
            "moonshine-base-vi-v2", "moonshine-base-zh-v2"), MoonshineSpeechModel.variants.map { it.id })
        for (variant in MoonshineSpeechModel.variants) {
            assertEquals(4, variant.files.size, variant.id)
            assertEquals(listOf("tokens.txt", "encoder_model.ort", "decoder_model_merged.ort", "LICENSE"),
                variant.files.map { it.path }, variant.id)
            assertEquals(variant.expandedBytes, variant.files.sumOf { it.bytes }, variant.id)
            assertEquals(variant.archiveRoot, variant.archiveName.removeSuffix(".tar.bz2"), variant.id)
            assertTrue(variant.expandedBytes > 40_000_000L, variant.id)
            for (file in variant.files) {
                assertEquals(64, file.sha256.length, "${variant.id}/${file.path}")
                assertEquals(file.sha256.lowercase(), file.sha256, "${variant.id}/${file.path}")
                assertTrue(file.bytes > 0, "${variant.id}/${file.path}")
            }
        }
        // Pin spot-checks: tiny.en must not drift from the card that already shipped, and
        // the Apache/MIT English models must stay separate from the community-licensed ones.
        val tiny = MoonshineSpeechModel.variant("moonshine-tiny-en-v2")
        assertEquals(44_256_550L, tiny.expandedBytes)
        assertEquals("cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0",
            tiny.files.single { it.path == "decoder_model_merged.ort" }.sha256)
        assertEquals("MIT", tiny.license)
        assertEquals("Moonshine Community License", MoonshineSpeechModel.variant("moonshine-base-zh-v2").license)
        assertEquals("en", MoonshineSpeechModel.variant("moonshine-base-en-v2").language)
        assertEquals("zh", MoonshineSpeechModel.variant("moonshine-base-zh-v2").language)
    }

    @Test fun verificationRequiresTheExactPinnedComponents() {
        val content = "fixture-moonshine".toByteArray()
        val variant = MoonshineSpeechModel.Variant(
            "fixture-model", "Fixture Moonshine", "en", "MIT", "fixture-root",
            listOf(MoonshineSpeechModel.PinnedFile("tokens.txt", content.size.toLong(), sha256(content))),
        )
        val root = Files.createTempDirectory("hearth-moonshine-pins-")
        try {
            assertTrue(assertFailsWith<IllegalArgumentException> { MoonshineSpeechModel.verify(root, variant) }
                .message.orEmpty().contains("Missing or modified"))
            Files.write(root.resolve("tokens.txt"), content)
            MoonshineSpeechModel.verify(root, variant)
            Files.write(root.resolve("tokens.txt"), "wrong".toByteArray())
            assertTrue(assertFailsWith<IllegalArgumentException> { MoonshineSpeechModel.verify(root, variant) }
                .message.orEmpty().startsWith("Missing or modified Moonshine model file"))
            Files.write(root.resolve("tokens.txt"), ByteArray(content.size))
            assertTrue(assertFailsWith<IllegalArgumentException> { MoonshineSpeechModel.verify(root, variant) }
                .message.orEmpty().contains("checksum mismatch"))
            Files.delete(root.resolve("tokens.txt"))
            assertFailsWith<IllegalArgumentException> { MoonshineSpeechModel.verify(root, variant) }
            // A component that resolves outside the selected directory is never followed.
            val escaping = variant.copy(files = listOf(MoonshineSpeechModel.PinnedFile("../tokens.txt", content.size.toLong(), sha256(content))))
            assertTrue(assertFailsWith<IllegalArgumentException> { MoonshineSpeechModel.verify(root, escaping) }
                .message.orEmpty().contains("escapes its directory"))
            // A symbolic link is never accepted, even when its target would hash correctly.
            val outside = Files.createTempFile("hearth-moonshine-outside-", ".txt")
            try {
                Files.write(outside, content)
                if (runCatching { Files.createSymbolicLink(root.resolve("tokens.txt"), outside) }.isSuccess) {
                    assertTrue(assertFailsWith<IllegalArgumentException> { MoonshineSpeechModel.verify(root, variant) }
                        .message.orEmpty().contains("symbolic link"))
                }
            } finally {
                Files.deleteIfExists(outside)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun autoResolvesToTheSelectedPackageAndMismatchesAreVisible() {
        assertEquals("en", MoonshineSpeechModel.language("moonshine-tiny-en-v2", "auto"))
        assertEquals("en", MoonshineSpeechModel.language("moonshine-base-en-v2", "EN-us"))
        assertEquals("ar", MoonshineSpeechModel.language("moonshine-base-ar-v2", "auto"))
        assertEquals("zh", MoonshineSpeechModel.language("moonshine-base-zh-v2", "zh-CN"))
        assertFailsWith<IllegalArgumentException> { MoonshineSpeechModel.language("moonshine-base-zh-v2", "en") }
        assertFailsWith<IllegalArgumentException> { MoonshineSpeechModel.language("moonshine-base-en-v2", "ar") }
        assertTrue(assertFailsWith<IllegalStateException> { MoonshineSpeechModel.language("unknown-model", "auto") }
            .message.orEmpty().contains("Unknown Moonshine desktop model"))
    }
}
