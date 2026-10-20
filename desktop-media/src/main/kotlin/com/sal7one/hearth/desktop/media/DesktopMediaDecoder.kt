package com.sal7one.hearth.desktop.media

import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** Decodes the first audio track of footage with AVFoundation or Media Foundation; no video editing. */
object DesktopMediaDecoder {
    private val os = System.getProperty("os.name").lowercase()
    private val platform = when { "mac" in os -> "macos"; "win" in os -> "windows"; else -> "unsupported" }
    private val arch = when (System.getProperty("os.arch").lowercase()) { "aarch64", "arm64" -> "arm64"; "amd64", "x86_64" -> "x64"; else -> "unsupported" }
    private val filename = "hearth-media-decode" + if (platform == "windows") ".exe" else ""
    private val resource = "/media/$platform-$arch/$filename"
    private fun external(): Path? = (System.getProperty("hearth.native.dir") ?: System.getenv("HEARTH_DESKTOP_NATIVE_DIR"))
        ?.let { Path.of(it).resolve(filename) }?.takeIf { Files.isRegularFile(it) }
    val available: Boolean get() = external() != null || DesktopMediaDecoder::class.java.getResource(resource) != null
    suspend fun decode(input: Path, outputDirectory: Path): Path {
        val delivered = java.util.concurrent.atomic.AtomicReference<Path?>()
        try {
            return withContext(Dispatchers.IO) {
                require(Files.isRegularFile(input) && Files.size(input) in 1..MAX_INPUT_BYTES) { "Media file is missing, empty or larger than 8 GiB" }
                Files.createDirectories(outputDirectory)
                val executable = external() ?: extract(outputDirectory)
                val output = Files.createTempFile(outputDirectory, "hearth-decoded-", ".wav").also(delivered::set)
                var success = false
                try {
                    val process = ProcessBuilder(executable.toAbsolutePath().toString(), input.toAbsolutePath().toString(), output.toAbsolutePath().toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                    try {
                        val errors = async {
                            process.errorStream.use { stream ->
                                val buffer = ByteArray(1024); val text = StringBuilder()
                                while (true) {
                                    val count = stream.read(buffer); if (count < 0) break
                                    if (text.length < 4096) text.append(String(buffer, 0, minOf(count, 4096 - text.length), Charsets.UTF_8))
                                }; text.toString().trim()
                            }
                        }
                        check(runInterruptible { process.waitFor(10, TimeUnit.MINUTES) }) { "Media decoding exceeded ten minutes" }
                        val diagnostic = errors.await()
                        check(process.exitValue() == 0) { "Native media decoder failed (${process.exitValue()}): ${diagnostic.take(4096)}" }
                        check(Files.size(output) in 44..MAX_OUTPUT_BYTES) { "Native decoder returned invalid or oversized audio" }
                        success = true; output
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            if (process.isAlive) { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) } }
                            process.errorStream.close()
                        }
                    }
                } finally { if (!success) Files.deleteIfExists(output) }
            }
        } catch (failure: Throwable) {
            // withContext has prompt cancellation on return: the caller may never receive an otherwise successful file.
            withContext(NonCancellable + Dispatchers.IO) { delivered.getAndSet(null)?.let { Files.deleteIfExists(it) } }
            throw failure
        }
    }
    @Synchronized private fun extract(directory: Path): Path {
        val bytes = DesktopMediaDecoder::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("Native $platform media decoder is not installed. Build/package the desktop media helper; PCM WAV remains supported without it.")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val parent = directory.resolve("helpers/$platform-$arch/$hash")
        Files.createDirectories(parent)
        val executable = parent.resolve(filename)
        if (platform != "windows") Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
        if (!Files.isRegularFile(executable) || !java.security.MessageDigest.isEqual(Files.readAllBytes(executable), bytes)) {
            val temporary = Files.createTempFile(parent, ".helper-", ".tmp")
            try {
                Files.write(temporary, bytes)
                if (platform != "windows") Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwx------"))
                Files.move(temporary, executable, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
        return executable
    }
    const val MAX_INPUT_BYTES: Long = 8L * 1024 * 1024 * 1024
    const val MAX_OUTPUT_BYTES: Long = 44L + 16_000 * 2 * 60 * 60
}
