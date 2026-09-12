package com.sal7one.transiber.models

import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.speech.SpeechModelPackage
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** Bounded publisher-archive adapter. Publication still requires the existing manifest verifier. */
internal object PublisherSpeechPackage {
    fun stage(input: InputStream, source: SpeechDownload, work: File, checkActive: () -> Unit = {}): File {
        val packageDigest = MessageDigest.getInstance("SHA-256")
        val counted = CountingInput(DigestInputStream(input, packageDigest), source.bytes, checkActive)
        // BZip2 reads individual compressed bytes. Buffer above the digest/count layer so
        // content-provider input is read in blocks instead of millions of file-descriptor calls.
        val raw = counted.buffered(65536)
        val assets = JSONArray()
        val seen = mutableSetOf<String>()
        var expanded = 0L
        val staged = ModelIntegrity.stageDirectory(work, "package") { sink ->
            fun add(path: String, stream: InputStream, size: Long) {
                require(seen.add(path)) { "Duplicate publisher asset: $path" }
                require(seen.size <= 1000) { "Publisher archive exceeds 1000 files" }
                require(size in 1..ModelIntegrity.MAX_SINGLE_FILE_BYTES && expanded <= ModelIntegrity.MAX_TREE_BYTES - size) { "Publisher asset exceeds size limit: $path" }
                expanded += size
                val digest = MessageDigest.getInstance("SHA-256")
                val content = CountingInput(DigestInputStream(stream, digest), size, checkActive)
                sink.addFile(path, content)
                require(content.count == size) { "Truncated publisher asset: $path" }
                assets.put(JSONObject().put("path", path).put("bytes", size).put("sha256", digest.digest().hex()))
            }
            if (source.archiveRoot == null) {
                add(source.roles.getValue("model"), raw, source.bytes)
            } else {
                // Stream the archive into the verified staging tree; no temporary ZIP or second extraction.
                val decompressed = BZip2CompressorInputStream(raw)
                val tar = TarArchiveInputStream(decompressed)
                var entries = 0
                while (true) {
                    checkActive()
                    val entry = tar.nextEntry ?: break
                    require(++entries <= 2000) { "Publisher archive exceeds 2000 entries" }
                    require(!entry.isSymbolicLink && !entry.isLink && !entry.isSparse && (entry.isDirectory || entry.isFile)) { "Unsupported publisher entry: ${entry.name}" }
                    val full = entry.name.trimEnd('/')
                    require(full.split('/').all { it.isNotEmpty() && it != "." && it != ".." } && '\\' !in full && ':' !in full && full.none { it.isISOControl() }) { "Invalid publisher path: $full" }
                    require(full == source.archiveRoot || full.startsWith(source.archiveRoot + "/")) { "Unexpected publisher root: $full" }
                    if (full == source.archiveRoot) { require(entry.isDirectory); continue }
                    val relative = full.removePrefix(source.archiveRoot + "/")
                    require(relative != SpeechModelPackage.MANIFEST) { "Publisher must not supply installation metadata" }
                    if (entry.isDirectory) sink.addDirectory(relative) else add(relative, tar, entry.size)
                }
                // Bound trailing decompressed data too, and consume the compressed checksum/footer.
                val buffer = ByteArray(65536)
                var trailing = 0L
                while (true) { checkActive(); val n = decompressed.read(buffer); if (n < 0) break
                    trailing += n; require(trailing <= 1_048_576) { "Excess trailing archive data" } }
            }
            val buffer = ByteArray(65536)
            while (raw.read(buffer) >= 0) { checkActive() }
            require(counted.count == source.bytes) { "Publisher download has the wrong size: ${counted.count}, expected ${source.bytes}" }
            require(packageDigest.digest().hex() == source.sha256) { "Publisher download SHA-256 mismatch" }
            val manifest = JSONObject().put("schemaVersion", 1).put("profile", source.profile.id)
                .put("roles", JSONObject(source.roles)).put("files", assets)
            sink.addFile(SpeechModelPackage.MANIFEST, manifest.toString().byteInputStream())
        }
        try {
            checkActive()
            SpeechModelPackage.verify(staged.file)
            return staged.file
        } catch (e: Exception) { staged.file.deleteRecursively(); throw e }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private class CountingInput(input: InputStream, private val limit: Long, private val checkActive: () -> Unit) : FilterInputStream(input) {
        var count = 0L; private set
        override fun read(): Int { checkActive(); val b = `in`.read(); if (b >= 0) account(1); return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int { checkActive(); val n = `in`.read(b, off, len); if (n > 0) account(n); return n }
        private fun account(n: Int) { count += n; require(count <= limit) { "Publisher data exceeds expected size $limit" } }
        override fun close() {} // Owner closes the complete source; archive entries share a stream.
    }
}
