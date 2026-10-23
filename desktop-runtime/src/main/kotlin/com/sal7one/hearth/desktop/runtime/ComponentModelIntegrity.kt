package com.sal7one.hearth.desktop.runtime

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

internal data class ComponentFileStamp(val name: String, val sha256: String, val path: Path,
    val bytes: Long, val modified: FileTime, val created: FileTime, val fileKey: String?)
internal data class ComponentModelStamp(val root: Path, val files: List<ComponentFileStamp>)

/** Verifies caller-recorded pins against a complete engine input inventory; never invents pins from local files. */
internal class ComponentModelIntegrity(
    private val label: String,
    private val required: Set<String>,
    private val optional: Set<String> = emptySet(),
    private val component: (Path, String) -> Path = { root, name -> root.resolve(name) },
) {
    fun stamp(root: Path, options: Map<String, String>, keepActive: () -> Unit = {}): ComponentModelStamp {
        val pins = options.filterKeys { it.startsWith("sha256.") }.mapKeys { it.key.removePrefix("sha256.") }
        require(pins.keys.containsAll(required)) { "$label requires SHA-256 pins for every component; missing: ${(required - pins.keys).sorted().joinToString()}" }
        require(pins.keys.all { it in required || it in optional }) { "Unexpected $label component pin" }
        require(pins.values.all { it.matches(Regex("[0-9a-f]{64}")) }) { "$label component SHA-256 must contain 64 lowercase hexadecimal characters" }
        val canonical = root.toRealPath()
        require(Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) { "Select the complete $label model directory" }
        val files = pins.toSortedMap().map { (name, expected) ->
            keepActive()
            val file = component(canonical, name).toAbsolutePath().normalize()
            require(file.startsWith(canonical)) { "$label component escapes its directory: $name" }
            var current = canonical
            for (part in canonical.relativize(file)) {
                current = current.resolve(part)
                require(!Files.isSymbolicLink(current)) { "$label component contains a symbolic link: $name" }
            }
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(attributes.isRegularFile && attributes.size() > 0) { "Missing or empty $label component: $name" }
            ComponentFileStamp(name, expected, file, attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(), attributes.fileKey()?.toString())
        }
        return ComponentModelStamp(canonical, files)
    }

    fun verify(root: Path, options: Map<String, String>, keepActive: () -> Unit = {}): ComponentModelStamp {
        val before = stamp(root, options, keepActive)
        for (file in before.files) {
            keepActive()
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file.path).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    keepActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == file.sha256) { "$label component checksum mismatch: ${file.name}" }
        }
        check(stamp(root, options, keepActive) == before) { "$label components changed during verification" }
        return before
    }
}
