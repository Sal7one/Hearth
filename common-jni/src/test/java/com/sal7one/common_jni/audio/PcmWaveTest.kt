package com.sal7one.common_jni.audio
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
class PcmWaveTest {
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
}
