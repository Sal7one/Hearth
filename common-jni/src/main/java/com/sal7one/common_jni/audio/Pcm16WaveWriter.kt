package com.sal7one.common_jni.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded, streaming PCM16 writer. One owner writes and closes; no audio is retained in memory.
 * A closed partial recording is a valid WAV. Publish/rename the file only after close succeeds.
 */
class Pcm16WaveWriter(file: File, val sampleRate: Int, private val maxSamples: Long) : AutoCloseable {
    private val destination = file
    private val output: BufferedOutputStream
    private val scratch = ByteArray(8192)
    private var closed = false
    var samplesWritten: Long = 0
        private set
    init {
        require(sampleRate in 8000..192000 && maxSamples > 0 && maxSamples <= (0xffffffffL - 36) / 2)
        output = BufferedOutputStream(FileOutputStream(file), 32768)
        output.write(header(0))
    }
    fun write(samples: ShortArray, count: Int = samples.size) {
        check(!closed) { "WAV writer is closed" }
        require(count in 0..samples.size)
        check(count.toLong() <= maxSamples - samplesWritten) { "Recording duration or storage limit reached" }
        var offset = 0
        while (offset < count) {
            val size = minOf(count - offset, scratch.size / 2)
            for (i in 0 until size) {
                val value = samples[offset + i].toInt()
                scratch[i * 2] = value.toByte()
                scratch[i * 2 + 1] = (value shr 8).toByte()
            }
            output.write(scratch, 0, size * 2)
            samplesWritten += size
            offset += size
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        output.close()
        RandomAccessFile(destination, "rw").use {
            check(it.length() == 44 + samplesWritten * 2) { "WAV write was incomplete" }
            it.seek(0); it.write(header(samplesWritten * 2)); it.fd.sync()
        }
    }
    private fun header(bytes: Long): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((36 + bytes).toInt())
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(1)
        putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)); putInt(bytes.toInt())
    }.array()
    companion object {
        /** Recover only this writer's interrupted mono PCM16 file. Buffered bytes lost at process
         * death cannot be invented; complete on-disk samples are retained with a corrected header.
         */
        fun recoverInterrupted(file: File, sampleRate: Int, maxSamples: Long): Long {
            RandomAccessFile(file, "rw").use { input ->
                require(input.length() >= 44) { "Recording header is incomplete" }
                val header = ByteArray(44); input.readFully(header)
                val view = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 8, Charsets.US_ASCII) == "WAVEfmt " &&
                    view.getInt(16) == 16 && view.getShort(20).toInt() == 1 &&
                    view.getShort(22).toInt() == 1 && view.getInt(24) == sampleRate &&
                    view.getInt(28) == sampleRate * 2 && view.getShort(32).toInt() == 2 &&
                    view.getShort(34).toInt() == 16 && String(header, 36, 4, Charsets.US_ASCII) == "data") {
                    "Recording is not a Hearth PCM16 WAV"
                }
                val samples = (input.length() - 44) / 2
                require(samples in 0..maxSamples && samples <= (0xffffffffL - 36) / 2)
                input.setLength(44 + samples * 2)
                input.seek(4); input.writeInt(Integer.reverseBytes((36 + samples * 2).toInt()))
                input.seek(40); input.writeInt(Integer.reverseBytes((samples * 2).toInt()))
                input.fd.sync()
                return samples
            }
        }
    }
}
