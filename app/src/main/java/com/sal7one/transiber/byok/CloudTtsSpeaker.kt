package com.sal7one.transiber.byok

import android.media.MediaPlayer
import com.sal7one.transiber.caption.CaptionSpeaker
import com.sal7one.transiber.caption.CaptionSpeechQueue
import com.sal7one.transiber.caption.SpeakerGender
import com.sal7one.transiber.caption.toVoiceGender
import com.sal7one.transiber.voice.VoiceGenderMapping
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
    private val connectionLock = Any()
    private var cancelled = false
    private var activeConnection: HttpURLConnection? = null

    fun cancel() = synchronized(connectionLock) {
        cancelled = true
        activeConnection?.disconnect()
    }

    /** Returns raw MP3 bytes for the spoken [text]. */
    fun speak(text: String): ByteArray {
        check(ByokPolicy.FEATURE_BYOK) { "Cloud services are unavailable in the offline build" }
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
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            synchronized(connectionLock) {
                if (cancelled) throw CancellationException("Read aloud stopped")
                activeConnection = connection
            }
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val limit = if (status in 200..299) 12 * 1024 * 1024 else 65_536
            val raw = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(out.size() + count <= limit) { "TTS provider response exceeds size limit" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } ?: ByteArray(0)
            if (status !in 200..299) {
                throw ByokHttpException(
                    "TTS provider returned HTTP $status from $endpoint: " +
                        String(raw, Charsets.UTF_8).replace(apiKey, "<redacted>").take(200),
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
                        String(raw, Charsets.UTF_8).replace(apiKey, "<redacted>").take(200),
                )
            }
            check(raw.isNotEmpty()) { "TTS provider returned empty audio" }
            return raw
        } finally {
            synchronized(connectionLock) { if (activeConnection === connection) activeConnection = null }
            connection.disconnect()
        }
    }
}

/**
 * Cloud TTS caption speaker: one utterance at a time, MP3 via MediaPlayer.
 * NETWORK CODE — play distribution only; see [ByokPolicy].
 */
class CloudTtsSpeaker(
    private val apiKey: String,
    private val baseUrl: String = RemoteWhisperEngine.DEFAULT_BASE_URL,
    private val model: String = "tts-1",
    private val voice: String = "alloy",
    private val onError: (String) -> Unit = {},
    private val cacheDirectory: File? = null,
) : CaptionSpeaker {
    private data class Request(val text: String, val voice: String, val volume: Float)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = CaptionSpeechQueue<Request>(scope, onError, ::playOnce)
    @Volatile private var client: OpenAiSpeechClient? = null
    private var player: MediaPlayer? = null

    override fun speak(text: String, languageTag: String): Boolean =
        speak(text, languageTag, SpeakerGender.ANY, 100)

    override fun speak(text: String, languageTag: String, gender: SpeakerGender, volumePercent: Int): Boolean {
        if (text.isBlank()) return false
        if (text.length > 5000) {
            onError("Read aloud accepts at most 5000 characters")
            return false
        }
        val accepted = queue.offer(Request(text, VoiceGenderMapping.cloudVoice(voice, gender.toVoiceGender()),
            volumePercent.coerceIn(0, 100) / 100f))
        if (!accepted) onError("Speech queue is full or closed. Skipped a new line to avoid falling behind.")
        return accepted
    }

    private suspend fun playOnce(request: Request) {
        val requestClient = OpenAiSpeechClient(apiKey, baseUrl, model, request.voice)
        client = requestClient
        try {
            val mp3 = withContext(Dispatchers.IO) { requestClient.speak(request.text) }
            currentCoroutineContext().ensureActive()
            val tmp = withContext(Dispatchers.IO + NonCancellable) {
                File.createTempFile("byok_tts", ".mp3", cacheDirectory).also {
                    try { it.writeBytes(mp3) } catch (e: Exception) { it.delete(); throw e }
                }
            }
            var mp: MediaPlayer? = null
            try {
                currentCoroutineContext().ensureActive()
                val output = MediaPlayer().also { mp = it; player = it }
                // Read-aloud audio must not enter our USAGE_MEDIA playback capture.
                output.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                output.setDataSource(tmp.absolutePath)
                output.setVolume(request.volume, request.volume)
                withTimeout(120_000) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        output.setOnPreparedListener {
                            if (continuation.isActive) try { it.start() }
                            catch (e: Exception) { continuation.resumeWithException(e) }
                        }
                        output.setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
                        output.setOnErrorListener { _, what, extra ->
                            if (continuation.isActive) continuation.resumeWithException(
                                IllegalStateException("Android voice playback failed: $what / $extra"))
                            true
                        }
                        output.prepareAsync()
                    }
                }
            } finally {
                player = null
                mp?.release()
                withContext(Dispatchers.IO + NonCancellable) { tmp.delete() }
            }
        } finally {
            if (client === requestClient) client = null
            requestClient.cancel()
        }
    }

    override fun stop() {
        queue.stop()
        client?.cancel()
    }

    override fun release() {
        stop()
        queue.close()
        scope.cancel()
    }
}
