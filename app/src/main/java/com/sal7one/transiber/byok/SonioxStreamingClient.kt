package com.sal7one.transiber.byok

import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

class SonioxStreamingClient(private val apiKey: String, private val language: String,
    private val target: String?) : StructuredCaptionClient {
    override var onInterim: ((String) -> Unit)? = null
    override var onFinal: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onConnected: (() -> Unit)? = null
    override var onCaption: ((CloudCaptionUpdate) -> Unit)? = null
    override var onTranslatedInterim: ((String) -> Unit)? = null
    private val transcript = SonioxTranscript()
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).pingInterval(10, TimeUnit.SECONDS).build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var ending = false
    override fun connect() {
        check(ByokPolicy.FEATURE_BYOK) { "Network disabled in offline app" }
        socket = http.newWebSocket(Request.Builder().url("wss://stt-rt.soniox.com/transcribe-websocket").build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (closed) { ws.close(1000, null); return }
                val config = JSONObject().put("api_key", apiKey).put("model", "stt-rt-v5")
                    .put("audio_format", "pcm_s16le").put("sample_rate", 16000).put("num_channels", 1)
                    .put("enable_language_identification", true).put("enable_endpoint_detection", true)
                    .put("max_endpoint_delay_ms", 1000)
                if (language != "auto") config.put("language_hints", JSONArray().put(language))
                if (target != null) config.put("translation", JSONObject().put("type", "one_way").put("target_language", target))
                if (!ws.send(config.toString())) { onError?.invoke("Soniox configuration send failed"); return }
                onConnected?.invoke()
            }
            override fun onMessage(ws: WebSocket, value: String) {
                if (closed) return
                try {
                    val event = JSONObject(value)
                    if (event.has("error_code")) {
                        onError?.invoke("${event.optInt("error_code")}: ${event.optString("error_message")}"); return
                    }
                    val result = transcript.accept(event)
                    result.updates.forEach { onCaption?.invoke(it) }
                    onInterim?.invoke(result.partial); onTranslatedInterim?.invoke(result.translation)
                } catch (e: Exception) { onError?.invoke(e.message ?: e.toString()) }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!closed) onError?.invoke(response?.let { "HTTP ${it.code}: ${it.message}" } ?: t.toString())
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, reason)
                if (!closed && !ending) onError?.invoke("Soniox connection closed: $code $reason")
            }
        })
    }
    override fun sendPcm(pcm: ShortArray) {
        if (closed || ending) return
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { bytes.putShort(it) }
        check(socket?.send(bytes.array().toByteString()) == true) { "Soniox audio send failed" }
    }
    override fun onCaptureEnded() { ending = true; if (socket?.send("") != true) onError?.invoke("Soniox end-of-audio send failed") }
    override fun close() { closed = true; socket?.close(1000, null); socket = null; http.dispatcher.executorService.shutdown() }
}
