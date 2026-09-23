package com.sal7one.transiber.downloads

import java.io.InputStream
import java.security.MessageDigest

/** Streams a publisher-pinned artifact without trusting the saved download phase. */
internal object DownloadIntegrity {
    fun matches(input: InputStream, asset: DownloadAsset, checkActive: () -> Unit = {}): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var length = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            length = Math.addExact(length, count.toLong())
            if (length > asset.bytes) return false
            digest.update(buffer, 0, count)
        }
        return length == asset.bytes && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == asset.sha256
    }
}
