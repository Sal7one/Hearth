package com.sal7one.transiber.byok

import android.media.MediaPlayer
import com.sal7one.transiber.caption.CaptionSpeaker
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * OpenAI-compatible /audio/speech client (BYOK TTS).
 *
 * NETWORK CODE — play distribution only; see [ByokPolicy].
 */
class OpenAiSpeechClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "tts-1",
    private val voice: String = "alloy",
) {
    /** Returns raw MP3 bytes for the spoken [text]. */
    fun speak(text: String): ByteArray {
        val payload = JSONObject()
            .put("model", model)
            .put("input", text)
            .put("voice", voice)
            .put("response_format", "mp3")
            .toString()

        val endpoint = baseUrl.trim().trimEnd('/').let {
            if (it.endsWith("/audio/speech")) it else it + "/audio/speech"
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299) {
                throw ByokHttpException(
                    "TTS provider returned HTTP $status from $endpoint: " +
                        String(raw, Charsets.UTF_8).take(200),
                )
            }
            // OpenRouter wraps audio in JSON ({audio:{data: base64, format}});
            // OpenAI-compatible providers return raw bytes. Handle both.
            if (raw.size > 0 && raw[0] == '{'.code.toByte()) {
                val json = org.json.JSONObject(String(raw, Charsets.UTF_8))
                val audio = json.optJSONObject("audio") ?: json
                val data = audio.optString("data")
                if (data.isNotBlank()) {
                    return android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
                }
                throw ByokHttpException(
                    "TTS response JSON had no audio data: " +
                        String(raw, Charsets.UTF_8).take(200),
                )
            }
            return raw
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Cloud TTS caption speaker: one utterance at a time, MP3 via MediaPlayer.
 * NETWORK CODE — play distribution only; see [ByokPolicy].
 */
class CloudTtsSpeaker(
    apiKey: String,
    baseUrl: String = RemoteWhisperEngine.DEFAULT_BASE_URL,
    model: String = "tts-1",
    voice: String = "alloy",
) : CaptionSpeaker {

    private val client = OpenAiSpeechClient(apiKey, baseUrl, model, voice)
    private val queue = ArrayDeque<String>()
    private var player: MediaPlayer? = null
    private var playing = false

    override fun speak(text: String, languageTag: String): Boolean {
        if (text.isBlank()) return false
        synchronized(queue) { queue.addLast(text) }
        drain()
        return true
    }

    private fun drain() {
        val start = synchronized(queue) {
            if (playing || queue.isEmpty()) false else {
                playing = true
                true
            }
        }
        if (!start) return
        Thread {
            try {
                while (true) {
                    val text = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                    playOnce(text)
                }
            } finally {
                synchronized(queue) { playing = false }
                if (synchronized(queue) { queue.isNotEmpty() }) drain()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun playOnce(text: String) {
        try {
            val mp3 = client.speak(text)
            val tmp = File.createTempFile("byok_tts", ".mp3")
            tmp.writeBytes(mp3)
            tmp.deleteOnExit()
            val latch = java.util.concurrent.CountDownLatch(1)
            val mp = MediaPlayer()
            mp.setDataSource(tmp.absolutePath)
            mp.setOnCompletionListener {
                it.release()
                tmp.delete()
                latch.countDown()
            }
            mp.setOnErrorListener { p, _, _ ->
                p.release()
                latch.countDown()
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            latch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // One failed utterance must not kill the queue.
        } finally {
            player = null
        }
    }

    override fun stop() {
        synchronized(queue) { queue.clear() }
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }

    override fun release() {
        stop()
    }
}
