package com.sal7one.transiber.downloads

import java.io.InputStream
import java.security.MessageDigest

/** Streams a publisher-pinned artifact without trusting the saved download phase. */
internal object DownloadIntegrity {
    data class Fingerprint(val bytes: Long, val sha256: String)

    /** A saved digest detects later local edits; only a publisher pin authenticates the download. */
    fun fingerprint(input: InputStream, maxBytes: Long, checkActive: () -> Unit = {}): Fingerprint {
        require(maxBytes > 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var length = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            length = Math.addExact(length, count.toLong())
            require(length <= maxBytes) { "Downloaded file exceeds verification limit" }
            digest.update(buffer, 0, count)
        }
        return Fingerprint(length, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    fun matches(input: InputStream, asset: DownloadAsset, checkActive: () -> Unit = {}): Boolean {
        return try { fingerprint(input, asset.bytes, checkActive) == Fingerprint(asset.bytes, asset.sha256) }
        catch (_: IllegalArgumentException) { false }
    }
}
