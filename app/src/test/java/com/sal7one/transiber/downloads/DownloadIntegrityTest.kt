package com.sal7one.transiber.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class DownloadIntegrityTest {
    private val original = "publisher model bytes".toByteArray()
    private val asset = DownloadAsset("model", "https://example.org/model", original.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it.toInt() and 255) }, null)

    @Test fun completedOriginalIsCheckedAgainBeforeInstall() {
        assertTrue(DownloadIntegrity.matches(ByteArrayInputStream(original), asset))
        assertFalse(DownloadIntegrity.matches(ByteArrayInputStream("publisher model bytex".toByteArray()), asset))
        assertFalse(DownloadIntegrity.matches(ByteArrayInputStream(original.copyOf(original.size - 1)), asset))
        assertFalse(DownloadIntegrity.matches(ByteArrayInputStream(original + byteArrayOf(1)), asset))
    }

    @Test fun verificationCanBeCancelledWhileReading() {
        assertThrows(CancellationException::class.java) {
            DownloadIntegrity.matches(ByteArrayInputStream(original), asset) { throw CancellationException("paused") }
        }
    }

    @Test fun directFileFingerprintDetectsSameSizeEditsAndTruncation() {
        val saved = DownloadIntegrity.fingerprint(ByteArrayInputStream(original), original.size.toLong())
        assertEquals(asset.sha256, saved.sha256)
        assertEquals(original.size.toLong(), saved.bytes)
        assertTrue(saved != DownloadIntegrity.fingerprint(ByteArrayInputStream("publisher model bytex".toByteArray()), original.size.toLong()))
        assertTrue(saved != DownloadIntegrity.fingerprint(ByteArrayInputStream(original.copyOf(original.size - 1)), original.size.toLong()))
        assertThrows(IllegalArgumentException::class.java) {
            DownloadIntegrity.fingerprint(ByteArrayInputStream(original + byteArrayOf(1)), original.size.toLong())
        }
    }
}
