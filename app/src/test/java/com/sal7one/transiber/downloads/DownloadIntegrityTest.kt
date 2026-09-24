package com.sal7one.transiber.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
}
