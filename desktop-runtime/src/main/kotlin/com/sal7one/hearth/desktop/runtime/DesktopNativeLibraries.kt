package com.sal7one.hearth.desktop.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.security.MessageDigest

fun defaultDataDirectory(): Path {
    val home = Path.of(System.getProperty("user.home"))
    return when {
        System.getProperty("os.name").startsWith("Windows", true) -> Path.of(System.getenv("LOCALAPPDATA") ?: home.resolve("AppData/Local").toString()).resolve("Hearth")
        System.getProperty("os.name").contains("Mac", true) -> home.resolve("Library/Application Support/Hearth")
        else -> Path.of(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").toString()).resolve("hearth")
    }
}

/** A model-free, hashed native bundle. Loading never downloads a runtime or a model. */
internal object DesktopNativeLibraries {
    private var dataDirectory = defaultDataDirectory()
    private val loaded = mutableSetOf<String>()
    val platform: String get() {
        val os = System.getProperty("os.name").lowercase().let { if (it.startsWith("windows")) "windows" else if (it.contains("mac")) "macos" else "linux" }
        val arch = System.getProperty("os.arch").lowercase().let { if (it in setOf("aarch64", "arm64")) "arm64" else if (it in setOf("amd64", "x86_64")) "x64" else it }
        return "$os-$arch"
    }
    @Synchronized fun configure(directory: Path) { dataDirectory = directory }
    @Synchronized fun readiness(name: String): String? = try { load(name); null } catch (error: Exception) { error.message ?: error.toString() } catch (error: LinkageError) { error.message ?: error.toString() }
    @Synchronized fun load(name: String) {
        if (name in loaded) return
        val filename = System.mapLibraryName(name)
        val configured = System.getProperty("hearth.native.dir")?.let(Path::of)
        val candidates = listOfNotNull(configured, configured?.resolve(platform), dataDirectory.resolve("native/$platform"))
        var folder = candidates.firstOrNull { Files.isRegularFile(it.resolve("manifest.json")) }
        if (folder == null) {
            val resource = "/native/$platform/manifest.json"
            val manifest = javaClass.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: error("Desktop native runtime is not installed for $platform; build/package it or set hearth.native.dir")
            val digest = sha256(manifest)
            val target = dataDirectory.resolve("runtime/$platform/$digest")
            Files.createDirectories(target)
            val files = nativeBundleFiles(Json.parseToJsonElement(manifest.toString(Charsets.UTF_8)).jsonObject)
            for ((file, hash) in files) {
                val destination = target.resolve(file)
                if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || sha256(destination) != hash) {
                    val bytes = readPackagedNative(platform, file, hash) { resource ->
                        javaClass.getResourceAsStream(resource)?.use { it.readBytes() }
                    }
                    val temporary = destination.resolveSibling("$file.part")
                    Files.write(temporary, bytes)
                    Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            Files.write(target.resolve("manifest.json"), manifest)
            folder = target
        }
        val manifest = Json.parseToJsonElement(Files.readString(folder.resolve("manifest.json"))).jsonObject
        check(manifest.getValue("platform").jsonPrimitive.content == platform) { "Wrong desktop native architecture" }
        val files = nativeBundleFiles(manifest)
        val expected = files[filename] ?: error("This desktop bundle does not include $filename")
        check(Files.isRegularFile(folder.resolve(filename), LinkOption.NOFOLLOW_LINKS)) { "Desktop native library is not a regular file: $filename" }
        check(sha256(folder.resolve(filename)) == expected) { "Desktop native checksum failed: $filename" }
        // Preflight all declared dependencies before loading any, never scan a model directory or PATH.
        val dependencies = nativeDependencyOrder(name, platform, files.keys)
        verifiedNativeDependencies(folder, dependencies, files).forEach { dependency -> System.load(dependency.toAbsolutePath().toString()) }
        System.load(folder.resolve(filename).toAbsolutePath().toString())
        loaded += name
    }
}

internal fun verifiedNativeDependencies(directory: Path, orderedFiles: List<String>, hashes: Map<String, String>): List<Path> = orderedFiles.map { filename ->
    val path = directory.resolve(filename)
    check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Native dependency is not a regular file: $filename" }
    check(sha256(path) == hashes.getValue(filename)) { "Native dependency checksum failed: $filename" }
    path
}

/** Windows ORT links the Microsoft DLL CRT even though Hearth's own JNI targets use /MT. */
internal fun nativeDependencyOrder(library: String, platform: String, files: Set<String>): List<String> {
    if (library != "hearth_supertonic_jni" && library != "hearth_desktop_qwen3_asr") return emptyList()
    // The Qwen3-ASR stage is staged for macOS arm64 only; do not preload a Windows ORT for a dylib that is not part of that bundle.
    if (library == "hearth_desktop_qwen3_asr" && !platform.startsWith("macos-")) return emptyList()
    if (platform.startsWith("windows-")) {
        val microsoft = listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "msvcp140_1.dll")
        for (filename in microsoft) check(filename in files) {
            "Windows $library is missing its bundled Microsoft Visual C++ dependency: $filename. Rebuild/package the verified Windows runtime."
        }
        check("onnxruntime.dll" in files) { "$library bundle is missing its host ONNX Runtime" }
        return microsoft + "onnxruntime.dll"
    }
    val candidates = if (platform.startsWith("macos-")) listOf("libonnxruntime.1.20.1.dylib", "libonnxruntime.dylib") else listOf("libonnxruntime.so")
    return listOf(candidates.firstOrNull { it in files } ?: error("$library bundle is missing its host ONNX Runtime"))
}

internal fun nativeResourceFilename(filename: String): String {
    val lower = filename.lowercase()
    return if (lower.endsWith(".dylib") || lower.endsWith(".dll") || lower.endsWith(".so") || ".so." in lower) "$filename.payload" else filename
}

/** The JAR envelope changes only the entry name. Original bytes and manifest hashes stay exact. */
internal fun readPackagedNative(platform: String, filename: String, expectedSha256: String, readResource: (String) -> ByteArray?): ByteArray {
    val resource = "/native/$platform/${nativeResourceFilename(filename)}"
    val bytes = readResource(resource) ?: error("Packaged native dependency is missing: $filename")
    check(sha256(bytes) == expectedSha256) { "Packaged native checksum failed: $filename" }
    return bytes
}

internal fun nativeBundleFiles(manifest: JsonObject): Map<String, String> = manifest.getValue("files").jsonObject.map { (filename, value) ->
    require(filename.matches(Regex("[A-Za-z0-9_.-]{1,180}")) && filename != "." && filename != "..") { "Invalid native bundle filename" }
    val hash = value.jsonPrimitive.content
    require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid native bundle SHA-256: $filename" }
    filename to hash
}.toMap()

internal fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    digest.digest().joinToString("") { "%02x".format(it) }
}
internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
