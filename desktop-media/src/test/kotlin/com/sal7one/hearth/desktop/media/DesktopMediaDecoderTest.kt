package com.sal7one.hearth.desktop.media

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.sound.sampled.AudioSystem
import kotlin.test.*

class DesktopMediaDecoderTest {
    @Test fun nativeDecoderConvertsRealStereoAudioAndRejectsInvalidMedia() = runBlocking {
        if (System.getProperty("hearth.media.required") == "true") assertTrue(DesktopMediaDecoder.available, "Native media decoder must be staged before the packaged build tests")
        assumeTrue(DesktopMediaDecoder.available)
        val directory = Files.createTempDirectory("hearth-media-test-")
        try {
            val samples = 44100
            val buffer = ByteBuffer.allocate(44 + samples * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray()).putInt(buffer.capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(2).putInt(44100).putInt(176400).putShort(4).putShort(16)
                .put("data".toByteArray()).putInt(samples * 4)
            repeat(samples) { val value = (kotlin.math.sin(it * 2 * Math.PI * 440 / 44100) * 1000).toInt().toShort(); buffer.putShort(value).putShort(value) }
            val input = directory.resolve("stereo.wav"); Files.write(input, buffer.array())
            val decoded = DesktopMediaDecoder.decode(input, directory)
            AudioSystem.getAudioInputStream(decoded.toFile()).use { wave ->
                assertEquals(16000f, wave.format.sampleRate); assertEquals(1, wave.format.channels); assertEquals(16, wave.format.sampleSizeInBits)
                assertTrue(wave.frameLength in 15900..16100)
            }
            val invalid = directory.resolve("invalid.mp4"); Files.writeString(invalid, "this is not media")
            assertFailsWith<IllegalStateException> { DesktopMediaDecoder.decode(invalid, directory) }
            assertEquals(1, Files.list(directory).use { entries -> entries.filter { it.fileName.toString().startsWith("hearth-decoded-") }.count() })
        } finally { directory.toFile().deleteRecursively() }
    }
}
