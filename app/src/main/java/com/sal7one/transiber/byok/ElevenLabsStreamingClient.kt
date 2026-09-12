package com.sal7one.transiber.byok

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Scribe v2 Realtime. VAD commits, optional language hint, no generative cleanup. */
class ElevenLabsStreamingClient(private val apiKey: String, private val language: String) : StreamingSttClient {
    override var onInterim: ((String) -> Unit)? = null
    override var onFinal: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onConnected: (() -> Unit)? = null
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).pingInterval(10, TimeUnit.SECONDS).build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var ending = false
    override fun connect() {
        check(ByokPolicy.FEATURE_BYOK) { "Network disabled in offline app" }
        val url = "https://api.elevenlabs.io/v1/speech-to-text/realtime".toHttpUrl().newBuilder()
            .addQueryParameter("model_id", "scribe_v2_realtime").addQueryParameter("audio_format", "pcm_16000")
            .addQueryParameter("commit_strategy", "vad").addQueryParameter("vad_silence_threshold_secs", "0.5")
            .addQueryParameter("include_timestamps", "false").addQueryParameter("no_verbatim", "false")
        if (language != "auto") url.addQueryParameter("language_code", language)
        socket = http.newWebSocket(Request.Builder().url(url.build()).header("xi-api-key", apiKey).build(), object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, value: String) {
                if (closed) return
                try {
                    when (val event = ScribeEvent.parse(value)) {
                        ScribeEvent.Connected -> onConnected?.invoke()
                        is ScribeEvent.Partial -> onInterim?.invoke(event.text)
                        is ScribeEvent.Final -> { onFinal?.invoke(event.text); onInterim?.invoke("") }
                        is ScribeEvent.Error -> onError?.invoke(event.message)
                        ScribeEvent.Ignore -> Unit
                    }
                } catch (e: Exception) { onError?.invoke(e.message ?: e.toString()) }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!closed) onError?.invoke(response?.let { "HTTP ${it.code}: ${it.message}" } ?: t.toString())
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, reason)
                if (!closed && !ending) onError?.invoke("Scribe connection closed: $code $reason")
            }
        })
    }
    override fun sendPcm(pcm: ShortArray) {
        if (closed || ending) return
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { bytes.putShort(it) }
        check(socket?.send(JSONObject().put("message_type", "input_audio_chunk")
            .put("audio_base_64", Base64.getEncoder().encodeToString(bytes.array())).put("sample_rate", 16000).put("commit", false).toString()) == true) { "Scribe audio send failed" }
    }
    override fun onCaptureEnded() {
        ending = true
        if (socket?.send(JSONObject().put("message_type", "input_audio_chunk").put("audio_base_64", "").put("commit", true).put("sample_rate", 16000).toString()) != true) onError?.invoke("Scribe commit failed")
    }
    override fun close() { closed = true; socket?.close(1000, null); socket = null; http.dispatcher.executorService.shutdown() }
}
