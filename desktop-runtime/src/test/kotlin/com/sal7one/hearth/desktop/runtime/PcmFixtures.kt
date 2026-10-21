package com.sal7one.hearth.desktop.runtime

import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/** Converts any readable WAV to [−1, 1] mono floats at 16 kHz, the rate the speech engines expect. */
internal fun readPcm16Mono(path: Path): FloatArray {
    AudioSystem.getAudioInputStream(path.toFile()).use { raw ->
        val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16_000f, 16, 1, 2, 16_000f, false)
        AudioSystem.getAudioInputStream(target, raw).use { converted ->
            val bytes = converted.readBytes()
            val floats = FloatArray(bytes.size / 2)
            for (index in floats.indices) {
                val low = bytes[index * 2].toInt() and 0xFF
                val high = bytes[index * 2 + 1].toInt()
                floats[index] = ((high shl 8) or low) / 32_768f
            }
            return floats
        }
    }
}
