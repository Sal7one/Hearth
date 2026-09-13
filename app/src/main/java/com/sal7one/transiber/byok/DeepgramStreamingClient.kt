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
 * Deepgram STREAMING speech-to-text client (WebSocket, interim results).
 * NETWORK CODE — play distribution only; see [ByokPolicy].
 *
 * Verified against Deepgram's docs plus a live auth handshake 2026-08-23:
 *  - wss://api.deepgram.com/v1/listen with model=nova-3, language=multi
 *    (the multilingual model auto-detects across 10 languages), encoding=
 *    linear16, sample_rate=16000, channels=1, interim_results=true,
 *    smart_format=true, no_delay=true, utterance_end_ms=1000
 *  - Authorization: Token <secret key>, enforced before the WS upgrade
 *  - client frames: raw binary PCM16 LE audio; KeepAlive/CloseStream text
 *  - server "Results" transcripts are CUMULATIVE for the current
 *    utterance — replace the live line on every event, never append;
 *    speech_final marks the settled utterance (commit it)
 *  - NET-0001 drops the socket when no frame arrives within 10 s of open
 *    and NET-0002 on prolonged silence — an idle timer streams 100 ms
 *    silence frames whenever capture is quiet (playback capture before
 *    media starts, mic gated by the OS, etc.)
 */
class DeepgramStreamingClient(
    private val apiKey: String,
    private val model: String = "nova-3",
    private val language: String? = "multi",
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
        val url = buildString {
            append("wss://api.deepgram.com/v1/listen?model=").append(model)
            if (!language.isNullOrBlank()) append("&language=").append(language)
            append("&encoding=linear16&sample_rate=16000&channels=1")
            append("&interim_results=true&smart_format=true&no_delay=true")
            append("&utterance_end_ms=1000")
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // First-frame rule: an audio frame must land within 10 s of
                // open or the server drops the socket (NET-0001).
                sendSilenceFrame()
                startKeepalive()
                onConnected?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("type")) {
                    "Results" -> {
                        val transcript = json.optJSONObject("channel")
                            ?.optJSONArray("alternatives")
                            ?.optJSONObject(0)
                            ?.optString("transcript")
                            ?.takeIf { it.isNotBlank() } ?: return
                        if (json.optBoolean("speech_final")) {
                            onFinal?.invoke(transcript)
                        } else {
                            // Interim AND non-speech finals carry the whole
                            // utterance so far — replace, don't append.
                            onInterim?.invoke(transcript)
                        }
                    }
                    "Error" -> onError?.invoke(
                        json.optString("description").ifBlank {
                            json.optString("message").ifBlank { "unknown Deepgram error" }
                        },
                    )
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
        runCatching { socket?.send(pcmToBytes(pcm).toByteString()) }
    }

    override fun close() {
        closing = true
        stopKeepalive()
        runCatching { socket?.send("""{"type":"CloseStream"}""") }
        socket?.close(1000, "client close")
        socket = null
    }

    override fun onCaptureEnded() {
        // CloseStream makes the server FINISH processing the buffered audio,
        // emit the remaining Results (speech_final included) and Metadata,
        // then close the connection itself — a graceful end-of-source drain.
        closing = true
        stopKeepalive()
        runCatching { socket?.send("""{"type":"CloseStream"}""") }
    }

    /** 100 ms of silence satisfies the first-frame and no-audio timers
     * while capture is idle. */
    private fun sendSilenceFrame() {
        lastAudioAtMs = System.currentTimeMillis()
        runCatching { socket?.send(ByteArray(SILENCE_BYTES).toByteString()) }
    }

    private fun startKeepalive() {
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "deepgram-keepalive").apply { isDaemon = true }
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

    private fun pcmToBytes(pcm: ShortArray): ByteArray {
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private companion object {
        /** 1600 samples of zeros @16 kHz = 100 ms. */
        const val SILENCE_BYTES = 3200
        const val KEEPALIVE_PERIOD_MS = 1_000L
        const val KEEPALIVE_GAP_MS = 800L
    }
}
