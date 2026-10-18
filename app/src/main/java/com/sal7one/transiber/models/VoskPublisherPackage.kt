package com.sal7one.transiber.models

import com.sal7one.common_jni.model.ModelIntegrity
import org.apache.commons.compress.archivers.zip.ZipFile as MetadataZipFile
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** The original ZIP and the expanded model tree must both match independent catalog pins. */
internal object VoskPublisherPackage {
    fun populate(input: InputStream, source: VoskDownload, work: File,
                 sink: ModelIntegrity.DirectorySink, checkActive: () -> Unit = {}) {
        val staged = ModelIntegrity.stageFile(input, work, "publisher.zip", source.artifact.sha256,
            source.artifact.bytes) { checkActive() }
        try {
            require(staged.file.length() == source.artifact.bytes) { "Vosk archive has the wrong size" }
            // Commons preserves Unix entry modes for rejecting links/special files.
            // The platform ZIP reader handles only STORE/DEFLATE data: using the
            // generic Commons decoder would retain unrelated optional codecs in R8.
            MetadataZipFile.builder().setFile(staged.file).get().use { metadata ->
                ZipFile(staged.file).use { zip ->
                    val seen = mutableSetOf<String>()
                    val files = mutableSetOf<String>()
                    var entries = 0
                    var expanded = 0L
                    val root = checkNotNull(source.artifact.archiveRoot)
                    val iterator = metadata.entries
                    while (iterator.hasMoreElements()) {
                        checkActive()
                        val entry = iterator.nextElement()
                        require(++entries <= 2000) { "Vosk archive exceeds 2000 entries" }
                        val mode = entry.unixMode and 0xf000
                        require(!entry.isUnixSymlink && (mode == 0 || mode == 0x4000 || mode == 0x8000) &&
                            (mode != 0x4000 || entry.isDirectory) && (mode != 0x8000 || !entry.isDirectory)) {
                            "Unsupported Vosk archive entry: ${entry.name}"
                        }
                        require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED) {
                            "Unsupported Vosk ZIP compression method: ${entry.method}"
                        }
                        require(metadata.canReadEntryData(entry)) { "Unsupported Vosk ZIP encoding: ${entry.name}" }
                        val full = if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name
                        require(full.isNotBlank() && full.length <= 1024 &&
                            full.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
                            '\\' !in full && ':' !in full && full.none(Char::isISOControl)) { "Invalid Vosk archive path: $full" }
                        require(seen.add(full)) { "Duplicate Vosk archive path: $full" }
                        require(full == root || full.startsWith("$root/")) { "Unexpected Vosk archive root: $full" }
                        val dataEntry = zip.getEntry(entry.name) ?: error("Vosk ZIP readers disagree on entry: ${entry.name}")
                        require(dataEntry.name == entry.name && dataEntry.method == entry.method &&
                            dataEntry.isDirectory == entry.isDirectory && dataEntry.size == entry.size &&
                            dataEntry.compressedSize == entry.compressedSize && dataEntry.crc == entry.crc) {
                            "Vosk ZIP readers disagree on metadata: ${entry.name}"
                        }
                        if (full == root) { require(entry.isDirectory); continue }
                        val relative = full.removePrefix("$root/")
                        if (entry.isDirectory) sink.addDirectory(relative) else {
                            files.add(relative)
                            require(entry.size >= 0 && entry.size <= ModelIntegrity.MAX_SINGLE_FILE_BYTES &&
                                entry.size <= source.expandedBytes - expanded) { "Vosk archive exceeds expanded size limit" }
                            expanded += entry.size
                            zip.getInputStream(dataEntry).use { content ->
                                val checked = CheckedInput(content, entry.size, checkActive)
                                sink.addFile(relative, checked)
                                require(checked.count == entry.size && checked.crc.value == entry.crc) {
                                    "Truncated or damaged Vosk archive entry: $relative"
                                }
                            }
                        }
                    }
                    require(zip.size() == entries) { "Vosk ZIP readers disagree on the entry count" }
                    require(expanded == source.expandedBytes) { "Vosk archive has the wrong expanded size" }
                    require(files.containsAll(setOf("am/final.mdl", "conf/model.conf", "conf/mfcc.conf")) &&
                        ("graph/HCLG.fst" in files || files.containsAll(setOf("graph/HCLr.fst", "graph/Gr.fst")))) {
                        "Vosk archive is missing acoustic, feature or graph assets"
                    }
                }
            }
            checkActive()
        } finally { staged.file.delete() }
    }

    private class CheckedInput(input: InputStream, private val limit: Long,
                               private val checkActive: () -> Unit) : FilterInputStream(input) {
        var count = 0L; private set
        val crc = CRC32()
        override fun read(): Int {
            checkActive()
            return `in`.read().also { if (it >= 0) { account(1); crc.update(it) } }
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            checkActive()
            return `in`.read(bytes, offset, length).also { if (it > 0) { account(it); crc.update(bytes, offset, it) } }
        }
        private fun account(size: Int) {
            count += size
            require(count <= limit) { "Vosk entry exceeds its declared size" }
        }
    }
}
