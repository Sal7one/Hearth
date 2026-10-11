package com.sal7one.common_jni.audio

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class Pcm16WaveWriterTest {
    @Test fun chunksAndPartialClosePreserveAudio() {
        val file = Files.createTempFile("wave", ".wav").toFile()
        try {
            val samples = shortArrayOf(Short.MIN_VALUE, -123, 0, 321, Short.MAX_VALUE)
            val writer = Pcm16WaveWriter(file, 16000, 10)
            writer.write(samples, 2); writer.write(samples.copyOfRange(2, 5)); writer.close(); writer.close()
            assertArrayEquals(samples, PcmWave.decode(file.readBytes()).samples)
            assertEquals(5L, writer.samplesWritten)
            assertThrows(IllegalStateException::class.java) { writer.write(samples) }
        } finally { file.delete() }
    }
    @Test fun limitRejectsWholeChunkWithoutTruncatingExistingSamples() {
        val file = Files.createTempFile("wave", ".wav").toFile()
        try {
            Pcm16WaveWriter(file, 16000, 2).use { writer ->
                writer.write(shortArrayOf(1, 2))
                assertThrows(IllegalStateException::class.java) { writer.write(shortArrayOf(3)) }
            }
            assertArrayEquals(shortArrayOf(1, 2), PcmWave.decode(file.readBytes()).samples)
        } finally { file.delete() }
    }
}
