package com.sal7one.transiber.byok

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI-compatible audio transcription/translation client (BYOK).
 *
 * NETWORK CODE — play distribution only; see [ByokPolicy]. Zero extra
 * dependencies: HttpURLConnection + hand-rolled multipart is all a
 * single-file upload needs. The [baseUrl] default targets OpenAI; any
 * OpenAI-compatible endpoint (Groq, local gateways...) works by override.
 */
class OpenAiAudioClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "whisper-1",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 15_000,
) {

    @Volatile private var activeConnection: HttpURLConnection? = null
    fun cancel() { activeConnection?.disconnect() }

    /** /audio/transcriptions: speech → text in the spoken language. */
    fun transcribe(wav: ByteArray, language: String? = null, prompt: String? = null): String =
        call("/audio/transcriptions", wav, language, prompt)

    /** /audio/translations: any language → English (NOT offered by all providers). */
    fun translateToEnglish(wav: ByteArray): String = call("/audio/translations", wav, null, null)

    /**
     * Builds the absolute endpoint from a user-configurable base URL.
     * Accepts: a bare host, a host with /api/v1, or the FULL endpoint —
     * the common paste/keystroke mistakes must not produce 404s.
     */
    private fun endpointFor(path: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            throw ByokHttpException(
                "Base URL is not absolute ($trimmed) — set a full https:// endpoint " +
                    "in the cloud provider settings."
            )
        }
        if (trimmed.endsWith(path)) return trimmed
        return trimmed + path
    }

    private fun call(path: String, wav: ByteArray, language: String?, prompt: String? = null): String {
        check(ByokPolicy.FEATURE_BYOK) { "Cloud services are unavailable in the offline build" }
        val boundary = "sal7one-byok-" + System.nanoTime()
        val body = buildMultipart(boundary, wav, language, prompt)
        val endpoint = endpointFor(path)

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        activeConnection = connection
        try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                throw ByokHttpException(
                    "Provider returned HTTP $status from $endpoint: ${text}",
                )
            }
            return JSONObject(text).optString("text", "").trim()
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    private fun buildMultipart(boundary: String, wav: ByteArray, language: String?, prompt: String? = null): ByteArray {
        val out = ByteArrayOutputStream(wav.size + 1024)
        fun part(name: String, value: String) {
            out.writeAscii("--$boundary\r\n")
            out.writeAscii("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            out.writeAscii(value)
            out.writeAscii("\r\n")
        }
        out.writeAscii("--$boundary\r\n")
        out.writeAscii(
            "Content-Disposition: form-data; name=\"file\"; filename=\"utterance.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n",
        )
        out.write(wav)
        out.writeAscii("\r\n")
        part("model", model)
        language?.takeIf { it.isNotBlank() }?.let { part("language", it) }
        prompt?.takeIf { it.isNotBlank() }?.let { part("prompt", it) }
        part("response_format", "json")
        out.writeAscii("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(s: String) {
        for (b in s.toByteArray(Charsets.US_ASCII)) write(b.toInt())
    }
}

class ByokHttpException(message: String) : Exception(message)
