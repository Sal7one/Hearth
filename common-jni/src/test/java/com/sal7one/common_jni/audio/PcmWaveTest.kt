package com.sal7one.common_jni.audio
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
class PcmWaveTest {
    private val pcmGuid = byteArrayOf(
        1, 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0,
        0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71)

    private fun extensibleWav() = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
        putInt(40); putShort(0xfffe.toShort()); putShort(1); putInt(44100)
        putInt(88200); putShort(2); putShort(16); putShort(22); putShort(16)
        putInt(4); put(pcmGuid)
        put("data".toByteArray()); putInt(4); putShort(32767); putShort(-32768)
    }.array()

    private fun wav(channels: Int=1,rate: Int=44100):ByteArray=ByteBuffer.allocate(44+channels*4).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray());putInt(capacity()-8);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(channels.toShort());putInt(rate);putInt(rate*channels*2);putShort((channels*2).toShort());putShort(16);put("data".toByteArray());putInt(channels*4);repeat(channels){putShort(32767);putShort(-32768)}
    }.array()
    @Test fun preservesVoiceRateAndSignedSamples() {
        val audio=PcmWave.decode(wav());assertEquals(44100,audio.sampleRate);assertArrayEquals(shortArrayOf(32767,-32768),audio.samples)
        assertArrayEquals(shortArrayOf(0,0),PcmWave.decode(wav(2)).samples)
    }
    @Test fun rejectsHeadersThatWouldOverreadOrAllocateExcessiveAudio() {
        for(raw in listOf(wav().copyOf(43),wav().also {it[40]=127},wav().also {it[20]=3},wav().also {it[32]=4},wav(3),wav(rate=96000)))
            assertThrows(IllegalArgumentException::class.java){PcmWave.decode(raw)}
        assertThrows(IllegalArgumentException::class.java){PcmWave.decode(wav(),maxBytes=44)}
        assertThrows(IllegalArgumentException::class.java){PcmWave.decode(wav(),maxSeconds=0)}
    }

    @Test fun acceptsExtensiblePcm16AndUnpaddedFinalMetadata() {
        assertArrayEquals(shortArrayOf(32767, -32768), PcmWave.decode(extensibleWav()).samples)
        val base = wav()
        val unpadded = ByteBuffer.allocate(base.size + 9).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(base); put("JUNK".toByteArray()); putInt(1); put(7)
            putInt(4, capacity() - 8)
        }.array()
        assertArrayEquals(shortArrayOf(32767, -32768), PcmWave.decode(unpadded).samples)
    }

    @Test fun rejectsMalformedOrNonPcmExtensibleHeaders() {
        val original = extensibleWav()
        for (mutated in listOf(
            original.copyOf().apply { this[44] = 3 }, // Non-PCM subtype.
            original.copyOf().apply { this[36] = 20 }, // cbSize cannot hold the extension.
            original.copyOf().apply { this[38] = 17 }, // More valid bits than the container.
            original.copyOf().apply { this[40] = 3 }   // Mono format cannot name two speakers.
        )) {
            assertThrows(IllegalArgumentException::class.java) { PcmWave.decode(mutated) }
        }
    }
}
