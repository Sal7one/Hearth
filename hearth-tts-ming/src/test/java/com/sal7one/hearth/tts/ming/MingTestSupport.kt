package com.sal7one.hearth.tts.ming

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

internal object MingTestSupport {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun writeAssets(directory: File, contents: LinkedHashMap<String, ByteArray>): List<MingAsset> {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create ${directory.absolutePath}" }
        return contents.map { (name, bytes) ->
            File(directory, name).writeBytes(bytes)
            MingAsset(name, bytes.size.toLong(), sha256(bytes), "https://example.invalid/$name")
        }
    }

    fun bundle(directory: File): List<MingAsset> = writeAssets(
        directory,
        linkedMapOf(
            "manifest.json" to "manifest".toByteArray(),
            "tokenizer.json" to "tokenizer".toByteArray(),
            "part-000.bin" to "part-zero".toByteArray(),
        ),
    )

    fun wavBytes(samples: ShortArray, sampleRate: Int = 44_100, channels: Int = 1, bits: Int = 16): ByteArray {
        val bytesPerSample = bits / 8
        val data = ByteBuffer.allocate(samples.size * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sample ->
            when (bits) {
                16 -> data.putShort(sample)
                8 -> data.put((sample.toInt() shr 8).toByte())
                else -> error("unsupported test width")
            }
        }
        val dataBytes = data.array()
        val fmt = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * bytesPerSample)
            putShort((channels * bytesPerSample).toShort())
            putShort(bits.toShort())
        }.array()
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(4 + fmt.size + 8 + dataBytes.size)
            put("WAVE".toByteArray())
        }.array()
        val dataHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("data".toByteArray())
            putInt(dataBytes.size)
        }.array()
        return header + fmt + dataHeader + dataBytes
    }

    fun tempDirectory(prefix: String): File = Files.createTempDirectory(prefix).toFile()
}
