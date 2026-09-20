package com.sal7one.transiber.byok

import com.sal7one.common_jni.audio.Pcm16Resampler
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Dedicated live translation protocol. Input silence is supplied by capture, at real cadence. */
class OpenAiTranslateClient(
    private val apiKey: String,
    private val targetLanguage: String,
) : StreamingSttClient {
    override var onInterim: ((String) -> Unit)? = null
    override var onFinal: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onConnected: (() -> Unit)? = null
    private val resampler = Pcm16Resampler(16_000, 24_000)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    private val transcript = TranslationTranscript()
    private var lastDeltaNs = 0L
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "caption-output").apply { isDaemon = true } }
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).pingInterval(10, TimeUnit.SECONDS).build()

    override fun connect() {
        check(ByokPolicy.FEATURE_BYOK) { RemoteWhisperEngine.NETWORK_DISABLED_MESSAGE }
        val update = sessionUpdate(targetLanguage).toString() // Validate before entering the socket callback thread.
        socket = client.newWebSocket(Request.Builder()
            .url("wss://api.openai.com/v1/realtime/translations?model=gpt-realtime-translate")
            .header("Authorization", "Bearer $apiKey").build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (closed) { ws.cancel(); return }
                ws.send(update)
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (closed) return
                try {
                    val event = JSONObject(text)
                    when (event.optString("type")) {
                        "session.updated" -> onConnected?.invoke()
                        "session.output_transcript.delta" -> synchronized(transcript) {
                            lastDeltaNs = System.nanoTime()
                            transcript.append(event.getString("delta")).forEach { onFinal?.invoke(it) }
                            onInterim?.invoke(transcript.partial)
                        }
                        "error" -> onError?.invoke(event.optJSONObject("error")?.toString() ?: text)
                    }
                } catch (e: Exception) { onError?.invoke("Translation event: ${e.message}") }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!closed) onError?.invoke("Translation socket closed: $code $reason")
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!closed) onError?.invoke(response?.let { "HTTP ${it.code}: ${it.body?.string() ?: it.message}" } ?: (t.message ?: t.javaClass.simpleName))
            }
        })
        timer.scheduleWithFixedDelay({
            synchronized(transcript) {
                if (!closed && lastDeltaNs > 0 && System.nanoTime() - lastDeltaNs >= 1_200_000_000L) {
                    transcript.flush().takeIf { it.isNotEmpty() }?.let { onFinal?.invoke(it); onInterim?.invoke("") }
                }
            }
        }, 200, 200, TimeUnit.MILLISECONDS)
    }

    override fun sendPcm(pcm: ShortArray) {
        if (closed || pcm.isEmpty()) return
        val ws = checkNotNull(socket) { "Translation socket not connected" }
        check(ws.queueSize() < 256_000) { "Translation network cannot keep up (outgoing audio queue exceeded 256 KB)" }
        check(ws.send(JSONObject().put("type", "session.input_audio_buffer.append")
            .put("audio", pcm24kBytes(pcm).toByteString().base64()).toString())) { "Translation socket rejected audio" }
    }

    override fun close() {
        closed = true
        timer.shutdownNow()
        socket?.cancel()
        socket = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        internal fun sessionUpdate(language: String): JSONObject {
            require(CloudSpeechLanguages.isExplicitLanguageCode(language)) { "Invalid translation language code: $language" }
            return JSONObject().put("type", "session.update").put("session",
                JSONObject().put("audio", JSONObject().put("output", JSONObject().put("language", language))))
        }
    }

    internal fun resample16kTo24k(src: ShortArray): ShortArray = Pcm16Resampler.frame(src, 16_000, 24_000)


    private fun pcm24kBytes(pcm: ShortArray): ByteArray {
        val audio = resampler.push(pcm)
        return ByteArray(audio.size * 2).also { bytes ->
            audio.forEachIndexed { i, sample -> bytes[i * 2] = sample.toByte(); bytes[i * 2 + 1] = (sample.toInt() shr 8).toByte() }
        }
    }
}
