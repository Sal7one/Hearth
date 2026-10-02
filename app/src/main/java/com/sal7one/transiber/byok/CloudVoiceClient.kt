package com.sal7one.transiber.byok

import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** A cancellable, bounded unary request. Never follows redirects with a provider credential. */
internal class CloudVoiceClient(private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    private val lock = Any()
    private var cancelled = false
    private var active: HttpURLConnection? = null
    fun cancel() = synchronized(lock) { cancelled = true; active?.disconnect() }
    fun speak(config: CloudVoiceConfig, key: String, text: String): CloudVoiceProtocol.Audio {
        val request = CloudVoiceProtocol.speech(config, text)
        val (bytes, type) = execute(request, key, CloudVoiceProtocol.JSON_AUDIO_LIMIT)
        try { return CloudVoiceProtocol.audio(config, bytes, type) }
        catch (e: Exception) { throw IllegalStateException(e.message.orEmpty().replace(key, "<redacted>"), e.takeUnless { it.message.orEmpty().contains(key) }) }
    }
    fun catalog(request: CloudVoiceProtocol.Request, key: String): String = execute(request, key, CloudVoiceProtocol.CATALOG_LIMIT).first.toString(Charsets.UTF_8)
    private fun execute(request: CloudVoiceProtocol.Request, key: String, limit: Int): Pair<ByteArray, String?> {
        check(ByokPolicy.FEATURE_BYOK) { "Cloud services are unavailable in the offline build" }
        require(key.isNotBlank() && !key.any(Char::isISOControl)) { "Invalid or missing voice credential" }
        val connection = synchronized(lock) {
            if (cancelled) throw CancellationException("Read aloud stopped")
            open(URL(request.url)).also { active = it }
        }
        try {
            connection.apply {
                instanceFollowRedirects = false; requestMethod = if (request.body == null) "GET" else "POST"
                connectTimeout = 15000; readTimeout = 60000
                setRequestProperty(request.header, if (request.header == "Authorization") "Bearer $key" else key)
                setRequestProperty("Content-Type", "application/json")
            }
            request.body?.let { body -> connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bound = if (status in 200..299) limit else 65536
            val raw = stream?.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    synchronized(lock) { if (cancelled) throw CancellationException("Read aloud stopped") }
                    val n = input.read(buffer); if (n < 0) break
                    check(n <= bound - output.size()) { "TTS provider response exceeds size limit" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            synchronized(lock) { if (cancelled) throw CancellationException("Read aloud stopped") }
            if (status !in 200..299) throw ByokHttpException("TTS provider returned HTTP $status: " + raw.toString(Charsets.UTF_8).replace(key, "<redacted>").take(400))
            return raw to connection.contentType
        } catch (e: Exception) {
            synchronized(lock) { if (cancelled) throw CancellationException("Read aloud stopped") }
            if (e.message.orEmpty().contains(key)) throw ByokHttpException(e.message.orEmpty().replace(key, "<redacted>"))
            throw e
        } finally {
            synchronized(lock) { if (active === connection) active = null }; connection.disconnect()
        }
    }
}
