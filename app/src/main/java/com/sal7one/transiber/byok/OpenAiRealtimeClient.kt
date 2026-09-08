package com.sal7one.transiber.byok

import com.sal7one.common_jni.audio.Pcm16Resampler
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Source-language CC. Never request assistant responses or treat a transcript as a translation. */
class OpenAiRealtimeClient(
    private val apiKey: String,
    private val transcriptionModel: String = "gpt-live-transcribe",
    private val sessionModel: String = "gpt-realtime-2.1",
    private val sourceLanguage: String = "auto",
) : StreamingSttClient {
    override var onInterim: ((String) -> Unit)? = null
    override var onFinal: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onConnected: (() -> Unit)? = null
    private val resampler = Pcm16Resampler(16_000, 24_000)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    private val transcript = RealtimeTranscript()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).pingInterval(10, TimeUnit.SECONDS).build()

    override fun connect() {
        check(ByokPolicy.FEATURE_BYOK) { RemoteWhisperEngine.NETWORK_DISABLED_MESSAGE }
        // Retain the account-verified realtime session transport; disable both
        // assistant response generation and interruption explicitly.
        socket = client.newWebSocket(Request.Builder().url("wss://api.openai.com/v1/realtime?model=$sessionModel")
            .header("Authorization", "Bearer $apiKey").build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (closed) { ws.cancel(); return }
                ws.send(sessionUpdate(transcriptionModel, sourceLanguage).toString())
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (closed) return
                try {
                    val e = JSONObject(text)
                    val id = e.optString("item_id")
                    when (e.optString("type")) {
                        "session.updated" -> onConnected?.invoke()
                        "input_audio_buffer.speech_started", "input_audio_buffer.committed" -> transcript.begin(id)
                        "conversation.item.input_audio_transcription.delta" -> {
                            transcript.append(id, e.getString("delta"))
                            onInterim?.invoke(transcript.partial)
                        }
                        "conversation.item.input_audio_transcription.completed" -> {
                            transcript.finish(id, e.getString("transcript")).forEach { onFinal?.invoke(it) }
                            onInterim?.invoke(transcript.partial)
                        }
                        "conversation.item.input_audio_transcription.failed", "error" ->
                            onError?.invoke(e.optJSONObject("error")?.toString() ?: text)
                    }
                } catch (e: Exception) { onError?.invoke("Transcription event: ${e.message}") }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!closed) onError?.invoke("Transcription socket closed: $code $reason")
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!closed) onError?.invoke(response?.let { "HTTP ${it.code}: ${it.body?.string() ?: it.message}" } ?: (t.message ?: t.javaClass.simpleName))
            }
        })
    }

    override fun sendPcm(pcm: ShortArray) {
        if (closed || pcm.isEmpty()) return
        val ws = checkNotNull(socket) { "Transcription socket not connected" }
        check(ws.queueSize() < 256_000) { "Transcription network cannot keep up (outgoing audio queue exceeded 256 KB)" }
        val audio = resampler.push(pcm)
        val bytes = ByteArray(audio.size * 2)
        audio.forEachIndexed { i, s -> bytes[i * 2] = s.toByte(); bytes[i * 2 + 1] = (s.toInt() shr 8).toByte() }
        check(ws.send(JSONObject().put("type", "input_audio_buffer.append").put("audio", bytes.toByteString().base64()).toString())) {
            "Transcription socket rejected audio"
        }
    }

    override fun close() {
        closed = true
        socket?.cancel()
        socket = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    internal fun resample16kTo24k(src: ShortArray): ShortArray = Pcm16Resampler.frame(src, 16_000, 24_000)


    companion object {
        internal fun sessionUpdate(model: String, language: String): JSONObject {
            val transcription = JSONObject().put("model", model)
            if (model == "gpt-live-transcribe") {
                transcription.put("delay", "low")
                if (language.isNotBlank() && language != "auto") transcription.put("languages", JSONArray().put(language))
            } else if (language.isNotBlank() && language != "auto") transcription.put("language", language)
            return JSONObject().put("type", "session.update").put("session", JSONObject().put("type", "realtime")
                .put("audio", JSONObject().put("input", JSONObject()
                    .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24_000))
                    .put("transcription", transcription)
                    .put("turn_detection", JSONObject().put("type", "server_vad").put("threshold", 0.5)
                        .put("prefix_padding_ms", 300).put("silence_duration_ms", 500)
                        .put("create_response", false).put("interrupt_response", false)))))
        }
    }
}
