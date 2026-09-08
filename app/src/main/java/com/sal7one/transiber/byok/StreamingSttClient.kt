package com.sal7one.transiber.byok

/**
 * A provider-agnostic STREAMING speech-to-text client (WebSocket, interim
 * results). NETWORK CODE — play distribution only; see [ByokPolicy].
 *
 * Providers implement the wire protocol (Groq, Deepgram, OpenAI realtime…);
 * the engine above only sees:
 *   - [sendPcm]: 16 kHz mono PCM16 frames, streamed as they arrive
 *   - [onInterim]: the live, still-changing hypothesis for the CURRENT
 *     utterance (called repeatedly)
 *   - [onFinal]: the settled text of a completed utterance
 *   - [onError]: a recoverable/terminal error message
 */
interface StreamingSttClient {

    fun connect()

    fun sendPcm(pcm: ShortArray)

    fun close()

    /**
     * Capture has ended (the audio source stopped). The session is NOT torn
     * down yet — providers that support it flush/commit their buffered audio
     * so the trailing utterance finalizes and its events still arrive; the
     * caller drains events for a grace window and then calls [close].
     */
    fun onCaptureEnded() {}

    var onInterim: ((String) -> Unit)?
    var onFinal: ((String) -> Unit)?
    var onError: ((String) -> Unit)?
    var onConnected: (() -> Unit)?
}
