package com.sal7one.hearth.tts.omnivoice

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class OmniVoiceModels(private val root: File) {
    fun file(asset: OmniVoiceAsset) = File(root, asset.filename)
    fun ready() = OmniVoiceCatalog.assets.all { file(it).let { f -> f.isFile && f.length() == it.bytes } }
    fun verify(cancelled: () -> Unit = {}) {
        OmniVoiceCatalog.assets.forEach { asset ->
            cancelled(); check(file(asset).isFile && file(asset).length() == asset.bytes) { "Install OmniVoice ${asset.filename} first" }
            check(hash(file(asset), cancelled) == asset.sha256) { "OmniVoice checksum failed: ${asset.filename}" }
        }
    }
    fun importWeight(input: InputStream, expected: OmniVoiceAsset, cancelled: () -> Unit = {}) {
        require(expected in OmniVoiceCatalog.assets); root.mkdirs()
        val temporary = File.createTempFile("omnivoice-", ".part", root)
        try {
            temporary.outputStream().use { sink ->
                val buffer = ByteArray(65536); var bytes = 0L
                while (true) { cancelled(); val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= expected.bytes) { "OmniVoice file exceeds pinned size" }; sink.write(buffer, 0, n) }
                require(bytes == expected.bytes) { "OmniVoice file is incomplete" }
            }
            check(hash(temporary, cancelled) == expected.sha256) { "OmniVoice file checksum failed" }
            cancelled(); Files.move(temporary.toPath(), file(expected).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    private fun hash(file: File, cancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(65536); while (true) { cancelled(); val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
