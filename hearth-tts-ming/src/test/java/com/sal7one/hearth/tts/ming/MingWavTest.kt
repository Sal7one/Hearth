package com.sal7one.hearth.tts.ming

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MingWavTest {
    @Test fun decodesMono44kPcm16ToUnitFloatSamples() {
        val pcm = MingWav.decode(MingTestSupport.wavBytes(shortArrayOf(0, 16_384, -32_768, 32_767)))
        assertEquals(44_100, pcm.sampleRate)
        assertArrayEquals(floatArrayOf(0f, 0.5f, -1f, 32_767 / 32_768f), pcm.samples, 0f)
    }

    @Test fun rejectsAnythingThatIsNotTheSingleSupportedContract() {
        assertThrows(IllegalArgumentException::class.java) {
            MingWav.decode(MingTestSupport.wavBytes(shortArrayOf(1, 2), sampleRate = 48_000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MingWav.decode(MingTestSupport.wavBytes(shortArrayOf(1, 2), channels = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MingWav.decode(MingTestSupport.wavBytes(shortArrayOf(1, 2), bits = 8))
        }
        assertThrows(IllegalArgumentException::class.java) { MingWav.decode(ByteArray(100)) }
        assertThrows(IllegalArgumentException::class.java) {
            MingWav.decode(MingTestSupport.wavBytes(shortArrayOf()).copyOf(43))
        }
    }

    @Test fun walksUnknownChunksAndRejectsTruncatedBodies() {
        val valid = MingTestSupport.wavBytes(shortArrayOf(1, 2, 3))
        // A zero-length unknown chunk before the data chunk must be skipped by the RIFF walk.
        val patched = valid.copyOfRange(0, 36) + "JUNK" .toByteArray() + byteArrayOf(0, 0, 0, 0) + valid.copyOfRange(36, valid.size)
        val decoded = MingWav.decode(patched)
        assertEquals(3, decoded.samples.size)

        val truncated = valid.copyOf(valid.size - 4)
        assertThrows(IllegalArgumentException::class.java) { MingWav.decode(truncated) }
    }

    @Test fun anEmptyDataChunkIsNotSpeech() {
        val valid = MingTestSupport.wavBytes(shortArrayOf())
        assertThrows(IllegalArgumentException::class.java) { MingWav.decode(valid) }
    }
}
