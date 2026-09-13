package com.sal7one.transiber.byok

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * AssemblyAI STREAMING speech-to-text client (v3 WebSocket, interim
 * results). NETWORK CODE — play distribution only; see [ByokPolicy].
 *
 * Verified against AssemblyAI's streaming reference 2026-08-23:
 *  - wss://streaming.assemblyai.com/v3/ws?sample_rate=16000
 *    (the v2 endpoint on api.assemblyai.com is the legacy protocol)
 *  - Authorization header carries the RAW API key — NO "Bearer" prefix
 *  - audio frames are raw binary PCM16 LE, 50–1000 ms per message
 *  - the server emits "Turn" events: end_of_turn=false carries the live
 *    partial, end_of_turn=true the settled utterance ("transcript" field)
 *  - the client ends a session by sending {"type":"Terminate"}
 */
class AssemblyAiStreamingClient(
    private val apiKey: String,
    private val sampleRate: Int = 16_000,
    private val speechModel: String = "universal-3-5-pro",
) : StreamingSttClient {

    override var onInterim: ((String) -> Unit)? = null
    override var onFinal: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onConnected: (() -> Unit)? = null

    private var socket: WebSocket? = null
    private var closing = false

    @Volatile private var lastAudioAtMs = 0L
    private var keepalive: ScheduledExecutorService? = null

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    override fun connect() {
        check(ByokPolicy.FEATURE_BYOK) { "Cloud services are unavailable in the offline build" }
        val url = "wss://streaming.assemblyai.com/v3/ws" +
            "?sample_rate=$sampleRate&speech_model=$speechModel"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", apiKey)
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // AssemblyAI ends sessions with no audio (~60 s) — stream
                // silence whenever capture is idle to keep it alive.
                sendSilenceFrame()
                startKeepalive()
                onConnected?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("type")) {
                    "Turn" -> {
                        val transcript = json.optString("transcript")
                        if (transcript.isBlank()) return
                        if (json.optBoolean("end_of_turn")) {
                            onFinal?.invoke(transcript)
                        } else {
                            onInterim?.invoke(transcript)
                        }
                    }
                    "Termination" -> if (!closing) {
                        // We didn't ask to end — the provider closed the
                        // session on us.
                        onError?.invoke("Session terminated by provider")
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!closing && code != 1000) {
                    onError?.invoke("Socket closed: $code $reason".trim())
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError?.invoke(
                    response?.let { "HTTP ${it.code}: ${it.message}" }
                        ?: (t.message ?: "connection failed"),
                )
            }
        })
    }

    override fun sendPcm(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        lastAudioAtMs = System.currentTimeMillis()
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        runCatching { socket?.send(bytes.toByteString()) }
    }

    override fun close() {
        closing = true
        stopKeepalive()
        runCatching { socket?.send("""{"type":"Terminate"}""") }
        socket?.close(1000, "client close")
        socket = null
    }

    /** 100 ms of silence keeps the session's inactivity timer satisfied
     * while capture is idle. */
    private fun sendSilenceFrame() {
        lastAudioAtMs = System.currentTimeMillis()
        runCatching { socket?.send(ByteArray(SILENCE_BYTES).toByteString()) }
    }

    private fun startKeepalive() {
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "assemblyai-keepalive").apply { isDaemon = true }
        }
        keepalive = executor
        executor.scheduleWithFixedDelay(
            {
                if (System.currentTimeMillis() - lastAudioAtMs >= KEEPALIVE_GAP_MS) {
                    sendSilenceFrame()
                }
            },
            KEEPALIVE_PERIOD_MS,
            KEEPALIVE_PERIOD_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopKeepalive() {
        keepalive?.shutdownNow()
        keepalive = null
    }

    private companion object {
        /** 1600 samples of zeros @16 kHz = 100 ms. */
        const val SILENCE_BYTES = 3200
        const val KEEPALIVE_PERIOD_MS = 1_000L
        const val KEEPALIVE_GAP_MS = 800L
    }
}
