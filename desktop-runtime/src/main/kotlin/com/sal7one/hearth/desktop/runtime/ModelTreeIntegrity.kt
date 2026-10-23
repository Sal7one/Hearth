package com.sal7one.hearth.desktop.runtime

import java.nio.channels.Channels
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** The existing native/data model-tree-sha256-v1 contract, with bounded, cancellable no-follow reads. */
internal object ModelTreeIntegrity {
    private data class Entry(val relative: String, val path: Path, val bytes: Long, val modified: FileTime,
        val created: FileTime, val fileKey: String?)

    fun verify(root: Path, expected: String, keepActive: () -> Unit = {}): Path {
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "A pinned model tree SHA-256 is required for Vosk" }
        val canonical = root.toRealPath()
        require(Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) { "Select a complete Vosk model directory" }
        check(digest(canonical, keepActive) == expected) { "Vosk model tree checksum mismatch" }
        return canonical
    }

    fun digest(root: Path, keepActive: () -> Unit = {}): String {
        val before = entries(root, keepActive)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("model-tree-sha256-v1\u0000".toByteArray(Charsets.US_ASCII))
        val buffer = ByteArray(64 * 1024)
        for (file in before) {
            keepActive()
            val relative = file.relative.toByteArray(Charsets.UTF_8)
            digest.update(longBytes(relative.size.toLong()))
            digest.update(relative)
            digest.update(longBytes(file.bytes))
            val content = MessageDigest.getInstance("SHA-256")
            var readBytes = 0L
            Channels.newInputStream(Files.newByteChannel(file.path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))).use { input ->
                while (true) {
                    keepActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    readBytes += count
                    check(readBytes <= file.bytes) { "Vosk model tree changed during verification" }
                    content.update(buffer, 0, count)
                }
            }
            check(readBytes == file.bytes) { "Vosk model tree changed during verification" }
            digest.update(content.digest())
        }
        check(entries(root, keepActive) == before) { "Vosk model tree changed during verification" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun entries(root: Path, keepActive: () -> Unit): List<Entry> {
        val files = mutableListOf<Entry>()
        var total = 0L
        var nodes = 0
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attributes: BasicFileAttributes): FileVisitResult {
                keepActive()
                check(++nodes <= 100_001) { "Vosk model tree contains too many entries" }
                check(root.relativize(dir).nameCount <= 32) { "Vosk model tree is too deep" }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                keepActive()
                check(++nodes <= 100_001 && files.size < 100_000) { "Vosk model tree contains too many entries" }
                require(attributes.isRegularFile && !attributes.isSymbolicLink) { "Vosk model tree contains a non-regular entry" }
                val relative = root.relativize(path).joinToString("/")
                require(relative.isNotEmpty() && '\\' !in relative && '\u0000' !in relative &&
                    relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
                    root.relativize(path).nameCount <= 32 && relative.toByteArray(Charsets.UTF_8).size <= 4096) { "Invalid Vosk model tree path" }
                check(attributes.size() in 0..(8L shl 30)) { "Vosk model component exceeds the size limit" }
                total += attributes.size()
                check(total <= (16L shl 30)) { "Vosk model tree exceeds the size limit" }
                files += Entry(relative, path, attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(), attributes.fileKey()?.toString())
                return FileVisitResult.CONTINUE
            }
        })
        check(files.isNotEmpty() && total > 0) { "Vosk model tree is empty" }
        return files.sortedWith { first, second ->
            val a = first.relative.toByteArray(Charsets.UTF_8)
            val b = second.relative.toByteArray(Charsets.UTF_8)
            var comparison = 0
            for (index in 0 until minOf(a.size, b.size)) {
                comparison = (a[index].toInt() and 255) - (b[index].toInt() and 255)
                if (comparison != 0) break
            }
            if (comparison != 0) comparison else a.size - b.size
        }
    }
    private fun longBytes(value: Long) = ByteArray(8) { index -> (value ushr (56 - index * 8)).toByte() }
}
