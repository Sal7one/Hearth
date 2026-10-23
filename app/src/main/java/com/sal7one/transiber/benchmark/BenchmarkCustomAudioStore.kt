package com.sal7one.transiber.benchmark

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One deliberately chosen WAV, copied privately so model browsing cannot discard its input. */
internal class BenchmarkCustomAudioStore(private val directory: File) {
    data class Saved(val clip: BenchmarkAudio.Clip, val label: String)

    companion object {
        private const val AUDIO = "custom.wav"
        private const val METADATA = "custom.json"
        private const val PENDING = "custom.pending"
        private const val MAX_METADATA_BYTES = 4096
        private const val MAX_LABEL_LENGTH = 240
        // The app has one process; serialize different store instances during the two-file commit.
        private val lock = Any()
    }

    /** Corruption/interrupted writes are reported, never replaced by a different input. */
    fun load(): Saved? = synchronized(lock) { loadLocked() }

    fun save(clip: BenchmarkAudio.Clip, label: String): Saved = synchronized(lock) {
        val name = label.trim()
        require(name.isNotBlank() && name.length <= MAX_LABEL_LENGTH) { "Use a recording name of 1–240 characters" }
        val snapshot = BenchmarkAudio.fromPcm16(clip.samples.copyOf())
        require(snapshot.sha256 == clip.sha256) { "Custom recording PCM hash changed" }
        val bytes = wave(snapshot.samples)
        val metadata = JSONObject().put("schemaVersion", 1).put("audio", AUDIO).put("label", name)
            .put("bytes", bytes.size).put("sha256", benchmarkHash(bytes)).put("pcmSha256", snapshot.sha256)
            .toString().toByteArray(Charsets.UTF_8)
        check(metadata.size <= MAX_METADATA_BYTES) { "Custom recording metadata is too large" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create private benchmark recording folder" }
        require(!Files.isSymbolicLink(directory.toPath())) { "Private benchmark recording folder is a symbolic link" }
        val audio = child(AUDIO)
        val manifest = child(METADATA)
        val pending = child(PENDING)
        val audioTemp = child("$AUDIO.tmp")
        val manifestTemp = child("$METADATA.tmp")
        try {
            writeSynced(audioTemp, bytes)
            writeSynced(manifestTemp, metadata)
            // A terminated process between the renames must not load the previous label/hash
            // together with newly replaced audio. A subsequent explicit save can repair this.
            writeSynced(pending, byteArrayOf(1))
            Files.move(audioTemp.toPath(), audio.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Files.move(manifestTemp.toPath(), manifest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            check(pending.delete()) { "Cannot finish saving the private benchmark recording" }
            checkNotNull(loadLocked())
        } finally {
            audioTemp.delete()
            manifestTemp.delete()
        }
    }

    fun clear() = synchronized(lock) {
        listOf(METADATA, AUDIO, PENDING, "$AUDIO.tmp", "$METADATA.tmp").forEach { name ->
            val file = child(name)
            if (file.exists()) check(file.delete()) { "Cannot remove private benchmark recording: $name" }
        }
    }

    private fun loadLocked(): Saved? {
        require(!Files.isSymbolicLink(directory.toPath())) { "Private benchmark recording folder is a symbolic link" }
        require(!directory.exists() || directory.isDirectory) { "Private benchmark recording folder is unavailable" }
        val audio = child(AUDIO)
        val manifest = child(METADATA)
        require(!child(PENDING).exists()) { "Saving the custom recording was interrupted. Choose the WAV again." }
        if (!audio.exists() && !manifest.exists()) return null
        require(audio.isFile && manifest.isFile) { "Private benchmark recording is incomplete. Choose the WAV again." }
        require(manifest.length() in 1..MAX_METADATA_BYTES.toLong()) { "Private benchmark recording metadata is missing or oversized" }
        val json = JSONObject(manifest.readText(Charsets.UTF_8))
        require(json.getInt("schemaVersion") == 1 && json.getString("audio") == AUDIO) { "Invalid private benchmark recording metadata" }
        val label = json.getString("label")
        require(label.isNotBlank() && label.length <= MAX_LABEL_LENGTH) { "Invalid private benchmark recording name" }
        require(audio.length() in 44..(44L + BenchmarkAudio.MAX_SECONDS * 16_000L * 2) && audio.length() == json.getLong("bytes")) {
            "Private benchmark recording is missing or oversized. Choose the WAV again."
        }
        val bytes = audio.readBytes()
        require(benchmarkHash(bytes) == json.getString("sha256")) { "Private benchmark recording changed. Choose the WAV again." }
        val clip = BenchmarkAudio.decode(bytes)
        require(clip.sha256 == json.getString("pcmSha256")) { "Private benchmark recording PCM changed. Choose the WAV again." }
        return Saved(clip, label)
    }

    private fun child(name: String): File = File(directory, name).also {
        require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == directory.canonicalFile) {
            "Unsafe private benchmark recording path: $name"
        }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { stream -> stream.write(bytes); stream.fd.sync() }
    }

    private fun wave(samples: ShortArray): ByteArray {
        val size = samples.size * 2
        val output = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size)
        samples.forEach(output::putShort)
        return output.array()
    }
}
