package com.sal7one.transiber.setup

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class SetupModelVerifierTest {
    @Test fun previewReusesVerificationButApplyRehashesEvenIfMetadataIsRestored() {
        val file = Files.createTempFile("setup-verify", ".bin").toFile()
        try {
            file.writeText("good")
            val expected = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val verifier = SetupModelVerifier()
            val first = verifier.inspect(file, expected)
            assertSame(first, verifier.inspect(file, expected))
            val stamp = Files.getLastModifiedTime(file.toPath())
            file.writeText("evil"); Files.setLastModifiedTime(file.toPath(), stamp)
            // Preview metadata is a speed hint, never a substitute for Apply/native-load integrity.
            assertSame(first, verifier.inspect(file, expected))
            assertThrows(Exception::class.java) { verifier.inspect(file, expected, force = true) }
            file.writeText("changed size")
            assertThrows(Exception::class.java) { verifier.inspect(file, expected) }
        } finally { file.delete() }
    }
    @Test fun deletedModelsAndNewTreeLinksInvalidateAPreviewReceipt() {
        val root = Files.createTempDirectory("setup-tree").toFile()
        try {
            val file = root.resolve("model.bin").apply { writeText("model") }
            val verifier = SetupModelVerifier(); verifier.inspect(root)
            Files.createSymbolicLink(root.resolve("link").toPath(), file.toPath())
            assertThrows(Exception::class.java) { verifier.inspect(root) }
            root.resolve("link").delete(); file.delete()
            assertThrows(Exception::class.java) { verifier.inspect(file) }
        } finally { root.deleteRecursively() }
    }
}
