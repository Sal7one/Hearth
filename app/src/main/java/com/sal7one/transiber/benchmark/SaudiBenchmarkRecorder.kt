package com.sal7one.transiber.benchmark

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded microphone recording for a personally spoken Saudi reference clip. */
internal object SaudiBenchmarkRecorder {
    @SuppressLint("MissingPermission") // Checked by the screen immediately before capture.
    suspend fun capture(stop: AtomicBoolean): BenchmarkAudio.Clip = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Microphone buffer query failed: $minimum" }
        val recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, 4096)).build()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            val chunks = ArrayList<ShortArray>()
            var count = 0
            val buffer = ShortArray(1024)
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!stop.get() && count < 16_000 * 15 && System.nanoTime() < deadline) {
                val read = recorder.read(buffer, 0, minOf(buffer.size, 16_000 * 15 - count), AudioRecord.READ_BLOCKING)
                check(read >= 0) { "Microphone read failed: $read" }
                if (read > 0) {
                    chunks += buffer.copyOf(read)
                    count += read
                } else delay(10)
            }
            require(count >= 8_000) { "Record at least half a second" }
            val samples = ShortArray(count)
            var offset = 0
            chunks.forEach { chunk -> chunk.copyInto(samples, offset); offset += chunk.size }
            BenchmarkAudio.fromPcm16(samples)
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release()
        }
    }
}
