package com.sal7one.hearth.desktop.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelTreeIntegrityTest {
    @Test fun canonicalNativeContractRejectsDifferentSelectedTree() = fixture { root ->
        // Independently computed by Python hashlib + struct.pack('>Q'), sorted unsigned UTF-8 path bytes.
        val pin = "fcff69742c56a65f4ef0dff6ac41ab9146e2e64d6f6e64837da9a50af9d4d801"
        assertEquals(pin, ModelTreeIntegrity.digest(root))
        assertEquals(root.toRealPath(), ModelTreeIntegrity.verify(root, pin))
        assertFailsWith<IllegalStateException> { ModelTreeIntegrity.verify(root, "0".repeat(64)) }
        Files.writeString(root.resolve("am/final.mdl"), "different model")
        assertFailsWith<IllegalStateException> { ModelTreeIntegrity.verify(root, pin) }
    }
    @Test fun rejectsSymlinksAndMalformedPins() = fixture { root ->
        assertFailsWith<IllegalArgumentException> { ModelTreeIntegrity.verify(root, "") }
        Files.createSymbolicLink(root.resolve("alias"), root.resolve("z.bin"))
        assertFailsWith<IllegalArgumentException> { ModelTreeIntegrity.digest(root) }
    }
    @Test fun hashingIsCancellableAndCannotPublishChangedTree() = fixture { root ->
        var calls = 0
        assertFailsWith<CancellationException> { ModelTreeIntegrity.digest(root) { if (++calls == 3) throw CancellationException("stop") } }
        var mutated = false
        assertFailsWith<IllegalStateException> {
            ModelTreeIntegrity.digest(root) {
                if (++calls == 12) { Files.writeString(root.resolve("extra.bin"), "new"); mutated = true }
            }
        }
        assertTrue(mutated)
    }
    private fun fixture(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("hearth-tree-integrity-")
        try {
            Files.createDirectories(root.resolve("am"))
            for ((name, data) in mapOf("z.bin" to "last", "am/final.mdl" to "model", "أ.bin" to "صوت", "ä.bin" to "accent")) Files.writeString(root.resolve(name), data)
            block(root)
        } finally { root.toFile().deleteRecursively() }
    }
}
