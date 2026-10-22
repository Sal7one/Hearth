package com.sal7one.hearth.desktop.runtime

import kotlinx.serialization.json.*
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
    private val locations = mutableMapOf<String, Path>()
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
        val dependencies = nativeDependencyOrder(name, platform, files.keys, manifest)
        if (name in setOf("hearth_desktop_whisper", "transiber_translation") && dependencies.any { it.startsWith("cublas") }) {
            check(!Files.exists(folder.resolve("nvcuda.dll"), LinkOption.NOFOLLOW_LINKS)) { "The NVIDIA driver must not be app-local" }
            val cudaFiles = manifest.getValue("cudaRuntime").jsonObject.getValue("sha256").jsonObject.keys.toList()
            // Notices/provenance and all code dependencies must match before loading the first DLL.
            verifiedNativeDependencies(folder, cudaFiles, files)
        }
        verifiedNativeDependencies(folder, dependencies, files).forEach { dependency -> System.load(dependency.toAbsolutePath().toString()) }
        System.load(folder.resolve(filename).toAbsolutePath().toString())
        locations[name] = folder.resolve(filename).toAbsolutePath()
        loaded += name
    }
    @Synchronized fun libraryPath(name: String): Path {
        val cached = locations[name]
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            load(name)
            return cached
        }
        if (cached != null) {
            loaded.remove(name)
            locations.remove(name)
        }
        load(name)
        return locations[name] ?: error("Desktop native library path is unavailable: $name")
    }
}

internal fun verifiedNativeDependencies(directory: Path, orderedFiles: List<String>, hashes: Map<String, String>): List<Path> = orderedFiles.map { filename ->
    val path = directory.resolve(filename)
    check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Native dependency is not a regular file: $filename" }
    check(sha256(path) == hashes.getValue(filename)) { "Native dependency checksum failed: $filename" }
    path
}

/** Windows ORT links the Microsoft DLL CRT even though Hearth's own JNI targets use /MT. */
internal fun nativeDependencyOrder(library: String, platform: String, files: Set<String>, manifest: JsonObject? = null): List<String> {
    if (manifest != null && library in setOf("hearth_desktop_whisper", "transiber_translation"))
        return nativeCudaDependencyOrder(library, platform, files, manifest)
    // The Vosk adapter dlopens its sibling at create() time. Verify and load the pinned
    // runtime first so the path the adapter resolves is the file this manifest approved,
    // never a post-extraction swap or a DYLD fallback.
    if (library == "hearth_vosk") {
        check(platform.startsWith("macos-")) { "The Vosk desktop backend is staged for macOS only" }
        check("libvosk.dylib" in files) { "hearth_vosk bundle is missing its pinned Vosk runtime: libvosk.dylib" }
        return listOf("libvosk.dylib")
    }
    if (library !in setOf("hearth_supertonic_jni", "hearth_desktop_qwen3_asr", "hearth_desktop_moss", "hearth_desktop_chatterbox")) return emptyList()
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

/** Cross-check the binary's recorded native export probe, not a requested build flag. */
private fun compiledBackends(filename: String, files: Set<String>, manifest: JsonObject): Set<String> {
    val entries = manifest["libraries"]?.jsonObject ?: return emptySet() // Legacy CPU/Metal bundle: JNI protocol validation still applies.
    val entry = entries[filename]?.jsonObject ?: return emptySet()
    check(filename in files && entry["sha256"]?.jsonPrimitive?.content == manifest.getValue("files").jsonObject[filename]?.jsonPrimitive?.content) { "Native compiled-backend evidence does not match $filename" }
    if (entry["backendProbe"]?.jsonPrimitive?.content != "native-export-v1") return emptySet()
    check(entry["sourceSetSha256"]?.jsonPrimitive?.content?.matches(Regex("[0-9a-f]{64}")) == true) { "Native backend evidence has no source pin: $filename" }
    val backends = entry.getValue("compiledBackends").jsonArray.map { it.jsonPrimitive.content }
    check(backends.isNotEmpty() && backends.distinct().size == backends.size && "cpu" in backends && backends.all { it in setOf("cpu", "metal", "cuda") }) { "Invalid native backend evidence: $filename" }
    return backends.toSet()
}

/** CUDA redistributables are app-local, pinned and loaded in dependency order. The NVIDIA driver is never bundled. */
internal fun nativeCudaDependencyOrder(library: String, platform: String, files: Set<String>, manifest: JsonObject): List<String> {
    val filename = when (library) {
        "hearth_desktop_whisper" -> if (platform.startsWith("windows-")) "hearth_desktop_whisper.dll" else System.mapLibraryName(library)
        "transiber_translation" -> if (platform.startsWith("windows-")) "transiber_translation.dll" else System.mapLibraryName(library)
        else -> return emptyList()
    }
    val backends = compiledBackends(filename, files, manifest)
    val receipt = manifest["cudaRuntime"]?.jsonObject
    if ("cuda" !in backends) {
        check(receipt?.get("entries")?.jsonArray?.none { it.jsonPrimitive.content == filename } != false) { "CUDA dependency receipt names a binary without compiled CUDA: $filename" }
        return emptyList()
    }
    check(platform == "windows-x64") { "Packaged CUDA runtime currently supports Windows x64 only" }
    check(receipt != null) { "$filename was compiled with CUDA but its pinned NVIDIA redistributables are missing" }
    check(receipt.getValue("schemaVersion").jsonPrimitive.int == 1 &&
        receipt.getValue("requiresSystemDriver").jsonPrimitive.boolean &&
        receipt.getValue("driver").jsonPrimitive.content == "nvcuda.dll") { "Invalid CUDA runtime dependency policy" }
    check(files.none { it.equals("nvcuda.dll", true) }) { "The NVIDIA driver must come from Windows, never the app-local bundle" }
    val toolkit = receipt.getValue("toolkitVersion").jsonPrimitive.content
    check(toolkit.matches(Regex("(?:12|13)\\.[0-9]+(?:\\.[0-9]+)?"))) { "Unsupported CUDA redistributable toolkit version" }
    val major = toolkit.substringBefore('.')
    fun list(name: String): List<String> = receipt.getValue(name).jsonArray.map { it.jsonPrimitive.content }
    val entries = list("entries")
    val knownEntries = setOf("hearth_desktop_whisper.dll", "transiber_translation.dll")
    val cudaEntries = knownEntries.filter { "cuda" in compiledBackends(it, files, manifest) }.toSet()
    check(entries.distinct().size == entries.size && entries.toSet() == cudaEntries) { "CUDA receipt does not match the actual CUDA JNI binaries" }
    val dependencies = list("files")
    val ordered = list("loadOrder")
    check(dependencies.isNotEmpty() && dependencies.size <= 16 && dependencies.distinct().size == dependencies.size &&
        ordered.distinct().size == ordered.size && ordered.toSet() == dependencies.toSet() &&
        dependencies.all { it.matches(Regex("(?:cudart|cublas|cublaslt)64_$major\\.dll")) }) { "Invalid CUDA dependency inventory/order" }
    check(dependencies.containsAll(listOf("cublas64_$major.dll", "cublaslt64_$major.dll")) &&
        ordered.indexOf("cublaslt64_$major.dll") < ordered.indexOf("cublas64_$major.dll")) { "CUDA cuBLAS/Lt dependency order is incomplete" }
    val provenance = receipt.getValue("provenance").jsonPrimitive.content
    val license = receipt.getValue("license").jsonPrimitive.content
    check(provenance == "nvidia-cuda-provenance.json" && license == "NVIDIA-CUDA-EULA.txt") { "CUDA provenance/license record is missing" }
    val recorded = receipt.getValue("sha256").jsonObject.mapValues { it.value.jsonPrimitive.content }
    check(recorded.keys == (dependencies + provenance + license + "NVIDIA-CUDA-NOTICE.txt").toSet()) { "CUDA receipt hashes do not cover the exact installed inventory" }
    val hashes = nativeBundleFiles(manifest)
    for ((file, hash) in recorded) check(file in files && hash.matches(Regex("[0-9a-f]{64}")) && hashes[file] == hash) { "CUDA dependency has no matching bundle checksum: $file" }
    val microsoft = listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "msvcp140_1.dll")
    for (file in microsoft) check(file in files) { "CUDA clean-PC runtime is missing its pinned Microsoft dependency: $file" }
    return microsoft + ordered
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
