package com.sal7one.hearth.desktop.service

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** Everything the service keeps on disk, below Hearth's data folder. */
internal class ServiceDirectory(val data: Path) {
    val root: Path = data.resolve("service")
    val clients: Path = root.resolve("clients")
    val jobs: Path = root.resolve("jobs")
    val setups: Path = root.resolve("setups")
    val descriptor: Path = root.resolve("host.json")
    val lock: Path = root.resolve("host.lock")
    val measurements: Path = root.resolve("measurements.json")
    val log: Path = data.resolve("logs").resolve("service.log")

    fun create() {
        Files.createDirectories(data)
        listOf(root, clients, jobs, setups, log.parent).forEach(PrivateFiles::directory)
    }
}

/** Files and folders only the current user can read: POSIX modes, or a single-owner ACL on Windows. */
internal object PrivateFiles {
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    fun directory(path: Path): Path { Files.createDirectories(path); restrict(path, directory = true); return path }

    /** Atomic replace; the bytes are never visible under another account or half-written. */
    fun write(path: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            restrict(temporary, directory = false)
            Files.write(temporary, bytes)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        restrict(path, directory = false)
    }

    fun restrict(path: Path, directory: Boolean) {
        if (posix) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
            return
        }
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: error("This file system cannot restrict $path to the current user")
        val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.owner)
            .setPermissions(AclEntryPermission.values().toSet())
            .apply { if (directory) setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) }.build()
        view.acl = listOf(entry)
    }

    /** True when no account other than the owner is granted anything. */
    fun ownerOnly(path: Path): Boolean {
        if (posix) return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).none { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") }
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) ?: return false
        return view.acl.isNotEmpty() && view.acl.all { it.type() == AclEntryType.ALLOW && it.principal() == view.owner }
    }
}

/**
 * Programs the owner approved, one token file each. A token is created only by [approve],
 * an explicit owner action, and is compared in constant time. Nothing here logs or returns it.
 */
internal class ServiceClients(private val directory: Path) {
    fun approve(name: String): Path {
        val file = file(name)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            val token = ByteArray(32).also(SecureRandom()::nextBytes)
            PrivateFiles.write(file, Base64.getUrlEncoder().withoutPadding().encode(token))
        }
        return file
    }

    fun revoke(name: String): Boolean = Files.deleteIfExists(file(name))

    /** The approved client that owns this bearer token, or null. Every token is checked, so timing names no client. */
    fun authenticate(authorization: String?): String? {
        val presented = authorization?.takeIf { it.startsWith("Bearer ") }?.substring(7)?.toByteArray(Charsets.US_ASCII) ?: return null
        var client: String? = null
        if (!Files.isDirectory(directory)) return null
        Files.newDirectoryStream(directory, "*.token").use { files ->
            for (file in files.sortedBy { it.fileName.toString() }) {
                val name = file.fileName.toString().removeSuffix(".token")
                if (!name.matches(NAME) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) !in 32..128) continue
                if (MessageDigest.isEqual(Files.readAllBytes(file), presented) && client == null) client = name
            }
        }
        return client
    }

    private fun file(name: String): Path {
        require(name.matches(NAME)) { "A client name is 1-32 lower-case letters, digits or hyphens" }
        return directory.resolve("$name.token")
    }

    private companion object { val NAME = Regex("[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?") }
}

/**
 * Size-bounded service log. It records what happened to a job, never what was in it:
 * values are reduced to identifier characters, so audio, text and tokens cannot appear.
 */
internal class ServiceLog(private val file: Path, private val maximumBytes: Long = 1024 * 1024) {
    @Synchronized fun event(name: String, vararg fields: Pair<String, Any?>) {
        val line = buildString {
            append(Instant.now()).append(' ').append(clean(name))
            for ((key, value) in fields) if (value != null) append(' ').append(clean(key)).append('=').append(clean(value.toString()))
            append('\n')
        }
        runCatching {
            Files.createDirectories(file.parent)
            if (Files.exists(file) && Files.size(file) + line.length > maximumBytes) {
                Files.move(file, file.resolveSibling("${file.fileName}.1"), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.write(file, line.toByteArray(Charsets.US_ASCII), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        }
    }

    private fun clean(value: String): String = value.take(96).map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_.:-") it else '_' }.joinToString("")
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun sha256Hex(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Deletes a job folder and everything in it without following links out of it. */
internal fun deleteTree(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
}
