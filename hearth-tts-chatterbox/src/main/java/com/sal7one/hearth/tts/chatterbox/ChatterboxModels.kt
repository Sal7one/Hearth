package com.sal7one.hearth.tts.chatterbox

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class ChatterboxModels(private val root: File) {
    fun file(asset: ChatterboxAsset) = File(root, if(asset.filename == ChatterboxGraph.SOURCE_FILENAME) ChatterboxGraph.FILENAME else asset.filename)
    fun ready() = ChatterboxPerth.ready(root) && ChatterboxCatalog.assets.all { file(it).let { f -> f.isFile && f.length() == installedBytes(it) } }
    fun verify(cancelled: () -> Unit = {}) {
        ChatterboxPerth.verify(root,cancelled)
        ChatterboxCatalog.assets.forEach { asset ->
            cancelled(); check(file(asset).isFile && file(asset).length() == installedBytes(asset)) { "Install Chatterbox ${asset.filename} first" }
            check(hash(file(asset), cancelled) == installedSha256(asset)) { "Chatterbox checksum failed: ${asset.filename}" }
        }
    }
    fun importWeight(input: InputStream, expected: ChatterboxAsset, cancelled: () -> Unit = {}) {
        require(expected in ChatterboxCatalog.assets); root.mkdirs()
        val temporary = File.createTempFile("chatterbox-", ".part", root)
        try {
            temporary.outputStream().use { sink ->
                val buffer = ByteArray(65536); var bytes = 0L
                while (true) { cancelled(); val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= expected.bytes) { "Chatterbox file exceeds pinned size" }; sink.write(buffer, 0, n) }
                require(bytes == expected.bytes) { "Chatterbox file is incomplete" }
            }
            check(hash(temporary, cancelled) == expected.sha256) { "Chatterbox file checksum failed" }
            if(expected.filename == ChatterboxGraph.SOURCE_FILENAME) { ChatterboxGraph.install(temporary,root,cancelled); return }
            cancelled(); file(expected).parentFile?.mkdirs(); Files.move(temporary.toPath(), file(expected).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    private fun installedBytes(asset: ChatterboxAsset) = if(asset.filename == ChatterboxGraph.SOURCE_FILENAME) ChatterboxGraph.OUTPUT_BYTES else asset.bytes
    private fun installedSha256(asset: ChatterboxAsset) = if(asset.filename == ChatterboxGraph.SOURCE_FILENAME) ChatterboxGraph.OUTPUT_SHA256 else asset.sha256
    private fun hash(file: File, cancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(65536); while (true) { cancelled(); val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
